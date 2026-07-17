package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.config.PipelineRateLimiterRegistry;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.tenant.PipelineSet;
import io.labs64.audit.tenant.SecretRefResolver;
import io.labs64.audit.tenant.TenantConcurrencyLimiter;
import io.labs64.audit.tenant.TenantIds;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import io.micrometer.core.instrument.Counter;
import io.labs64.audit.telemetry.BusinessTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for processing audit events through configured pipelines.
 * Each pipeline consists of a transformer and a sink (Python-based).
 */
@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final AuditFlowConfiguration auditFlowConfiguration;
    private final TransformationService transformationService;
    private final SinkService sinkService;
    private final ConditionEvaluator conditionEvaluator;
    private final IdempotencyService idempotencyService;
    private final QuarantineService quarantineService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Counter deduplicatedCounter;
    private final BusinessTelemetry businessTelemetry;
    private final ConsumerHealthIndicator consumerHealthIndicator;
    private final PipelineRateLimiterRegistry pipelineRateLimiterRegistry;
    private final TenantPipelineRegistry tenantRegistry;
    private final TenantConcurrencyLimiter tenantConcurrencyLimiter;
    private final SecretRefResolver secretRefResolver;

    /** Quarantine reason for events whose tenant is unprovisioned or disabled (no routable set). */
    public static final String TENANT_UNRESOLVED = "TENANT_UNRESOLVED";

    /**
     * Max number of a single event's matching pipelines processed concurrently.
     * Mirrors the bound of the retired {@code pipelineExecutor} thread pool; the work is
     * I/O-bound, so concurrency is now expressed via Reactor's {@code flatMap} rather than threads.
     */
    private static final int PIPELINE_CONCURRENCY = 8;

    /** Terminal disposition of a single pipeline for one event. */
    private enum PipelineOutcome {
        /** Delivered successfully (or skipped because a prior delivery already succeeded). */
        SUCCESS,
        /** Disabled or condition did not match — nothing to do. */
        SKIPPED,
        /** Permanent failure (4xx / malformed transform output); retrying cannot help. */
        POISON,
        /** Transient failure that survived HTTP retries / open circuit; event must be redelivered. */
        RETRYABLE_FAILURE
    }

    public AuditService(
            AuditFlowConfiguration auditFlowConfiguration,
            TransformationService transformationService,
            SinkService sinkService,
            ConditionEvaluator conditionEvaluator,
            IdempotencyService idempotencyService,
            QuarantineService quarantineService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            ConsumerHealthIndicator consumerHealthIndicator,
            PipelineRateLimiterRegistry pipelineRateLimiterRegistry,
            BusinessTelemetry businessTelemetry,
            TenantPipelineRegistry tenantRegistry,
            TenantConcurrencyLimiter tenantConcurrencyLimiter,
            SecretRefResolver secretRefResolver) {
        this.auditFlowConfiguration = auditFlowConfiguration;
        this.transformationService = transformationService;
        this.sinkService = sinkService;
        this.conditionEvaluator = conditionEvaluator;
        this.idempotencyService = idempotencyService;
        this.quarantineService = quarantineService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.deduplicatedCounter = meterRegistry.counter("auditflow.events.deduplicated");
        this.consumerHealthIndicator = consumerHealthIndicator;
        this.pipelineRateLimiterRegistry = pipelineRateLimiterRegistry;
        this.businessTelemetry = businessTelemetry;
        this.tenantRegistry = tenantRegistry;
        this.tenantConcurrencyLimiter = tenantConcurrencyLimiter;
        this.secretRefResolver = secretRefResolver;
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
     * Process an audit event through all enabled pipelines.
     *
     * @param message The audit event message as JSON string
     */
    public void processAuditEvent(String message) {
        if (!StringUtils.hasText(message)) {
            logger.warn("Received empty or null audit event message, skipping processing.");
            return;
        }

        // Reject events during shutdown to allow graceful drain
        if (consumerHealthIndicator.isShutdownRequested()) {
            logger.warn("Shutdown requested, rejecting new event for processing");
            throw new RetryableDeliveryException("Shutdown in progress; event will be redelivered");
        }

        // Track in-flight events for graceful shutdown
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

        // Idempotency: claim by eventId. A duplicate (or in-flight redelivery) is dropped.
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
            dispatchToPipelines(eventJson, eventId);
            if (StringUtils.hasText(eventId)) {
                idempotencyService.markProcessed(eventId);
            }
            consumerHealthIndicator.recordEventProcessed();
        } catch (RuntimeException e) {
            consumerHealthIndicator.recordEventFailed();
            // Event-level failure: release the claim so an at-least-once redelivery can retry,
            // then rethrow so the broker retries and ultimately routes to the DLQ. Per-pipeline
            // dedup keys ensure already-succeeded pipelines are NOT re-delivered on redelivery.
            if (StringUtils.hasText(eventId)) {
                idempotencyService.release(eventId);
            }
            throw e;
        } finally {
            MDC.remove("eventId");
        }
    }

    private void dispatchToPipelines(JsonNode eventJson, String eventId) {
        // Authoritative tenantId (stamped at ingest from X-Auth-Tenant; trusted here).
        String tenantId = TenantIds.resolve(eventJson.path("tenantId").asText(null));

        var pipelineSet = tenantRegistry.pipelinesFor(tenantId);
        if (pipelineSet.isEmpty()) {
            // ABSENT or Disabled -> quarantine, NEVER another tenant's sink. Stop.
            logger.warn("No routable pipeline set for tenant '{}' (state={}); quarantining eventId={}",
                    tenantId, tenantRegistry.stateFor(tenantId), eventId);
            tenantEvent(tenantId, "quarantined");
            try {
                quarantineService.quarantine(objectMapper.writeValueAsString(eventJson),
                        TENANT_UNRESOLVED + ": tenant '" + tenantId + "' state="
                                + tenantRegistry.stateFor(tenantId));
            } catch (Exception e) {
                quarantineService.quarantine(eventJson.toString(), TENANT_UNRESOLVED);
            }
            return;
        }

        List<AuditFlowConfiguration.PipelineProperties> pipelines = pipelineSet.map(PipelineSet::pipelines).get();
        if (pipelines.isEmpty()) {
            logger.warn("Tenant '{}' has an empty pipeline set; nothing to route for eventId={}", tenantId, eventId);
            return;
        }

        // Layer-2 fairness: cap this tenant's in-flight events so one flooding tenant cannot
        // monopolize the shared consumer even within its ingest rate budget.
        if (!tenantConcurrencyLimiter.tryAcquire(tenantId)) {
            throw new RetryableDeliveryException(
                    "Tenant '" + tenantId + "' concurrency cap reached; redelivering eventId=" + eventId);
        }
        try {
            // Fan a single event out across its matching pipelines concurrently via Reactor.
            // The Spring Cloud Stream Consumer contract is blocking, so we subscribe once here;
            // the transform/sink legs in between are fully non-blocking.
            List<PipelineOutcome> outcomes = Flux.fromIterable(pipelines)
                    .flatMap(pipeline -> runPipeline(pipeline, eventJson, eventId, tenantId), PIPELINE_CONCURRENCY)
                    .collectList()
                    .block();

            // If any pipeline ended in a retryable failure, fail the whole event so the broker
            // redelivers and ultimately dead-letters it. Poison failures are logged/counted only —
            // retrying or dead-lettering them would loop forever without ever succeeding.
            long retryable = outcomes == null ? 0
                    : outcomes.stream().filter(o -> o == PipelineOutcome.RETRYABLE_FAILURE).count();
            if (retryable > 0) {
                throw new RetryableDeliveryException(retryable + " of " + outcomes.size()
                        + " pipeline(s) failed with a retryable error; redelivering event"
                        + (StringUtils.hasText(eventId) ? " eventId=" + eventId : ""));
            }
            tenantEvent(tenantId, "routed");
        } finally {
            tenantConcurrencyLimiter.release(tenantId);
        }
    }

    /**
     * Run a single pipeline reactively, returning its terminal {@link PipelineOutcome}. Disabled
     * or non-matching pipelines are SKIPPED; a pipeline that already succeeded on a prior delivery
     * (per-pipeline dedup key) is SUCCESS without re-delivering. Failures are classified into
     * POISON (never retryable) or RETRYABLE_FAILURE and never escape this method.
     */
    private Mono<PipelineOutcome> runPipeline(
            AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson, String eventId, String tenantId) {
        String name = pipeline.getName();

        if (!pipeline.isEnabled()) {
            logger.debug("Pipeline '{}' disabled, skipping.", name);
            return Mono.just(recordOutcome(name, PipelineOutcome.SKIPPED));
        }
        if (!conditionEvaluator.evaluate(eventJson, pipeline.getCondition())) {
            logger.debug("Pipeline '{}' condition not matched, skipping.", name);
            return Mono.just(recordOutcome(name, PipelineOutcome.SKIPPED));
        }
        // Per-pipeline dedup: on a redelivery, skip pipelines that already delivered successfully
        // so a single failing pipeline does not cause duplicate deliveries to the healthy ones.
        if (StringUtils.hasText(eventId) && idempotencyService.isPipelineDone(eventId, name)) {
            logger.debug("Pipeline '{}' already delivered for eventId='{}' on a prior attempt, skipping.", name, eventId);
            return Mono.just(recordOutcome(name, PipelineOutcome.SUCCESS));
        }

        // Rate limiting: reject events if pipeline rate limit is exceeded
        if (!pipelineRateLimiterRegistry.tryAcquirePermission(name)) {
            logger.warn("Pipeline '{}' rate limit exceeded, rejecting event", name);
            meterRegistry.counter("auditflow.pipeline.rate.limited", "pipeline", name).increment();
            // Treat as retryable so the broker redelivers later when the rate limit resets
            return Mono.error(new RetryableDeliveryException(
                    "Pipeline '" + name + "' rate limit exceeded; will retry on redelivery"));
        }

        logger.debug("Processing pipeline '{}'", name);
        long startNanos = System.nanoTime();
        // Mono.defer ensures a synchronous throw during chain assembly (e.g. transformer/sink
        // argument validation) surfaces as an onError signal for THIS pipeline only.
        return Mono.defer(() -> processPipeline(pipeline, eventJson, tenantId))
                .doOnNext(result -> logger.debug("Pipeline '{}' completed successfully.", name))
                .then(Mono.fromSupplier(() -> {
                    if (StringUtils.hasText(eventId)) {
                        idempotencyService.markPipelineDone(eventId, name);
                    }
                    return PipelineOutcome.SUCCESS;
                }))
                .onErrorResume(e -> Mono.just(classifyFailure(name, e)))
                .map(outcome -> {
                    meterRegistry.timer("auditflow.pipeline.duration", "pipeline", name, "outcome", outcome.name())
                            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                    if (outcome == PipelineOutcome.SUCCESS) {
                        tenantEvent(tenantId, "delivered");
                    }
                    return recordOutcome(name, outcome);
                });
    }

    /** Map a pipeline failure to an outcome, logging at the appropriate level. */
    private PipelineOutcome classifyFailure(String pipelineName, Throwable e) {
        if (e instanceof PoisonDeliveryException) {
            logger.error("Pipeline '{}' produced a poison event (not retryable): {}", pipelineName, e.getMessage());
            return PipelineOutcome.POISON;
        }
        // RetryableDeliveryException and anything unclassified — fail safe toward redelivery.
        logger.error("Pipeline '{}' failed with a retryable error: {}", pipelineName, e.getMessage(), e);
        return PipelineOutcome.RETRYABLE_FAILURE;
    }

    /** Tenant-dimensioned lifecycle signal: span event + the `auditflow.tenant.events` counter. */
    private void tenantEvent(String tenantId, String outcome) {
        String provider = String.valueOf(tenantRegistry.providerFor(tenantId));
        businessTelemetry.tenantEvent(tenantId, provider, outcome);
        meterRegistry.counter("auditflow.tenant.events",
                "tenant", tenantId, "provider", provider, "outcome", outcome).increment();
    }

    private PipelineOutcome recordOutcome(String pipelineName, PipelineOutcome outcome) {
        businessTelemetry.pipelineCompleted(pipelineName, outcome.name());
        meterRegistry.counter("auditflow.pipeline.outcomes", "pipeline", pipelineName, "outcome", outcome.name())
                .increment();
        return outcome;
    }

    /**
     * Process a single pipeline: apply the transformer stage(s) in order, then deliver to the
     * sink (falling back to the configured fallback sink on a retryable primary-sink failure).
     */
    private Mono<String> processPipeline(
            AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson, String tenantId) {
        return applyTransformers(pipeline, eventJson)
                .flatMap(transformed -> sendWithFallback(pipeline.getSink(), pipeline.getName(), tenantId, transformed));
    }

    /**
     * Apply the pipeline's transformer stages in order (multi-stage chaining). A pipeline with no
     * transformer passes the original message through unchanged.
     */
    private Mono<JsonNode> applyTransformers(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) {
        Mono<JsonNode> chain = Mono.just(eventJson);
        for (AuditFlowConfiguration.TransformerProperties stage : pipeline.getEffectiveTransformers()) {
            if (stage == null || !StringUtils.hasText(stage.getName())) {
                continue;
            }
            String transformerName = stage.getName();
            chain = chain.flatMap(current -> transformationService.transform(current, transformerName)
                    .map(result -> parseTransformerOutput(transformerName, result)));
        }
        return chain;
    }

    private JsonNode parseTransformerOutput(String transformerName, String result) {
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            // Malformed transformer output will never parse on retry — treat as poison.
            throw new PoisonDeliveryException("Transformer '" + transformerName
                    + "' returned invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Deliver to the primary sink; on a <em>retryable</em> failure, attempt the configured
     * fallback sink before giving up. A poison failure is not retried on the fallback.
     */
    private Mono<String> sendWithFallback(AuditFlowConfiguration.SinkProperties sink,
                                          String pipelineName, String tenantId, JsonNode event) {
        // ${secretRef:...} placeholders resolve from THIS tenant's secret store only — resolution
        // failure is retryable (→ redelivery → DLQ), never a blank or another tenant's credential.
        Mono<String> primary = Mono
                .fromSupplier(() -> secretRefResolver.resolve(tenantId, sink.getProperties()))
                .flatMap(props -> sinkService.sendToSink(event, sink.getName(), props));

        AuditFlowConfiguration.SinkProperties fallback = sink.getFallback();
        if (fallback == null || !StringUtils.hasText(fallback.getName())) {
            return primary;
        }
        return primary.onErrorResume(RetryableDeliveryException.class, e -> {
            logger.warn("Pipeline '{}' primary sink '{}' failed ({}); attempting fallback sink '{}'",
                    pipelineName, sink.getName(), e.getMessage(), fallback.getName());
            return Mono.fromSupplier(() -> secretRefResolver.resolve(tenantId, fallback.getProperties()))
                    .flatMap(props -> sinkService.sendToSink(event, fallback.getName(), props));
        });
    }

}
