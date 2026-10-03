package io.labs64.audit.delivery;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Headers of a delivery message: one event bound for ONE pipeline. The body is the event exactly as
 * routed (already redacted at ingest). The tenant comes from the body ({@code tenantId}, stamped from
 * the trusted auth context at ingest); {@link #PIPELINE} only selects among that tenant's pipelines,
 * so a header can never route an event into another tenant's pipeline.
 */
public final class DeliveryHeaders {

    public static final String TENANT = "x-auditflow-tenant";
    public static final String PIPELINE = "x-auditflow-pipeline";
    public static final String EVENT_ID = "x-auditflow-event-id";
    /** Failed delivery attempts so far (throttling does not count). */
    public static final String ATTEMPTS = "x-auditflow-attempts";
    /** Epoch millis the router first enqueued the delivery; the retry age is measured from here. */
    public static final String FIRST_ENQUEUED_AT = "x-auditflow-first-enqueued-at";
    /** How often the delivery was deferred by backpressure (rate limit / concurrency cap). */
    public static final String THROTTLED = "x-auditflow-throttled";
    public static final String LAST_ERROR = "x-auditflow-last-error";
    /** Why the entry is in the DLQ: {@link DeadLetterReason}. */
    public static final String DLQ_REASON = "x-auditflow-dlq-reason";
    public static final String DLQ_AT = "x-auditflow-dlq-at";

    private static final int MAX_ERROR_LENGTH = 500;

    private DeliveryHeaders() {
    }

    public static Message newDelivery(String eventJson, String tenantId, String pipeline, String eventId, long now) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader(TENANT, tenantId);
        props.setHeader(PIPELINE, pipeline);
        if (eventId != null) {
            props.setHeader(EVENT_ID, eventId);
            props.setMessageId(eventId + "/" + pipeline);
        }
        props.setHeader(ATTEMPTS, 0);
        props.setHeader(FIRST_ENQUEUED_AT, now);
        props.setHeader(THROTTLED, 0);
        return new Message(eventJson.getBytes(StandardCharsets.UTF_8), props);
    }

    /** A copy for re-publishing (retry, defer, dead-letter) with selected headers overridden. */
    public static Message copy(Message original, Map<String, Object> overrides) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties props = new MessageProperties();
        props.setContentType(source.getContentType());
        props.setContentEncoding(source.getContentEncoding());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(source.getMessageId());
        for (String key : new String[] { TENANT, PIPELINE, EVENT_ID, ATTEMPTS, FIRST_ENQUEUED_AT, THROTTLED,
                LAST_ERROR, DLQ_REASON, DLQ_AT }) {
            Object value = source.getHeaders().get(key);
            if (value != null) {
                props.setHeader(key, value);
            }
        }
        overrides.forEach((k, v) -> {
            if (v == null) {
                props.getHeaders().remove(k);
            } else {
                props.setHeader(k, v);
            }
        });
        return new Message(original.getBody(), props);
    }

    public static String string(Message message, String header) {
        Object value = message.getMessageProperties().getHeaders().get(header);
        return value == null ? null : value.toString();
    }

    public static int intHeader(Message message, String header) {
        Object value = message.getMessageProperties().getHeaders().get(header);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long longHeader(Message message, String header, long fallback) {
        Object value = message.getMessageProperties().getHeaders().get(header);
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH) + "…";
    }
}
