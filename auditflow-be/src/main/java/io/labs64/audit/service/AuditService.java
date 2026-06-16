package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration;
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
    private final Counter deduplicatedCounter;

    /**
     * Max number of a single event's matching pipelines processed concurrently.
     * Mirrors the bound of the retired {@code pipelineExecutor} thread pool; the work is
     * I/O-bound, so concurrency is now expressed via Reactor's {@code flatMap} rather than threads.
     */
    private static final int PIPELINE_CONCURRENCY = 8;

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
            dispatchToPipelines(eventJson);
            if (StringUtils.hasText(eventId)) {
                idempotencyService.markProcessed(eventId);
            }
        } catch (RuntimeException e) {
            // Event-level failure (not a single-pipeline failure): release the claim so an
            // at-least-once redelivery can retry, then rethrow so the broker DLQs after retries.
            if (StringUtils.hasText(eventId)) {
                idempotencyService.release(eventId);
            }
            throw e;
        }
    }

    private void dispatchToPipelines(JsonNode eventJson) {
        List<AuditFlowConfiguration.PipelineProperties> pipelines = auditFlowConfiguration.getPipelines();
        if (pipelines == null || pipelines.isEmpty()) {
            logger.warn("No audit pipelines configured, skipping event processing.");
            return;
        }

        // Fan a single event out across its matching pipelines concurrently via Reactor.
        // The Spring Cloud Stream Consumer contract is blocking, so we subscribe once here;
        // the transform/sink legs in between are fully non-blocking.
        Flux.fromIterable(pipelines)
                .flatMap(pipeline -> runPipeline(pipeline, eventJson), PIPELINE_CONCURRENCY)
                .then()
                .block();
    }

    /**
     * Run a single pipeline reactively. Disabled or non-matching pipelines complete empty.
     * A failure in one pipeline is logged and swallowed so the others still run — failure
     * propagation to the broker DLQ is introduced separately (see plan item P0-1).
     */
    private Mono<Void> runPipeline(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) {
        if (!pipeline.isEnabled()) {
            logger.debug("Pipeline '{}' is disabled, skipping processing.", pipeline.getName());
            return Mono.empty();
        }
        if (!conditionEvaluator.evaluate(eventJson, pipeline.getCondition())) {
            logger.debug("Pipeline '{}' condition not matched, skipping processing.", pipeline.getName());
            return Mono.empty();
        }

        logger.debug("Start event processing using pipeline '{}'", pipeline.getName());
        // Mono.defer ensures a synchronous throw during chain assembly (e.g. transformer/sink
        // argument validation) surfaces as an onError signal for THIS pipeline only, so
        // onErrorResume can isolate it rather than failing the whole event's fan-out.
        return Mono.defer(() -> processPipeline(pipeline, eventJson))
                .doOnSuccess(result -> logger.debug("Successfully processed event through pipeline '{}'. Sink result: {}",
                        pipeline.getName(), result))
                .onErrorResume(e -> {
                    // One failing pipeline does not stop the others.
                    logger.error("Error processing audit pipeline '{}': {}", pipeline.getName(), e.getMessage(), e);
                    return Mono.empty();
                })
                .then();
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
                        throw new RuntimeException("Transformer '" + pipeline.getTransformer().getName()
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
