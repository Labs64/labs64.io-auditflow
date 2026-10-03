package io.labs64.audit.publisher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.delivery.ConfirmingPublisher;
import io.labs64.audit.service.RedactionService;

/**
 * Publishes accepted events to the ingest exchange and waits for the broker's publisher confirm: the
 * API answers 200 only once the broker has stored the event (durable queue, persistent message). A
 * nack, an unroutable return or a missing confirm is a failure the client sees as 503 and retries
 * with the same {@code eventId}.
 */
@Service
public class AuditPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(AuditPublisherService.class);

    /** Ingest exchange (topic); the router's queue is bound with {@code #}. */
    public static final String INGEST_EXCHANGE = "labs64-audit-topic";
    public static final String INGEST_ROUTING_KEY = "audit.event";

    private final ConfirmingPublisher publisher;
    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;

    public AuditPublisherService(ConfirmingPublisher publisher, ObjectMapper objectMapper,
                                 RedactionService redactionService) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.redactionService = redactionService;
    }

    /** @return true once the broker confirmed the event; false if it did not. */
    public boolean publishMessage(io.labs64.auditflow.model.AuditEvent event) {
        return publishMessages(List.of(event)).get(0) == null;
    }

    /**
     * Publish several events, waiting for all confirms in one round. Returns one entry per event in
     * input order: {@code null} when stored, otherwise the failure reason.
     */
    public List<String> publishMessages(List<io.labs64.auditflow.model.AuditEvent> events) {
        List<ConfirmingPublisher.Outgoing> out = new ArrayList<>(events.size());
        for (io.labs64.auditflow.model.AuditEvent event : events) {
            out.add(new ConfirmingPublisher.Outgoing(INGEST_EXCHANGE, INGEST_ROUTING_KEY, toMessage(event)));
        }
        List<String> results = publisher.publishEach(out);
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) != null) {
                logger.error("Broker did not store audit event eventId={}: {}", events.get(i).getEventId(), results.get(i));
            }
        }
        return results;
    }

    private Message toMessage(io.labs64.auditflow.model.AuditEvent event) {
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
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (event.getEventId() != null) {
            props.setMessageId(event.getEventId().toString());
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }
}
