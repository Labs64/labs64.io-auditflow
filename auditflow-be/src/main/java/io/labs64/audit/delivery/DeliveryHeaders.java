package io.labs64.audit.delivery;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.service.StepProgress;

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
    /**
     * How many steps of a pipeline with {@code steps} already succeeded for this event: a retry, a defer or a
     * DLQ replay continues at the next one instead of repeating them. Absent = none (and always for one-step pipelines).
     */
    public static final String STEPS_DONE = "x-auditflow-steps-done";
    /** The event's step context so far (JSON object): what the sinks of the finished steps returned, by step name. */
    public static final String CONTEXT = "x-auditflow-context";

    /** The headers a re-published copy carries over; anything else (broker bookkeeping) is dropped. */
    private static final String[] CARRIED = { TENANT, PIPELINE, EVENT_ID, ATTEMPTS, FIRST_ENQUEUED_AT, THROTTLED,
            LAST_ERROR, DLQ_REASON, DLQ_AT, STEPS_DONE, CONTEXT };

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

    /**
     * The step progress a message carries: none for a fresh delivery and for every one-step pipeline.
     *
     * @throws IllegalArgumentException for a header this code did not write (negative count, context not a JSON object)
     */
    public static StepProgress progress(BrokerMessage message, ObjectMapper objectMapper) {
        Object done = message.header(STEPS_DONE);
        String context = string(message, CONTEXT);
        if (done == null && context == null) {
            return StepProgress.START;
        }
        int stepsDone;
        try {
            stepsDone = done instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(done));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Header " + STEPS_DONE + " is not a number");
        }
        if (stepsDone < 0) {
            throw new IllegalArgumentException("Header " + STEPS_DONE + " is negative");
        }
        ObjectNode node = null;
        if (context != null && !context.isEmpty()) {
            try {
                JsonNode parsed = objectMapper.readTree(context);
                if (!(parsed instanceof ObjectNode object)) {
                    throw new IllegalArgumentException("Header " + CONTEXT + " is not a JSON object");
                }
                node = object;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Header " + CONTEXT + " is not valid JSON");
            }
        }
        return new StepProgress(stepsDone, node);
    }

    /** A copy of the message that resumes at {@code progress}; the original for a fresh start. */
    public static BrokerMessage withProgress(BrokerMessage message, StepProgress progress, ObjectMapper objectMapper) {
        if (progress == null || progress.isStart()) {
            return message;
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(STEPS_DONE, progress.stepsDone());
        try {
            headers.put(CONTEXT, progress.context() == null ? null : objectMapper.writeValueAsString(progress.context()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Step context cannot be serialized", e);
        }
        return copy(message, headers);
    }

    public static String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH) + "…";
    }
}
