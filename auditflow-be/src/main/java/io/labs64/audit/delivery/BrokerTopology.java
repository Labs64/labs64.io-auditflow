package io.labs64.audit.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Broker objects of the two-stage delivery model, declared by the application (RabbitAdmin) so the
 * same topology exists on the in-cluster broker and on Amazon MQ.
 *
 * <pre>
 * publish ─▶ labs64-audit-topic ─▶ ingest queue ─▶ router (AuditService)
 *                                                    │ one message per matching pipeline
 *                                                    ▼
 *            labs64-audit-delivery (exchange) ─▶ labs64-audit-delivery (queue) ─▶ DeliveryWorker
 *                     ▲                                                       │ retry / throttled
 *                     └──── TTL expiry (DLX) ◀── labs64-audit-delay.&lt;tier&gt; ◀──┘
 *                                                                             │ exhausted / poison
 *            labs64-audit-dlx (exchange) ─▶ labs64-audit-dlq.&lt;tenant&gt; ◀───────┘
 * </pre>
 *
 * <p>Delays use one queue per tier with a fixed queue TTL that dead-letters back to the delivery
 * exchange. Unlike per-message TTL this never blocks behind a longer-lived head message, and unlike
 * the delayed-message plugin it is available on Amazon MQ. Per-tenant DLQ queues are declared on
 * first use by {@link DeadLetterPublisher}.</p>
 */
@Configuration
public class BrokerTopology {

    public static final String DELIVERY_EXCHANGE = "labs64-audit-delivery";
    public static final String DELIVERY_QUEUE = "labs64-audit-delivery";
    public static final String DELIVERY_ROUTING_KEY = "deliver";
    public static final String DELAY_QUEUE_PREFIX = "labs64-audit-delay.";
    public static final String DLX_EXCHANGE = "labs64-audit-dlx";
    public static final String DLQ_QUEUE_PREFIX = "labs64-audit-dlq.";

    /** Redelivery delays, shortest first. Attempt n waits {@code TIERS[min(n-1, last)]}. */
    public static final List<Duration> TIERS = List.of(
            Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3));

    /** Queue type of the new queues: {@code classic} (default) or {@code quorum} (replicated, multi-node brokers). */
    private final String queueType;

    public BrokerTopology(@org.springframework.beans.factory.annotation.Value(
            "${auditflow.broker.queue-type:classic}") String queueType) {
        this.queueType = queueType;
    }

    public static String delayQueueName(Duration tier) {
        return DELAY_QUEUE_PREFIX + tier.toSeconds() + "s";
    }

    /** Per-tenant DLQ queue name; tenant ids are validated at provisioning ({@code [A-Za-z0-9_.-]}). */
    public static String dlqQueueName(String tenantId) {
        return DLQ_QUEUE_PREFIX + tenantId;
    }

    Map<String, Object> queueArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-queue-type", queueType);
        return args;
    }

    @Bean
    public Declarables deliveryTopology() {
        List<Declarable> declarables = new ArrayList<>();
        DirectExchange delivery = new DirectExchange(DELIVERY_EXCHANGE, true, false);
        Queue deliveryQueue = new Queue(DELIVERY_QUEUE, true, false, false, queueArguments());
        declarables.add(delivery);
        declarables.add(deliveryQueue);
        declarables.add(BindingBuilder.bind(deliveryQueue).to(delivery).with(DELIVERY_ROUTING_KEY));

        for (Duration tier : TIERS) {
            Map<String, Object> args = queueArguments();
            args.put("x-message-ttl", tier.toMillis());
            args.put("x-dead-letter-exchange", DELIVERY_EXCHANGE);
            args.put("x-dead-letter-routing-key", DELIVERY_ROUTING_KEY);
            declarables.add(new Queue(delayQueueName(tier), true, false, false, args));
        }

        declarables.add(new DirectExchange(DLX_EXCHANGE, true, false));
        return new Declarables(declarables);
    }

    /** Queue + binding for one tenant's DLQ (routing key = tenant id). */
    Declarable[] tenantDlq(String tenantId) {
        Queue queue = new Queue(dlqQueueName(tenantId), true, false, false, queueArguments());
        Binding binding = new Binding(queue.getName(), Binding.DestinationType.QUEUE, DLX_EXCHANGE, tenantId, null);
        return new Declarable[] { queue, binding };
    }
}
