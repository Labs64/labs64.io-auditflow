package io.labs64.audit.controller;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.CorrelationIdFilter;
import io.labs64.audit.exception.PublishException;
import io.labs64.audit.publisher.AuditPublisherService;
import io.labs64.audit.tenant.TenantGate;
import io.labs64.audit.v1.api.AuditEventApi;
import io.labs64.authcontext.authorization.Authorize;
import io.labs64.authcontext.core.AuthContextHolder;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.auditflow.model.AuditEventBatch;
import io.labs64.auditflow.model.BatchPublishEntryResult;
import io.labs64.auditflow.model.BatchPublishResult;
import io.labs64.auditflow.model.ErrorCode;
import io.labs64.auditflow.model.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;

/**
 * REST Controller for handling audit event publication.
 * Implements the OpenAPI-generated interface for type safety.
 *
 * <p>Error handling is delegated to {@link io.labs64.audit.exception.GlobalExceptionHandler}
 * to ensure consistent {@code ErrorResponse} format across all endpoints.</p>
 *
 * <p>The controller is root-mapped: the {@code /<module>/api/v1} prefix is owned and
 * stripped by the Traefik gateway (see labs64.io-helm-charts, chart-libs gateway-routes).</p>
 *
 * <p>Both endpoints answer success only for events the broker has confirmed (stored).</p>
 */
@RestController
public class AuditEventController implements AuditEventApi {

    private static final Logger logger = LoggerFactory.getLogger(AuditEventController.class);

    private final AuditPublisherService publisherService;
    private final TenantGate tenantGate;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public AuditEventController(AuditPublisherService publisherService, TenantGate tenantGate,
                                ObjectMapper objectMapper, Validator validator) {
        this.publisherService = publisherService;
        this.tenantGate = tenantGate;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    /**
     * Publish an audit event to the message broker.
     *
     * @param event The audit event to publish
     * @return Response indicating success or failure
     */
    @Override
    @Authorize(action = "publishEvent", resourceType = "AuditEvent")
    public ResponseEntity<String> publishEvent(@Valid AuditEvent event) {
        OffsetDateTime receivedAt = OffsetDateTime.now();
        prepare(event, receivedAt);
        String eventId = event.getEventId().toString();

        // Ingest gate: provisioning + per-tenant quota, BEFORE the event reaches the broker.
        tenantGate.check(event.getTenantId());

        logger.debug("Received request to publish audit event; eventId={}", eventId);
        if (!publisherService.publishMessage(event)) {
            throw new PublishException("Failed to publish audit event to message broker; eventId=" + eventId);
        }

        logger.info("Audit event published successfully; eventId={}", eventId);
        return ResponseEntity.ok()
                .header("X-Audit-Event-Id", eventId)
                .header("X-Audit-Received-At", receivedAt.toString())
                .body("Audit event published successfully");
    }

    /**
     * Publish up to 100 events with a result per event: one invalid, over-quota or unconfirmed event
     * never blocks the others. The tenant is checked once for the whole batch (403 if it cannot
     * publish at all); quota is spent per event.
     */
    @Override
    @Authorize(action = "publishEvents", resourceType = "AuditEvent")
    public ResponseEntity<BatchPublishResult> publishEvents(@Valid AuditEventBatch batch) {
        OffsetDateTime receivedAt = OffsetDateTime.now();
        String tenantId = AuthContextHolder.get().map(c -> c.tenantId()).orElse(null);
        tenantGate.checkProvisioned(tenantId);

        List<Map<String, Object>> entries = batch.getEvents();
        BatchPublishEntryResult[] results = new BatchPublishEntryResult[entries.size()];
        List<AuditEvent> toPublish = new ArrayList<>();
        List<Integer> toPublishIndex = new ArrayList<>();
        boolean retryable = false;

        for (int i = 0; i < entries.size(); i++) {
            AuditEvent event;
            try {
                event = objectMapper.convertValue(entries.get(i), AuditEvent.class);
            } catch (IllegalArgumentException e) {
                results[i] = rejected(i, null, ErrorCode.VALIDATION_ERROR, "Malformed event: " + rootMessage(e));
                continue;
            }
            if (event == null) {
                results[i] = rejected(i, null, ErrorCode.VALIDATION_ERROR, "Event must be a JSON object");
                continue;
            }
            Set<ConstraintViolation<AuditEvent>> violations = validator.validate(event);
            if (!violations.isEmpty()) {
                results[i] = rejected(i, event.getEventId(), ErrorCode.VALIDATION_ERROR, violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted()
                        .collect(Collectors.joining("; ")));
                continue;
            }
            prepare(event, receivedAt);
            if (!tenantGate.tryAcquireQuota(event.getTenantId())) {
                results[i] = rejected(i, event.getEventId(), ErrorCode.TENANT_RATE_LIMITED,
                        "Tenant exceeded its ingest rate limit");
                retryable = true;
                continue;
            }
            toPublish.add(event);
            toPublishIndex.add(i);
        }

        if (!toPublish.isEmpty()) {
            List<String> failures = publisherService.publishMessages(toPublish);
            for (int k = 0; k < toPublish.size(); k++) {
                int i = toPublishIndex.get(k);
                UUID eventId = toPublish.get(k).getEventId();
                if (failures.get(k) == null) {
                    results[i] = new BatchPublishEntryResult().index(i).eventId(eventId)
                            .status(BatchPublishEntryResult.StatusEnum.ACCEPTED);
                } else {
                    results[i] = rejected(i, eventId, ErrorCode.PUBLISH_FAILED,
                            "The message broker did not store the event; retry with the same eventId");
                    retryable = true;
                }
            }
        }

        BatchPublishResult body = new BatchPublishResult().results(List.of(results));
        long accepted = body.getResults().stream()
                .filter(r -> r.getStatus() == BatchPublishEntryResult.StatusEnum.ACCEPTED).count();
        body.acceptedCount((int) accepted).rejectedCount(entries.size() - (int) accepted);
        logger.info("Audit event batch processed: {} accepted, {} rejected", accepted, entries.size() - accepted);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (retryable) {
            response.header("Retry-After", String.valueOf(TenantGate.retryAfterSeconds()));
        }
        return response.body(body);
    }

    /** Server-side fields: event id, correlation id, trusted tenant and receipt time. */
    private static void prepare(AuditEvent event, OffsetDateTime receivedAt) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        // Persist the request correlation id into the event so the audit record is self-contained.
        // Treat a null OR blank client value as "omitted" and fall back to the request correlation id.
        if (event.getCorrelationId() == null || event.getCorrelationId().isBlank()) {
            String correlationId = MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY);
            if (correlationId != null) {
                event.setCorrelationId(correlationId);
            }
        }
        // The gateway-derived tenant is authoritative: a client-supplied tenantId in the
        // payload never overrides the trusted X-Auth-Tenant context.
        AuthContextHolder.get().ifPresent(context -> {
            if (context.tenantId() != null) {
                event.setTenantId(context.tenantId());
            }
        });
        event.setTimestamp(receivedAt);
    }

    private static BatchPublishEntryResult rejected(int index, UUID eventId, ErrorCode code, String message) {
        return new BatchPublishEntryResult().index(index).eventId(eventId)
                .status(BatchPublishEntryResult.StatusEnum.REJECTED)
                .error(new ErrorResponse().code(code).message(message).timestamp(OffsetDateTime.now()));
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        // Jackson messages can be long and echo input; keep the first line only.
        return msg == null ? t.getClass().getSimpleName() : msg.lines().findFirst().orElse(msg);
    }
}
