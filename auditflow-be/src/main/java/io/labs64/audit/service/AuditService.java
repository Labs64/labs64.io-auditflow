package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            MeterRegistry meterRegistry) {
        this.auditFlowConfiguration = auditFlowConfiguration;
        this.transformationService = transformationService;
        this.sinkService = sinkService;
        this.conditionEvaluator = conditionEvaluator;
        this.idempotencyService = idempotencyService;
        this.quarantineService = quarantineService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.deduplicatedCounter = meterRegistry.counter("auditflow.events.deduplicated");
    }

    @PostConstruct
    public void validateConfiguration() {
        if (auditFlowConfiguration.getPipelines() != null) {
            auditFlowConfiguration.getPipelines().forEach(pipeline -> {
                if (pipeline.isEnabled()) {
                    validatePipeline(pipeline);
                }
            });
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

        // Parse once, up front. Fail closed: an unparseable event is quarantined, never matched.
        JsonNode eventJson;
        try {
            eventJson = objectMapper.readTree(message);
        } catch (Exception e) {
            quarantineService.quarantine(message, "Unparseable JSON: " + e.getMessage());
            return;
        }

        // Idempotency: claim by eventId. A duplicate (or in-flight redelivery) is dropped.
        String eventId = eventJson.path("eventId").asText(null);
        if (!StringUtils.hasText(eventId)) {
            logger.warn("Audit event has no eventId; processing without dedup guarantee.");
        } else if (!idempotencyService.claim(eventId)) {
            logger.debug("Duplicate audit event eventId='{}', dropping.", eventId);
            deduplicatedCounter.increment();
            return;
        }

        try {
            dispatchToPipelines(eventJson, eventId);
            if (StringUtils.hasText(eventId)) {
                idempotencyService.markProcessed(eventId);
            }
        } catch (RuntimeException e) {
            // Event-level failure: release the claim so an at-least-once redelivery can retry,
            // then rethrow so the broker retries and ultimately routes to the DLQ. Per-pipeline
            // dedup keys ensure already-succeeded pipelines are NOT re-delivered on redelivery.
            if (StringUtils.hasText(eventId)) {
                idempotencyService.release(eventId);
            }
            throw e;
        }
    }

    private void dispatchToPipelines(JsonNode eventJson, String eventId) {
        List<AuditFlowConfiguration.PipelineProperties> pipelines = auditFlowConfiguration.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) {
            logger.warn("No audit pipelines configured, skipping event processing.");
            return;
        }

        // Fan a single event out across its matching pipelines concurrently via Reactor.
        // The Spring Cloud Stream Consumer contract is blocking, so we subscribe once here;
        // the transform/sink legs in between are fully non-blocking.
        List<PipelineOutcome> outcomes = Flux.fromIterable(pipelines)
                .flatMap(pipeline -> runPipeline(pipeline, eventJson, eventId), PIPELINE_CONCURRENCY)
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
    }

    /**
     * Run a single pipeline reactively, returning its terminal {@link PipelineOutcome}. Disabled
     * or non-matching pipelines are SKIPPED; a pipeline that already succeeded on a prior delivery
     * (per-pipeline dedup key) is SUCCESS without re-delivering. Failures are classified into
     * POISON (never retryable) or RETRYABLE_FAILURE and never escape this method.
     */
    private Mono<PipelineOutcome> runPipeline(
            AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson, String eventId) {
        String name = pipeline.getName();

        if (!pipeline.isEnabled()) {
            logger.debug("Pipeline '{}' is disabled, skipping processing.", name);
            return Mono.just(recordOutcome(name, PipelineOutcome.SKIPPED));
        }
        if (!conditionEvaluator.evaluate(eventJson, pipeline.getCondition())) {
            logger.debug("Pipeline '{}' condition not matched, skipping processing.", name);
            return Mono.just(recordOutcome(name, PipelineOutcome.SKIPPED));
        }
        // Per-pipeline dedup: on a redelivery, skip pipelines that already delivered successfully
        // so a single failing pipeline does not cause duplicate deliveries to the healthy ones.
        if (StringUtils.hasText(eventId) && idempotencyService.isPipelineDone(eventId, name)) {
            logger.debug("Pipeline '{}' already delivered for eventId='{}' on a prior attempt, skipping.", name, eventId);
            return Mono.just(recordOutcome(name, PipelineOutcome.SUCCESS));
        }

        logger.debug("Start event processing using pipeline '{}'", name);
        long startNanos = System.nanoTime();
        // Mono.defer ensures a synchronous throw during chain assembly (e.g. transformer/sink
        // argument validation) surfaces as an onError signal for THIS pipeline only.
        return Mono.defer(() -> processPipeline(pipeline, eventJson))
                .doOnNext(result -> logger.debug("Successfully processed event through pipeline '{}'. Sink result: {}",
                        name, result))
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

    private PipelineOutcome recordOutcome(String pipelineName, PipelineOutcome outcome) {
        meterRegistry.counter("auditflow.pipeline.outcomes", "pipeline", pipelineName, "outcome", outcome.name())
                .increment();
        return outcome;
    }

    /**
     * Process a single pipeline: transform and then send to sink.
     */
    private Mono<String> processPipeline(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) {
        return transformMessage(pipeline, eventJson)
                .flatMap(transformed -> sinkService.sendToSink(
                        transformed,
                        pipeline.getSink().getName(),
                        pipeline.getSink().getProperties()));
    }

    /**
     * Transform the event using the configured transformer. A pipeline with no transformer
     * passes the original message through unchanged.
     */
    private Mono<JsonNode> transformMessage(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) {
        if (pipeline.getTransformer() == null || !StringUtils.hasText(pipeline.getTransformer().getName())) {
            logger.debug("No transformer configured for pipeline '{}', using original message", pipeline.getName());
            return Mono.just(eventJson);
        }
        return transformationService.transform(eventJson, pipeline.getTransformer().getName())
                .map(result -> {
                    try {
                        return objectMapper.readTree(result);
                    } catch (Exception e) {
                        // Malformed transformer output will never parse on retry — treat as poison.
                        throw new PoisonDeliveryException("Transformer '" + pipeline.getTransformer().getName()
                                + "' returned invalid JSON: " + e.getMessage(), e);
                    }
                });
    }

    /**
     * Validate pipeline configuration at startup.
     */
    private void validatePipeline(AuditFlowConfiguration.PipelineProperties pipeline) {
        if (!StringUtils.hasText(pipeline.getName())) {
            throw new IllegalStateException("Pipeline name cannot be empty");
        }

        if (pipeline.getSink() == null) {
            throw new IllegalStateException("Sink must be configured for pipeline: " + pipeline.getName());
        }

        if (!StringUtils.hasText(pipeline.getSink().getName())) {
            throw new IllegalStateException("Sink name must be specified for pipeline: " + pipeline.getName());
        }

        logger.info("Pipeline '{}' validated successfully (sink: {})",
                pipeline.getName(), pipeline.getSink().getName());
    }
}
