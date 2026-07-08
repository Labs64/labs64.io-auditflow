package io.labs64.audit.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Actuator endpoint for managing the Dead Letter Queue (DLQ).
 * Provides visibility into failed events and allows retrying them.
 */
@Endpoint(id = "dlq")
@Component
public class DlqEndpoint {

    private static final Logger logger = LoggerFactory.getLogger(DlqEndpoint.class);

    private static final String DLQ_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow.dlq";
    private static final String MAIN_QUEUE_NAME = "labs64-audit-topic.labs64.io-auditflow";

    private final RabbitTemplate rabbitTemplate;

    public DlqEndpoint(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @ReadOperation
    public Map<String, Object> getDlqInfo() {
        logger.info("DLQ info requested");
        Map<String, Object> info = new HashMap<>();

        try {
            Integer messageCount = rabbitTemplate.execute(channel ->
                    channel.queueDeclarePassive(DLQ_QUEUE_NAME).getMessageCount()
            );

            info.put("queueName", DLQ_QUEUE_NAME);
            info.put("messageCount", messageCount != null ? messageCount : 0);
            info.put("status", "available");
        } catch (Exception e) {
            logger.error("Failed to get DLQ info", e);
            info.put("status", "error");
            info.put("error", e.getMessage());
        }

        return info;
    }

    @WriteOperation
    public Map<String, Object> retryAllMessages() {
        logger.warn("DLQ retry requested — retrying all messages");
        Map<String, Object> result = new HashMap<>();
        int retriedCount = 0;
        int failedCount = 0;

        try {
            Message message = rabbitTemplate.receive(DLQ_QUEUE_NAME, 1000);
            while (message != null) {
                try {
                    rabbitTemplate.send(MAIN_QUEUE_NAME, message);
                    retriedCount++;
                } catch (Exception e) {
                    failedCount++;
                    logger.error("Failed to retry DLQ message", e);
                }
                message = rabbitTemplate.receive(DLQ_QUEUE_NAME, 100);
            }

            logger.info("DLQ retry completed — retried: {}, failed: {}", retriedCount, failedCount);
            result.put("status", "success");
            result.put("retriedCount", retriedCount);
            result.put("failedCount", failedCount);
            result.put("message", "Retried " + retriedCount + " messages from DLQ" +
                    (failedCount > 0 ? " (" + failedCount + " failed)" : ""));
        } catch (Exception e) {
            logger.error("Failed to retry DLQ messages", e);
            result.put("status", "error");
            result.put("error", e.getMessage());
        }

        return result;
    }
}
