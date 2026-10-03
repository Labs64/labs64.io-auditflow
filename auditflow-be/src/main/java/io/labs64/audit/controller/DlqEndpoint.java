package io.labs64.audit.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;

import io.labs64.audit.delivery.BrokerTopology;
import io.labs64.audit.delivery.DeadLetterPublisher;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryQueue;
import io.labs64.audit.tenant.TenantIds;

/**
 * Tenant-scoped DLQ actuator ({@code /actuator/dlq/<tenantId>}).
 *
 * <p>Two sources per tenant:</p>
 * <ul>
 *   <li>the tenant's own DLQ queue {@code labs64-audit-dlq.<tenant>}: one entry per failed pipeline
 *       delivery, with reason, attempts and last error in headers. Only this tenant's entries are
 *       ever in it, so no other tenant's message is read;</li>
 *   <li>the legacy shared ingest DLQ, filtered by the body {@code tenantId}: events whose routing
 *       failed, and entries from before per-pipeline delivery.</li>
 * </ul>
 *
 * <p>Replay re-enqueues a delivery entry as a fresh delivery for its pipeline (attempts and age start
 * over) and a legacy entry onto the ingest queue. Optional {@code pipeline} narrows replay and purge
 * to one pipeline. Safety: everything runs on one channel with manual acks; a message is acked only
 * after it was forwarded (replay, confirmed) or on purpose (purge). Unacked messages return to their
 * queue if the operation crashes.</p>
 */
