package io.labs64.audit.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.delivery.DeliveryQueue;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.telemetry.BusinessTelemetry;
import io.labs64.audit.tenant.PipelineSet;
import io.labs64.audit.tenant.TenantIds;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;

/**
 * First stage (router): consumes the ingest queue and turns one audit event into one delivery message
 * per matching pipeline on {@code labs64-audit-delivery}. It never calls a transformer or a sink, so a
 * slow or failing destination cannot hold up routing or the other pipelines.
 *
 * <p>The ingest message is acked only after every delivery message is confirmed by the broker. If that
 * fails, the event is released and redelivered by the binder (and dead-lettered to the legacy ingest
 * DLQ after its retries); pipelines whose delivery already succeeded are skipped on the next pass.</p>
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    /** Quarantine reason for events whose tenant is unprovisioned or disabled (no routable set). */
    public static final String TENANT_UNRESOLVED = "TENANT_UNRESOLVED";

    private final AuditFlowConfiguration auditFlowConfiguration;
    private final ConditionEvaluator conditionEvaluator;
    private final IdempotencyService idempotencyService;
    private final QuarantineService quarantineService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Counter deduplicatedCounter;
    private final BusinessTelemetry businessTelemetry;
    private final ConsumerHealthIndicator consumerHealthIndicator;
    private final TenantPipelineRegistry tenantRegistry;
    private final DeliveryQueue deliveryQueue;

    public AuditService(
            AuditFlowConfiguration auditFlowConfiguration,
            ConditionEvaluator conditionEvaluator,
            IdempotencyService idempotencyService,
            QuarantineService quarantineService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ConsumerHealthIndicator consumerHealthIndicator,
            BusinessTelemetry businessTelemetry,
            TenantPipelineRegistry tenantRegistry,
            DeliveryQueue deliveryQueue) {
        this.auditFlowConfiguration = auditFlowConfiguration;
        this.conditionEvaluator = conditionEvaluator;
        this.idempotencyService = idempotencyService;
        this.quarantineService = quarantineService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.deduplicatedCounter = meterRegistry.counter("auditflow.events.deduplicated");
        this.consumerHealthIndicator = consumerHealthIndicator;
        this.businessTelemetry = businessTelemetry;
        this.tenantRegistry = tenantRegistry;
        this.deliveryQueue = deliveryQueue;
    }

    @PostConstruct
    public void validateConfiguration() {
        // Legacy fail-fast (settled): global pipelines no longer participate in routing, and an
        // upgrade must never silently stop delivering events.
        if (auditFlowConfiguration.getPipelines() != null && !auditFlowConfiguration.getPipelines().isEmpty()) {
            throw new IllegalStateException("""
                    Global 'auditflow.pipelines' no longer participates in routing (tenant model). \
                    Move these pipelines into a tenant config — typically tenants/_platform.yaml (local-dir mode) \
                    or the auditflow-tenant-platform ConfigMap (gitops-configmap mode) — and remove \
                    'auditflow.pipelines' from the application configuration.""");
        }
    }

    /**
     * Route one ingest message.
     *
     * @param message the audit event as JSON (already redacted at ingest)
     */
    public void processAuditEvent(String message) {
        if (!StringUtils.hasText(message)) {
            logger.warn("Received empty or null audit event message, skipping processing.");
            return;
        }
        if (consumerHealthIndicator.isShutdownRequested()) {
            logger.warn("Shutdown requested, rejecting new event for processing");
            throw new RetryableDeliveryException("Shutdown in progress; event will be redelivered");
        }
        consumerHealthIndicator.recordEventStarted();

        // Parse once, up front. Fail closed: an unparseable event is quarantined, never matched.
        JsonNode eventJson;
        try {
            eventJson = objectMapper.readTree(message);
        } catch (Exception e) {
            consumerHealthIndicator.recordEventFailed();
            quarantineService.quarantine(message, "Unparseable JSON: " + e.getMessage());
            return;
        }

        // Idempotency: claim by eventId. A duplicate (or an in-flight redelivery) is dropped.
        String eventId = eventJson.path("eventId").asText(null);
        if (!StringUtils.hasText(eventId)) {
            logger.warn("Audit event has no eventId; processing without dedup guarantee.");
        } else if (!idempotencyService.claim(eventId)) {
            logger.debug("Duplicate audit event eventId='{}', dropping.", eventId);
            consumerHealthIndicator.recordEventFailed();
            deduplicatedCounter.increment();
            return;
        }

        if (StringUtils.hasText(eventId)) {
            MDC.put("eventId", eventId);
        }
        businessTelemetry.auditEventReceived(eventId, eventJson.path("eventType").asText(null));
        try {
            route(message, eventJson, eventId);
            if (StringUtils.hasText(eventId)) {
                idempotencyService.markProcessed(eventId);
            }
            consumerHealthIndicator.recordEventProcessed();
        } catch (RuntimeException e) {
            consumerHealthIndicator.recordEventFailed();
            // Release the claim so the binder's redelivery can route the event again.
            if (StringUtils.hasText(eventId)) {
                idempotencyService.release(eventId);
            }
            logger.warn("Routing failed (event will be redelivered): {}", e.getMessage());
            throw e;
        } finally {
            MDC.remove("eventId");
        }
    }

    private void route(String message, JsonNode eventJson, String eventId) {
        // Authoritative tenantId (stamped at ingest from X-Auth-Tenant; trusted here).
        String tenantId = TenantIds.resolve(eventJson.path("tenantId").asText(null));

        var pipelineSet = tenantRegistry.pipelinesFor(tenantId);
        if (pipelineSet.isEmpty()) {
            // ABSENT or Disabled -> quarantine, NEVER another tenant's sink. Stop.
            logger.warn("No routable pipeline set for tenant '{}' (state={}); quarantining eventId={}",
                    tenantId, tenantRegistry.stateFor(tenantId), eventId);
            tenantEvent(tenantId, "quarantined");
            quarantineService.quarantine(message, TENANT_UNRESOLVED + ": tenant '" + tenantId + "' state="
                    + tenantRegistry.stateFor(tenantId));
            return;
        }

        List<AuditFlowConfiguration.PipelineProperties> pipelines = pipelineSet.map(PipelineSet::pipelines).get();
        List<String> matching = new ArrayList<>();
        for (AuditFlowConfiguration.PipelineProperties pipeline : pipelines) {
            String name = pipeline.getName();
            if (!pipeline.isEnabled() || !conditionEvaluator.evaluate(eventJson, pipeline.getCondition())) {
                recordOutcome(name, "SKIPPED");
                continue;
            }
            // A redelivered ingest message must not re-enqueue a pipeline that already delivered.
            if (StringUtils.hasText(eventId) && idempotencyService.isPipelineDone(eventId, name)) {
                continue;
            }
            matching.add(name);
        }
        if (!matching.isEmpty()) {
            deliveryQueue.enqueue(message, tenantId, matching, eventId);
            matching.forEach(name -> recordOutcome(name, "ENQUEUED"));
        }
        tenantEvent(tenantId, "routed");
    }

    /** Tenant-dimensioned lifecycle signal: span event + the `auditflow.tenant.events` counter. */
    private void tenantEvent(String tenantId, String outcome) {
        String provider = String.valueOf(tenantRegistry.providerFor(tenantId));
        businessTelemetry.tenantEvent(tenantId, provider, outcome);
        meterRegistry.counter("auditflow.tenant.events",
                "tenant", tenantId, "provider", provider, "outcome", outcome).increment();
    }

    private void recordOutcome(String pipelineName, String outcome) {
        businessTelemetry.pipelineCompleted(pipelineName, outcome);
        meterRegistry.counter("auditflow.pipeline.outcomes", "pipeline", pipelineName, "outcome", outcome).increment();
    }
}
