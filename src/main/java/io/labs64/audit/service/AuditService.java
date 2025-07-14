package io.labs64.audit.service;

import io.labs64.audit.config.AuditFlowProperties;
import io.labs64.audit.processors.DestinationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final AuditFlowProperties auditFlowProperties;
    private final TransformationService transformationService;

    @Autowired
    public AuditService(AuditFlowProperties auditFlowProperties, TransformationService transformationService) {
        this.auditFlowProperties = auditFlowProperties;
        this.transformationService = transformationService;
    }

    public void processAuditEvent(String message) {
        for (AuditFlowProperties.PipelineProperties pipeline : auditFlowProperties.getPipelines()) {
            if (pipeline.isEnabled()) {
                try {
                    processPipeline(pipeline, message);
                } catch (Exception e) {
                    logger.error("Error processing audit pipeline '{}'", pipeline.getName(), e);
                }
            } else {
                logger.info("Pipeline '{}' is disabled, skipping processing.", pipeline.getName());
            }
        }
    }

    private void processPipeline(AuditFlowProperties.PipelineProperties pipeline, String message) throws Exception {
        logger.debug("Start event processing using pipeline '{}' for message: {}", pipeline.getName(), message);

        // 1. Transform message using the configured transformer
        String transformedMessage = transformationService.transform(message, pipeline.getTransformer().getName());

        // 2. Instantiate the Processor
        DestinationProcessor processor = (DestinationProcessor) Class
                .forName(pipeline.getProcessor().getClazz())
                .getDeclaredConstructor()
                .newInstance();

        // 3. Initialize the processor with its properties
        processor.initialize(pipeline.getProcessor().getProperties());

        // 4. Process the transformed event
        processor.process(transformedMessage);
    }

}
