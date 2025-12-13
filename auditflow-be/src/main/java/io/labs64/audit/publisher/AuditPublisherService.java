package io.labs64.audit.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

@Service
public class AuditPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(AuditPublisherService.class);

    public static final String AUDIT_OUT_0 = "audit-out-0";

    private final StreamBridge streamBridge;
    private ObjectMapper objectMapper;

    @Autowired
    public AuditPublisherService(StreamBridge streamBridge, ObjectMapper objectMapper) {
        this.streamBridge = streamBridge;
        this.objectMapper = objectMapper;
    }

    public boolean publishMessage(io.labs64.audit.v1.model.AuditEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            logger.error("Failed to convert Object to JSON! Error: {}", e.getMessage());
            return false;
        }

        logger.debug("Publish audit event to binding '{}'", AUDIT_OUT_0);
        return streamBridge.send(AUDIT_OUT_0, MessageBuilder.withPayload(json).build());
    }

}
