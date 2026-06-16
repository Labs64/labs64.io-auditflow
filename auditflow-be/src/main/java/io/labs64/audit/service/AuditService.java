package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
    private final Executor pipelineExecutor;

    public AuditService(
            AuditFlowConfiguration auditFlowConfiguration,
            TransformationService transformationService,
            SinkService sinkService,
            ConditionEvaluator conditionEvaluator,
            IdempotencyService idempotencyService,
            QuarantineService quarantineService,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Qualifier("pipelineExecutor") Executor pipelineExecutor) {
        this.auditFlowConfiguration = auditFlowConfiguration;
        this.transformationService = transformationService;
        this.sinkService = sinkService;
        this.conditionEvaluator = conditionEvaluator;
        this.idempotencyService = idempotencyService;
        this.quarantineService = quarantineService;
        this.objectMapper = objectMapper;
        this.deduplicatedCounter = meterRegistry.counter("auditflow.events.deduplicated");
        this.pipelineExecutor = pipelineExecutor;
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
        if (auditFlowConfiguration.getPipelines() == null || auditFlowConfiguration.getPipelines().isEmpty()) {
            logger.warn("No audit pipelines configured, skipping event processing.");
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (AuditFlowConfiguration.PipelineProperties pipeline : auditFlowConfiguration.getPipelines()) {
            if (!pipeline.isEnabled()) {
                logger.debug("Pipeline '{}' is disabled, skipping processing.", pipeline.getName());
                continue;
            }
            if (!conditionEvaluator.evaluate(eventJson, pipeline.getCondition())) {
                logger.debug("Pipeline '{}' condition not matched, skipping processing.", pipeline.getName());
                continue;
            }
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    processPipeline(pipeline, eventJson);
                } catch (Exception e) {
                    // One failing pipeline does not stop the others.
                    logger.error("Error processing audit pipeline '{}': {}", pipeline.getName(), e.getMessage(), e);
                }
            }, pipelineExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Process a single pipeline: transform and then send to sink.
     */
    private void processPipeline(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) throws Exception {
        logger.debug("Start event processing using pipeline '{}'", pipeline.getName());

        // 1. Transform event using the configured transformer
        JsonNode transformed = transformMessage(pipeline, eventJson);

        // 2. Send to sink service
        String sinkResult = sinkService.sendToSink(
                transformed,
                pipeline.getSink().getName(),
                pipeline.getSink().getProperties());

        logger.debug("Successfully processed event through pipeline '{}'. Sink result: {}",
                pipeline.getName(), sinkResult);
    }

    /**
     * Transform the event using the configured transformer.
     */
    private JsonNode transformMessage(AuditFlowConfiguration.PipelineProperties pipeline, JsonNode eventJson) {
        if (pipeline.getTransformer() == null || !StringUtils.hasText(pipeline.getTransformer().getName())) {
            logger.debug("No transformer configured for pipeline '{}', using original message", pipeline.getName());
            return eventJson;
        }
        String result = transformationService.transform(eventJson, pipeline.getTransformer().getName());
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            throw new RuntimeException("Transformer '" + pipeline.getTransformer().getName()
                    + "' returned invalid JSON: " + e.getMessage(), e);
        }
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
