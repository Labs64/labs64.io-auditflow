package io.labs64.audit.controller;

import io.labs64.audit.config.CorrelationIdFilter;
import io.labs64.audit.exception.PublishException;
import io.labs64.audit.v1.api.AuditEventApi;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.audit.publisher.AuditPublisherService;
import io.labs64.authcontext.cedar.Authorize;
import io.labs64.authcontext.core.AuthContextHolder;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * REST Controller for handling audit event publication.
 * Implements the OpenAPI-generated interface for type safety.
 *
 * <p>Error handling is delegated to {@link io.labs64.audit.exception.GlobalExceptionHandler}
 * to ensure consistent {@code ErrorResponse} format across all endpoints.</p>
 *
 * <p>The controller is root-mapped: the {@code /<module>/api/v1} prefix is owned and
 * stripped by the Traefik gateway (see labs64.io-helm-charts, chart-libs gateway-routes).</p>
 */
@RestController
public class AuditEventController implements AuditEventApi {

    private static final Logger logger = LoggerFactory.getLogger(AuditEventController.class);

    private final AuditPublisherService publisherService;

    public AuditEventController(AuditPublisherService publisherService) {
        this.publisherService = publisherService;
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
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        String eventId = event.getEventId().toString();

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

        logger.debug("Received request to publish audit event; eventId={}", eventId);

        OffsetDateTime receivedAt = OffsetDateTime.now();
        event.setTimestamp(receivedAt);
        boolean result = publisherService.publishMessage(event);

        if (!result) {
            throw new PublishException("Failed to publish audit event to message broker; eventId=" + eventId);
        }

        logger.info("Audit event published successfully; eventId={}", eventId);
        return ResponseEntity.ok()
                .header("X-Audit-Event-Id", eventId)
                .header("X-Audit-Received-At", receivedAt.toString())
                .body("Audit event published successfully");
    }

}
