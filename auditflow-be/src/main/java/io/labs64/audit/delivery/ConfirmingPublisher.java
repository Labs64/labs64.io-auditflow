package io.labs64.audit.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes and waits for the broker's publisher confirm. A message counts as stored only when the
 * broker acked it AND did not return it as unroutable ({@code mandatory}); anything else, including
 * a missing confirm within {@code auditflow.broker.confirm-timeout}, is a failure. With durable
 * queues and persistent messages a confirm means the broker has written the message.
 *
 * <p>Requires {@code spring.rabbitmq.publisher-confirm-type=correlated},
 * {@code spring.rabbitmq.publisher-returns=true} and {@code spring.rabbitmq.template.mandatory=true}.</p>
 */
@Component
public class ConfirmingPublisher {

    /** One message to publish. */
    public record Outgoing(String exchange, String routingKey, Message message) {
    }

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public ConfirmingPublisher(RabbitTemplate rabbitTemplate,
                               @Value("${auditflow.broker.confirm-timeout:PT5S}") Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = confirmTimeout;
    }

    public void publish(String exchange, String routingKey, Message message) {
        publishAll(List.of(new Outgoing(exchange, routingKey, message)));
    }

    /**
     * Publish every message, then wait for all confirms (pipelined: one round trip, not one per
     * message). Throws {@link BrokerPublishException} naming the first failure; on failure some
     * messages may have been stored, so callers must be idempotent on redelivery.
     */
    public void publishAll(List<Outgoing> messages) {
        List<String> failures = publishEach(messages);
        for (String failure : failures) {
            if (failure != null) {
                throw new BrokerPublishException(failure);
            }
        }
    }

    /** Per-message outcome: {@code null} = stored, otherwise the failure reason (same order as input). */
    public List<String> publishEach(List<Outgoing> messages) {
        List<CorrelationData> correlations = new ArrayList<>(messages.size());
        List<String> results = new ArrayList<>(messages.size());
        for (Outgoing out : messages) {
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            try {
                rabbitTemplate.send(out.exchange(), out.routingKey(), out.message(), correlation);
                correlations.add(correlation);
                results.add(null);
            } catch (RuntimeException e) {
                correlations.add(null);
                results.add("publish failed: " + e.getMessage());
            }
        }
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        for (int i = 0; i < correlations.size(); i++) {
            CorrelationData correlation = correlations.get(i);
            if (correlation == null) {
                continue;
            }
            results.set(i, awaitConfirm(correlation, deadline));
        }
        return results;
    }

    private static String awaitConfirm(CorrelationData correlation, long deadlineNanos) {
        try {
            long remaining = Math.max(0, deadlineNanos - System.nanoTime());
            CorrelationData.Confirm confirm = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
            if (correlation.getReturned() != null) {
                return "unroutable (returned by broker: " + correlation.getReturned().getReplyText() + ")";
            }
            if (!confirm.ack()) {
                return "nacked by broker: " + confirm.reason();
            }
            return null;
        } catch (TimeoutException e) {
            return "no broker confirm within timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while waiting for broker confirm";
        } catch (ExecutionException e) {
            return "broker confirm failed: " + e.getCause();
        }
    }
}
