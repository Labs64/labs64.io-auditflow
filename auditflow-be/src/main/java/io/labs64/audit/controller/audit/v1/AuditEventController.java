package io.labs64.audit.controller.audit.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.v1.api.AuditEventApi;
import io.labs64.audit.v1.model.AuditEvent;
import io.labs64.audit.publisher.AuditPublisherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/api/v1")
public class AuditEventController implements AuditEventApi {

    private final AuditPublisherService publisherService;

    public AuditEventController(AuditPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @Override
    public ResponseEntity<String> publishEvent(AuditEvent event) {
        boolean res = publisherService.publishMessage(event);
        if (res) {
            return ResponseEntity.ok("Message sent successfully");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to send message");
        }
    }

}
