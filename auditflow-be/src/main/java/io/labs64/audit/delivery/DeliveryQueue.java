package io.labs64.audit.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * The delivery side of the broker: enqueue, retry later, defer, replay. Every operation is a
 * confirmed publish, so the caller may ack the message it is working on only after this returns.
 */
@Component
public class DeliveryQueue {

    private final ConfirmingPublisher publisher;

    public DeliveryQueue(ConfirmingPublisher publisher) {
        this.publisher = publisher;
    }

    /** One delivery message per pipeline, all confirmed before returning. */
    public void enqueue(String eventJson, String tenantId, List<String> pipelines, String eventId) {
        long now = System.currentTimeMillis();
        List<ConfirmingPublisher.Outgoing> out = new ArrayList<>(pipelines.size());
        for (String pipeline : pipelines) {
            out.add(new ConfirmingPublisher.Outgoing(BrokerTopology.DELIVERY_EXCHANGE, BrokerTopology.DELIVERY_ROUTING_KEY,
                    DeliveryHeaders.newDelivery(eventJson, tenantId, pipeline, eventId, now)));
        }
        publisher.publishAll(out);
    }

    /** Park a failed delivery in the delay tier; it returns to the delivery queue after {@code delay}. */
    public void retryLater(Message delivery, int failedAttempts, String error, Duration delay) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(DeliveryHeaders.ATTEMPTS, failedAttempts);
        headers.put(DeliveryHeaders.LAST_ERROR, DeliveryHeaders.truncateError(error));
        publisher.publish("", BrokerTopology.delayQueueName(delay), DeliveryHeaders.copy(delivery, headers));
    }

    /** Backpressure: come back after the shortest delay, WITHOUT spending an attempt. */
    public void defer(Message delivery) {
        int throttled = DeliveryHeaders.intHeader(delivery, DeliveryHeaders.THROTTLED) + 1;
        publisher.publish("", BrokerTopology.delayQueueName(RetryPolicy.throttleDelay()),
                DeliveryHeaders.copy(delivery, Map.of(DeliveryHeaders.THROTTLED, throttled)));
    }

    /** Re-deliver a DLQ entry as a fresh delivery: attempts and age start over, DLQ headers removed. */
    public void replay(Message dlqEntry) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(DeliveryHeaders.ATTEMPTS, 0);
        headers.put(DeliveryHeaders.THROTTLED, 0);
        headers.put(DeliveryHeaders.FIRST_ENQUEUED_AT, System.currentTimeMillis());
        headers.put(DeliveryHeaders.DLQ_REASON, null);
        headers.put(DeliveryHeaders.DLQ_AT, null);
        headers.put(DeliveryHeaders.LAST_ERROR, null);
        publisher.publish(BrokerTopology.DELIVERY_EXCHANGE, BrokerTopology.DELIVERY_ROUTING_KEY,
                DeliveryHeaders.copy(dlqEntry, headers));
    }
}