@Endpoint(id = "dlq")
@Component
public class DlqEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(DlqEndpoint.class);

    static final String LEGACY_DLQ_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow.dlq";
    static final String INGEST_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow";
    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublisher deadLetters;
    private final DeliveryQueue deliveryQueue;
    private final DefaultMessagePropertiesConverter propertiesConverter = new DefaultMessagePropertiesConverter();

    public DlqEndpoint(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper, DeadLetterPublisher deadLetters,
                       DeliveryQueue deliveryQueue) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.deadLetters = deadLetters;
        this.deliveryQueue = deliveryQueue;
    }

    /** Most entries one inspection returns with content. */
    static final int MAX_INSPECT = 100;

    /** Count (non-destructive): total, legacy share, and the tenant's entries by pipeline and reason. */
    public Map<String, Object> getDlqInfo(String tenantId) {
        return getDlqInfo(tenantId, null, null);
    }

    /**
     * Inspect (non-destructive). Always the counts; with {@code limit} (1..100) also up to that many
     * entries with their content: event id, pipeline, reason, attempts, last error, timestamps and the
     * event as it was routed (already redacted at ingest). {@code pipeline} narrows the entries.
     */
    @ReadOperation
    public Map<String, Object> getDlqInfo(@Selector String tenantId, @Nullable Integer limit, @Nullable String pipeline) {
        Map<String, Object> info = new HashMap<>();
        String wanted;
        try {
            wanted = validTenant(tenantId);
        } catch (IllegalArgumentException e) {
            return error(tenantId, e);
        }
        try {
            deadLetters.ensureTenantDlq(wanted);
            int max = limit == null ? 0 : Math.max(0, Math.min(MAX_INSPECT, limit));
            List<Map<String, Object>> entries = new ArrayList<>();
            Map<String, Integer> byPipeline = new TreeMap<>();
            Map<String, Integer> byReason = new TreeMap<>();
            long tenantCount = scan(BrokerTopology.dlqQueueName(wanted), (channel, resp) -> {
                byPipeline.merge(header(resp, DeliveryHeaders.PIPELINE), 1, Integer::sum);
                byReason.merge(header(resp, DeliveryHeaders.DLQ_REASON), 1, Integer::sum);
                if (entries.size() < max && (pipeline == null || pipeline.equals(header(resp, DeliveryHeaders.PIPELINE)))) {
                    entries.add(entry(resp, false));
                }
                return Action.KEEP_COUNTED;
            });
            long legacyCount = scan(LEGACY_DLQ_QUEUE_NAME, (channel, resp) -> {
                if (!wanted.equals(tenantOf(resp.getBody()))) {
                    return Action.KEEP;
                }
                if (entries.size() < max && pipeline == null) {
                    entries.add(entry(resp, true));
                }
                return Action.KEEP_COUNTED;
            });
            info.put("tenantId", wanted);
            info.put("messageCount", tenantCount + legacyCount);
            info.put("legacyMessageCount", legacyCount);
            info.put("byPipeline", byPipeline);
            info.put("byReason", byReason);
            if (max > 0) {
                info.put("entries", entries);
            }
            info.put("status", "available");
        } catch (Exception e) {
            logger.error("Failed to inspect DLQ for tenant '{}'", wanted, e);
            return error(wanted, e);
        }
        return info;
    }

    /** Replay the tenant's entries (optionally only {@code pipeline}'s) for another delivery. */
    @WriteOperation
    public Map<String, Object> retry(@Selector String tenantId, @Nullable String pipeline) {
        String wanted;
        try {
            wanted = validTenant(tenantId);
        } catch (IllegalArgumentException e) {
            return error(tenantId, e);
        }
        logger.warn("DLQ replay requested for tenant '{}' pipeline='{}'", wanted, pipeline);
        try {
            deadLetters.ensureTenantDlq(wanted);
            long replayed = scan(BrokerTopology.dlqQueueName(wanted), (channel, resp) -> {
                if (pipeline != null && !pipeline.equals(header(resp, DeliveryHeaders.PIPELINE))) {
                    return Action.KEEP;
                }
                deliveryQueue.replay(toMessage(resp)); // confirmed before the ack below
                return Action.ACK_COUNTED;
            });
            long legacyReplayed = pipeline != null ? 0 : scan(LEGACY_DLQ_QUEUE_NAME, (channel, resp) -> {
                if (!wanted.equals(tenantOf(resp.getBody()))) {
                    return Action.KEEP;
                }
                channel.basicPublish("", INGEST_QUEUE_NAME, resp.getProps(), resp.getBody());
                return Action.ACK_COUNTED;
            });
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("tenantId", wanted);
            result.put("retriedCount", (int) (replayed + legacyReplayed));
            result.put("legacyRetriedCount", (int) legacyReplayed);
            logger.info("DLQ replay for tenant '{}' completed: {} delivery entr(ies), {} legacy event(s)",
                    wanted, replayed, legacyReplayed);
            return result;
        } catch (Exception e) {
            logger.error("Failed to replay DLQ for tenant '{}'", wanted, e);
            return error(wanted, e);
        }
    }

    /**
     * Discard the tenant's entries (optionally only {@code pipeline}'s) for good: for poison entries
     * an operator has accepted to lose. Every discarded entry is logged with its event id.
     */
    @DeleteOperation
    public Map<String, Object> purge(@Selector String tenantId, @Nullable String pipeline) {
        String wanted;
        try {
            wanted = validTenant(tenantId);
        } catch (IllegalArgumentException e) {
            return error(tenantId, e);
        }
        logger.warn("DLQ purge requested for tenant '{}' pipeline='{}': matching entries will be discarded",
                wanted, pipeline);
        try {
            deadLetters.ensureTenantDlq(wanted);
            long purged = scan(BrokerTopology.dlqQueueName(wanted), (channel, resp) -> {
                if (pipeline != null && !pipeline.equals(header(resp, DeliveryHeaders.PIPELINE))) {
                    return Action.KEEP;
                }
                logger.warn("DLQ purge: discarding eventId={} pipeline={} reason={}",
                        header(resp, DeliveryHeaders.EVENT_ID), header(resp, DeliveryHeaders.PIPELINE),
                        header(resp, DeliveryHeaders.DLQ_REASON));
                return Action.ACK_COUNTED;
            });
            long legacyPurged = pipeline != null ? 0 : scan(LEGACY_DLQ_QUEUE_NAME,
                    (channel, resp) -> wanted.equals(tenantOf(resp.getBody())) ? Action.ACK_COUNTED : Action.KEEP);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("tenantId", wanted);
            result.put("purgedCount", (int) (purged + legacyPurged));
            result.put("legacyPurgedCount", (int) legacyPurged);
            logger.info("DLQ purge for tenant '{}' completed: {} entr(ies) discarded", wanted, purged + legacyPurged);
            return result;
        } catch (Exception e) {
            logger.error("Failed to purge DLQ for tenant '{}'", wanted, e);
            return error(wanted, e);
        }
    }

    private enum Action {
        /** Leave in the queue, not counted. */
        KEEP,
        /** Leave in the queue, counted. */
        KEEP_COUNTED,
        /** Remove from the queue (forwarded or discarded), counted. */
        ACK_COUNTED
    }

    @FunctionalInterface
    private interface Visitor {
        Action visit(Channel channel, GetResponse response) throws Exception;
    }

    /**
     * Visit every message currently in {@code queue} once. Kept messages are nacked back only at the
     * end: unacked messages are not handed out again on the same channel, so the loop terminates.
     */
    private long scan(String queue, Visitor visitor) {
        Long count = rabbitTemplate.execute(channel -> {
            long counted = 0;
            List<Long> keep = new ArrayList<>();
            try {
                GetResponse resp;
                while ((resp = channel.basicGet(queue, false)) != null) {
                    long tag = resp.getEnvelope().getDeliveryTag();
                    Action action;
                    try {
                        action = visitor.visit(channel, resp);
                    } catch (Exception e) {
                        keep.add(tag); // never leave it unacked on a cached channel: back to the queue
                        throw e;
                    }
                    if (action == Action.ACK_COUNTED) {
                        channel.basicAck(tag, false);
                        counted++;
                    } else {
                        keep.add(tag);
                        if (action == Action.KEEP_COUNTED) {
                            counted++;
                        }
                    }
                }
            } finally {
                for (long tag : keep) {
                    channel.basicNack(tag, false, true);
                }
            }
            return counted;
        });
        return count == null ? 0 : count;
    }

    /** One DLQ entry for inspection; the body is returned as JSON when it parses, else as text. */
    private Map<String, Object> entry(GetResponse resp, boolean legacy) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        String body = new String(resp.getBody(), StandardCharsets.UTF_8);
        // Plain maps and lists, not a JsonNode: the actuator serialises with its own JSON mapper.
        Object event;
        try {
            event = objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            event = body;
        }
        if (legacy) {
            entry.put("legacy", true);
            entry.put("eventId", event instanceof Map<?, ?> m && m.get("eventId") != null ? m.get("eventId").toString() : null);
            Object death = resp.getProps() == null || resp.getProps().getHeaders() == null ? null
                    : resp.getProps().getHeaders().get("x-exception-message");
            entry.put("lastError", death == null ? null : death.toString());
        } else {
            entry.put("eventId", nullable(resp, DeliveryHeaders.EVENT_ID));
            entry.put("pipeline", nullable(resp, DeliveryHeaders.PIPELINE));
            entry.put("reason", nullable(resp, DeliveryHeaders.DLQ_REASON));
            String attempts = nullable(resp, DeliveryHeaders.ATTEMPTS);
            entry.put("attempts", attempts == null || !attempts.matches("\\d{1,9}") ? attempts : Integer.valueOf(attempts));
            entry.put("lastError", nullable(resp, DeliveryHeaders.LAST_ERROR));
            entry.put("deadLetteredAt", instant(nullable(resp, DeliveryHeaders.DLQ_AT)));
            entry.put("firstEnqueuedAt", instant(nullable(resp, DeliveryHeaders.FIRST_ENQUEUED_AT)));
        }
        entry.put("event", event);
        return entry;
    }

    private static String nullable(GetResponse resp, String name) {
        String value = header(resp, name);
        return "-".equals(value) ? null : value;
    }

    private static String instant(String epochMillis) {
        try {
            return epochMillis == null ? null : java.time.Instant.ofEpochMilli(Long.parseLong(epochMillis)).toString();
        } catch (NumberFormatException e) {
            return epochMillis;
        }
    }

    private Message toMessage(GetResponse resp) {
        return new Message(resp.getBody(), propertiesConverter.toMessageProperties(
                resp.getProps(), resp.getEnvelope(), StandardCharsets.UTF_8.name()));
    }

    private static String header(GetResponse resp, String name) {
        Map<String, Object> headers = resp.getProps() == null ? null : resp.getProps().getHeaders();
        Object value = headers == null ? null : headers.get(name);
        return value == null ? "-" : value.toString();
    }

    private static String validTenant(String raw) {
        String tenant = TenantIds.resolve(raw);
        if (!TenantIds.PLATFORM.equals(tenant) && !TENANT_ID.matcher(tenant).matches()) {
            throw new IllegalArgumentException("Invalid tenant id '" + raw + "'");
        }
        return tenant;
    }

    private static Map<String, Object> error(String tenantId, Exception e) {
        Map<String, Object> result = new HashMap<>();
        result.put("tenantId", tenantId);
        result.put("status", "error");
        result.put("error", e.getMessage());
        return result;
    }

    private String tenantOf(byte[] body) {
        try {
            return TenantIds.resolve(
                    objectMapper.readTree(new String(body, StandardCharsets.UTF_8)).path("tenantId").asText(null));
        } catch (Exception e) {
            return TenantIds.PLATFORM; // unparseable -> platform bucket, never another tenant
        }
    }
}
