package io.labs64.audit.controller.audit.v1;

import io.labs64.audit.v1.api.AuditEventApi;
import io.labs64.audit.v1.model.AuditEvent;
import io.labs64.audit.publisher.AuditPublisherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import java.time.OffsetDateTime;

/**
 * REST Controller for handling audit event publication.
 * Implements the OpenAPI-generated interface for type safety.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AuditEvent", description = "Audit Event Management API")
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
    @Operation(
        summary = "Publish an audit event",
        description = "Publishes an audit event to the message broker for asynchronous processing through configured pipelines"
    )
    public ResponseEntity<String> publishEvent(@Valid AuditEvent event) {
        logger.debug("Received request to publish audit event: eventType={}, sourceSystem={}",
                event.getEventType() != null ? event.getEventType() : "unknown",
                event.getSourceSystem() != null ? event.getSourceSystem() : "unknown");

        try {
            OffsetDateTime receivedAt = OffsetDateTime.now();
            event.setTimestamp(receivedAt);
            boolean result = publisherService.publishMessage(event);

            if (result) {
                logger.info("Audit event published successfully");
                return ResponseEntity.ok("Audit event published successfully");
            } else {
                logger.error("Failed to publish audit event - publisher returned false");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("Failed to publish audit event");
            }
        } catch (Exception e) {
            logger.error("Exception occurred while publishing audit event: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to publish audit event: " + e.getMessage());
        }
    }

}
