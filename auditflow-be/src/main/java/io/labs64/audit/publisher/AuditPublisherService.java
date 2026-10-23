package io.labs64.audit.publisher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.service.RedactionService;

/**
 * Publishes accepted events to the ingest destination and waits until the broker stored them: the
 * API answers 200 only then (RabbitMQ: publisher confirm on a durable queue; SQS: SendMessage
 * succeeded). A failure is one the client sees as 503 and retries with the same {@code eventId}; an
 * event larger than the broker accepts is rejected up front ({@link #tooLarge}), as 413.
 */
@Service
public class AuditPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(AuditPublisherService.class);

    /** Prefix of a {@link #publishMessages} failure reason that no retry can fix (the event is too large). */
    public static final String TOO_LARGE = "too large: ";

    /**
     * Room kept for the headers a delivery message adds to the event body (tenant, pipeline, attempts,
     * last error of up to 500 characters, DLQ reason...), so every message derived from an accepted
     * event still fits the broker.
     */
    static final int HEADER_HEADROOM_BYTES = 16 * 1024;

    private final MessageBroker broker;
    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;

    public AuditPublisherService(MessageBroker broker, ObjectMapper objectMapper,
                                 RedactionService redactionService) {
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.redactionService = redactionService;
    }

    /**
     * @return {@code null} once the broker stored the event, otherwise the failure reason (starting
     *         with {@link #TOO_LARGE} when the event can never fit the broker)
     */
    public String publishMessage(io.labs64.auditflow.model.AuditEvent event) {
        return publishMessages(List.of(event)).get(0);
    }

    /**
     * Publish several events, waiting for all of them to be stored in one round. Returns one entry per
     * event in input order: {@code null} when stored, otherwise the failure reason.
     */
    public List<String> publishMessages(List<io.labs64.auditflow.model.AuditEvent> events) {
        List<String> results = new ArrayList<>(events.size());
        List<MessageBroker.Outgoing> out = new ArrayList<>(events.size());
        List<Integer> outIndex = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            BrokerMessage message = toMessage(events.get(i));
            String tooLarge = tooLarge(message.body().length);
            results.add(tooLarge);
            if (tooLarge == null) {
                out.add(new MessageBroker.Outgoing(Destination.ingest(), message));
                outIndex.add(i);
            }
        }
        if (!out.isEmpty()) {
            List<String> stored = broker.publishEach(out);
            for (int k = 0; k < stored.size(); k++) {
                results.set(outIndex.get(k), stored.get(k));
            }
        }
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) != null) {
                logger.error("Broker did not store audit event eventId={}: {}", events.get(i).getEventId(), results.get(i));
            }
        }
        return results;
    }

    /** The failure reason for an event body of {@code bytes}, or {@code null} when it fits the broker. */
    String tooLarge(int bytes) {
        long limit = (long) broker.maxMessageBytes() - HEADER_HEADROOM_BYTES;
        if (bytes <= limit) {
            return null;
        }
        return TOO_LARGE + "the event is " + bytes + " bytes after redaction; the " + broker.type()
                + " broker accepts events up to " + limit + " bytes";
    }

    private BrokerMessage toMessage(io.labs64.auditflow.model.AuditEvent event) {
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
        return new BrokerMessage(json.getBytes(StandardCharsets.UTF_8), null,
                event.getEventId() == null ? null : event.getEventId().toString());
    }
}
