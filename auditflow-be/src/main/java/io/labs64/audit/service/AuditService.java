package io.labs64.audit.service;

import io.labs64.audit.config.AuditFlowConfiguration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    @Autowired
    public AuditService(
            AuditFlowConfiguration auditFlowConfiguration,
            TransformationService transformationService,
            SinkService sinkService,
            ConditionEvaluator conditionEvaluator) {
        this.auditFlowConfiguration = auditFlowConfiguration;
        this.transformationService = transformationService;
        this.sinkService = sinkService;
        this.conditionEvaluator = conditionEvaluator;
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

        if (auditFlowConfiguration.getPipelines() == null || auditFlowConfiguration.getPipelines().isEmpty()) {
            logger.warn("No audit pipelines configured, skipping event processing.");
            return;
        }

        logger.debug("Processing audit event through {} pipeline(s)",
                auditFlowConfiguration.getPipelines().stream()
                        .filter(AuditFlowConfiguration.PipelineProperties::isEnabled).count());

        for (AuditFlowConfiguration.PipelineProperties pipeline : auditFlowConfiguration.getPipelines()) {
            if (pipeline.isEnabled()) {
                // Check if pipeline condition matches the event
                if (!conditionEvaluator.evaluate(message, pipeline.getCondition())) {
                    logger.debug("Pipeline '{}' condition not matched, skipping processing.", pipeline.getName());
                    continue;
                }
                try {
                    processPipeline(pipeline, message);
                } catch (Exception e) {
                    logger.error("Error processing audit pipeline '{}': {}", pipeline.getName(), e.getMessage(), e);
                    // Continue processing other pipelines even if one fails
                }
            } else {
                logger.debug("Pipeline '{}' is disabled, skipping processing.", pipeline.getName());
            }
        }
    }

    /**
     * Process a single pipeline: transform and then send to sink.
     */
    private void processPipeline(AuditFlowConfiguration.PipelineProperties pipeline, String message) throws Exception {
        logger.debug("Start event processing using pipeline '{}'", pipeline.getName());

        // 1. Transform message using the configured transformer
        String transformedMessage = transformMessage(pipeline, message);

        // 2. Send to sink service
        String sinkResult = sinkService.sendToSink(
                transformedMessage,
                pipeline.getSink().getName(),
                pipeline.getSink().getProperties()
        );

        logger.debug("Successfully processed event through pipeline '{}'. Sink result: {}",
                pipeline.getName(), sinkResult);
    }

    /**
     * Transform the message using the configured transformer.
     */
    private String transformMessage(AuditFlowConfiguration.PipelineProperties pipeline, String message) {
        if (pipeline.getTransformer() == null || !StringUtils.hasText(pipeline.getTransformer().getName())) {
            logger.debug("No transformer configured for pipeline '{}', using original message", pipeline.getName());
            return message;
        }

        return transformationService.transform(message, pipeline.getTransformer().getName());
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
