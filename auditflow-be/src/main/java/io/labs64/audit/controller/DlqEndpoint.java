package io.labs64.audit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.GetResponse;
import io.labs64.audit.tenant.TenantIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenant-scoped DLQ actuator. Every operation REQUIRES a tenantId and only ever touches messages
 * whose authoritative body {@code tenantId} matches — an operator replaying tenant A's poison
 * messages can never list, replay, or expose tenant B's. Replayed messages re-enter the normal
 * consumer flow and are re-subjected to routing isolation (so replay after offboarding re-quarantines).
 *
 * <p>Safety: everything runs on one channel with manual acks. Inspect fetches with
 * {@code basicGet(autoAck=false)} and nacks everything back (unacked messages are invisible to the
 * same-channel loop, so this terminates); replay acks a message only AFTER it was published to the
 * main queue. A crash mid-operation loses nothing — unacked messages return to the DLQ.</p>
 */
@Endpoint(id = "dlq")
@Component
public class DlqEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(DlqEndpoint.class);
    private static final String DLQ_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow.dlq";
    private static final String MAIN_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow";

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public DlqEndpoint(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @ReadOperation
    public Map<String, Object> getDlqInfo(@Selector String tenantId) {
        String wanted = TenantIds.resolve(tenantId);
        Map<String, Object> info = new HashMap<>();
        try {
            Long matching = rabbitTemplate.execute(channel -> {
                long count = 0;
                List<Long> tags = new ArrayList<>();
                GetResponse resp;
                while ((resp = channel.basicGet(DLQ_QUEUE_NAME, false)) != null) {
                    tags.add(resp.getEnvelope().getDeliveryTag());
                    if (wanted.equals(tenantOf(resp.getBody()))) {
                        count++;
                    }
                }
                for (long tag : tags) {
                    channel.basicNack(tag, false, true); // non-destructive peek: everything goes back
                }
                return count;
            });
            info.put("tenantId", wanted);
            info.put("messageCount", matching);
            info.put("status", "available");
        } catch (Exception e) {
            logger.error("Failed to inspect DLQ for tenant '{}'", wanted, e);
            info.put("status", "error");
            info.put("error", e.getMessage());
        }
        return info;
    }

    @WriteOperation
    public Map<String, Object> retry(@Selector String tenantId) {
        String wanted = TenantIds.resolve(tenantId);
        logger.warn("DLQ replay requested for tenant '{}'", wanted);
        Map<String, Object> result = new HashMap<>();
        try {
            long[] counts = rabbitTemplate.execute(channel -> {
                long retried = 0;
                List<Long> keep = new ArrayList<>();
                GetResponse resp;
                while ((resp = channel.basicGet(DLQ_QUEUE_NAME, false)) != null) {
                    if (wanted.equals(tenantOf(resp.getBody()))) {
                        channel.basicPublish("", MAIN_QUEUE_NAME, resp.getProps(), resp.getBody());
                        channel.basicAck(resp.getEnvelope().getDeliveryTag(), false); // ack AFTER forward
                        retried++;
                    } else {
                        keep.add(resp.getEnvelope().getDeliveryTag()); // untouched, nacked back below
                    }
                }
                for (long tag : keep) {
                    channel.basicNack(tag, false, true);
                }
                return new long[] { retried, keep.size() };
            });
            result.put("status", "success");
            result.put("tenantId", wanted);
            result.put("retriedCount", (int) counts[0]);
            result.put("requeuedCount", (int) counts[1]);
            logger.info("DLQ replay for tenant '{}' completed — retried: {}, requeued: {}",
                    wanted, counts[0], counts[1]);
        } catch (Exception e) {
            logger.error("Failed to replay DLQ for tenant '{}'", wanted, e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }
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
