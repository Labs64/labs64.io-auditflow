package io.labs64.audit.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.service.RedactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class AuditPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(AuditPublisherService.class);

    public static final String AUDIT_OUT_0 = "audit-out-0";

    private final StreamBridge streamBridge;
    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;

    public AuditPublisherService(StreamBridge streamBridge, ObjectMapper objectMapper,
                                 RedactionService redactionService) {
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
        this.redactionService = redactionService;
    }

    public boolean publishMessage(io.labs64.auditflow.model.AuditEvent event) {
        String json;
        try {
            // PII redaction happens here, before publish, so raw PII never enters the broker.
            JsonNode tree = objectMapper.valueToTree(event);
            redactionService.redact(tree);
            json = objectMapper.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize audit event to JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Failed to serialize audit event: " + e.getMessage(), e);
        }

        logger.debug("Publish audit event to binding '{}'", AUDIT_OUT_0);
        return streamBridge.send(AUDIT_OUT_0, MessageBuilder.withPayload(json).build());
    }

}
