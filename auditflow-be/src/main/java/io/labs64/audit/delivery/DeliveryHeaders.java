package io.labs64.audit.delivery;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.labs64.audit.broker.BrokerMessage;

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

    /** The headers a re-published copy carries over; anything else (broker bookkeeping) is dropped. */
    private static final String[] CARRIED = { TENANT, PIPELINE, EVENT_ID, ATTEMPTS, FIRST_ENQUEUED_AT, THROTTLED,
            LAST_ERROR, DLQ_REASON, DLQ_AT };

    private static final int MAX_ERROR_LENGTH = 500;

    private DeliveryHeaders() {
    }

    public static BrokerMessage newDelivery(String eventJson, String tenantId, String pipeline, String eventId, long now) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(TENANT, tenantId);
        headers.put(PIPELINE, pipeline);
        if (eventId != null) {
            headers.put(EVENT_ID, eventId);
        }
        headers.put(ATTEMPTS, 0);
        headers.put(FIRST_ENQUEUED_AT, now);
        headers.put(THROTTLED, 0);
        return new BrokerMessage(eventJson.getBytes(StandardCharsets.UTF_8), headers,
                eventId == null ? null : eventId + "/" + pipeline);
    }

    /** A copy for re-publishing (retry, defer, dead-letter) with selected headers overridden. */
    public static BrokerMessage copy(BrokerMessage original, Map<String, Object> overrides) {
        Map<String, Object> headers = new LinkedHashMap<>();
        for (String key : CARRIED) {
            Object value = original.header(key);
            if (value != null) {
                headers.put(key, value);
            }
        }
        overrides.forEach((k, v) -> {
            if (v == null) {
                headers.remove(k);
            } else {
                headers.put(k, v);
            }
        });
        return new BrokerMessage(original.body(), headers, original.messageId());
    }

    public static String string(BrokerMessage message, String header) {
        Object value = message.header(header);
        return value == null ? null : value.toString();
    }

    public static int intHeader(BrokerMessage message, String header) {
        Object value = message.header(header);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static long longHeader(BrokerMessage message, String header, long fallback) {
        Object value = message.header(header);
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
