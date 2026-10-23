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
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.broker.MessageBroker.ScanAction;
import io.labs64.audit.delivery.DeadLetterPublisher;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryQueue;
import io.labs64.audit.tenant.TenantIds;

/**
 * Tenant-scoped DLQ actuator ({@code /actuator/dlq/<tenantId>}).
 *
 * <p>Two sources per tenant:</p>
 * <ul>
 *   <li>the tenant's own DLQ queue ({@link Destination#tenantDlq}): one entry per failed pipeline
 *       delivery, with reason, attempts and last error in headers. Only this tenant's entries are
 *       ever in it, so no other tenant's message is read;</li>
 *   <li>the legacy shared ingest DLQ ({@link Destination#ingestDlq}), filtered by the body
 *       {@code tenantId}: events whose routing failed, and entries from before per-pipeline delivery.</li>
 * </ul>
 *
 * <p>Replay re-enqueues a delivery entry as a fresh delivery for its pipeline (attempts and age start
 * over) and a legacy entry onto the ingest queue. Optional {@code pipeline} narrows replay and purge
 * to one pipeline. Safety: the broker adapter's {@link MessageBroker#scan} removes a message only
 * after it was forwarded (replay, confirmed) or on purpose (purge); every other message stays, also
 * when the operation crashes.</p>
 */
@Endpoint(id = "dlq")
@Component
public class DlqEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(DlqEndpoint.class);

    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private final MessageBroker broker;
    private final ObjectMapper objectMapper;
    private final DeadLetterPublisher deadLetters;
    private final DeliveryQueue deliveryQueue;

    public DlqEndpoint(MessageBroker broker, ObjectMapper objectMapper, DeadLetterPublisher deadLetters,
                       DeliveryQueue deliveryQueue) {
        this.broker = broker;
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
            long tenantCount = broker.scan(Destination.tenantDlq(wanted), resp -> {
                byPipeline.merge(header(resp, DeliveryHeaders.PIPELINE), 1, Integer::sum);
                byReason.merge(header(resp, DeliveryHeaders.DLQ_REASON), 1, Integer::sum);
                if (entries.size() < max && (pipeline == null || pipeline.equals(header(resp, DeliveryHeaders.PIPELINE)))) {
                    entries.add(entry(resp, false));
                }
                return ScanAction.KEEP_COUNTED;
            });
            long legacyCount = broker.scan(Destination.ingestDlq(), resp -> {
                if (!wanted.equals(tenantOf(resp.body()))) {
                    return ScanAction.KEEP;
                }
                if (entries.size() < max && pipeline == null) {
                    entries.add(entry(resp, true));
                }
                return ScanAction.KEEP_COUNTED;
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
            long replayed = broker.scan(Destination.tenantDlq(wanted), resp -> {
                if (pipeline != null && !pipeline.equals(header(resp, DeliveryHeaders.PIPELINE))) {
                    return ScanAction.KEEP;
                }
                deliveryQueue.replay(resp); // confirmed before the ack below
                return ScanAction.ACK_COUNTED;
            });
            long legacyReplayed = pipeline != null ? 0 : broker.scan(Destination.ingestDlq(), resp -> {
                if (!wanted.equals(tenantOf(resp.body()))) {
                    return ScanAction.KEEP;
                }
                BrokerMessage again = new BrokerMessage(resp.body(), resp.headers(), resp.messageId());
                again.headers().remove(IngestConsumer.EXCEPTION_MESSAGE);
                broker.publish(Destination.ingest(), again); // confirmed before the ack
                return ScanAction.ACK_COUNTED;
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
            long purged = broker.scan(Destination.tenantDlq(wanted), resp -> {
                if (pipeline != null && !pipeline.equals(header(resp, DeliveryHeaders.PIPELINE))) {
                    return ScanAction.KEEP;
                }
                logger.warn("DLQ purge: discarding eventId={} pipeline={} reason={}",
                        header(resp, DeliveryHeaders.EVENT_ID), header(resp, DeliveryHeaders.PIPELINE),
                        header(resp, DeliveryHeaders.DLQ_REASON));
                return ScanAction.ACK_COUNTED;
            });
            long legacyPurged = pipeline != null ? 0 : broker.scan(Destination.ingestDlq(),
                    resp -> wanted.equals(tenantOf(resp.body())) ? ScanAction.ACK_COUNTED : ScanAction.KEEP);
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

    /** One DLQ entry for inspection; the body is returned as JSON when it parses, else as text. */
    private Map<String, Object> entry(BrokerMessage resp, boolean legacy) {
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        String body = resp.bodyAsString();
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
            Object death = resp.header(IngestConsumer.EXCEPTION_MESSAGE);
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

    private static String nullable(BrokerMessage resp, String name) {
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

    private static String header(BrokerMessage resp, String name) {
        Object value = resp.header(name);
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
