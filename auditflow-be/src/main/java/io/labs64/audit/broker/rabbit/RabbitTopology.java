package io.labs64.audit.broker.rabbit;

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
import org.springframework.amqp.core.TopicExchange;

import io.labs64.audit.broker.Destination;
import io.labs64.audit.delivery.RetryPolicy;

/**
 * RabbitMQ objects of the two-stage delivery model, declared by the application (RabbitAdmin) so the
 * same topology exists on the in-cluster broker and on Amazon MQ.
 *
 * <pre>
 * publish ─▶ labs64-audit-topic ─▶ ingest queue ─▶ router (AuditService)
 *                                     │ retries used up       │ one message per matching pipeline
 *                                     ▼                       ▼
 *            DLX ─▶ ingest DLQ (legacy)   labs64-audit-delivery (exchange) ─▶ labs64-audit-delivery (queue) ─▶ DeliveryWorker
 *                     ▲                                                       │ retry / throttled
 *                     └──── TTL expiry (DLX) ◀── labs64-audit-delay.&lt;tier&gt; ◀──┘
 *                                                                             │ exhausted / poison
 *            labs64-audit-dlx (exchange) ─▶ labs64-audit-dlq.&lt;tenant&gt; ◀───────┘
 *
 * router / DeliveryWorker ─▶ labs64-audit-quarantine (exchange) ─▶ labs64-audit-quarantine.quarantine
 *   (unparseable event, tenant not routable)
 * </pre>
 *
 * <p>Delays use one queue per tier with a fixed queue TTL that dead-letters back to the delivery
 * exchange. Unlike per-message TTL this never blocks behind a longer-lived head message, and unlike
 * the delayed-message plugin it is available on Amazon MQ. Per-tenant DLQ queues are declared on
 * first use by {@link RabbitMessageBroker#ensureTenantDlq}.</p>
 *
 * <p>The ingest objects carry exactly the names and arguments the Spring Cloud Stream binder declared
 * for the former {@code audit-in-0} binding (group {@code labs64.io-auditflow}, {@code autoBindDlq}),
 * so brokers that already have them accept the declaration.</p>
 */
public class RabbitTopology {

    public static final String INGEST_EXCHANGE = "labs64-audit-topic";
    public static final String INGEST_ROUTING_KEY = "audit.event";
    public static final String INGEST_QUEUE = "labs64-audit-topic.labs64.io-auditflow";
    public static final String INGEST_DLX = "DLX";
    public static final String INGEST_DLQ = INGEST_QUEUE + ".dlq";
    public static final String DELIVERY_EXCHANGE = "labs64-audit-delivery";
    public static final String DELIVERY_QUEUE = "labs64-audit-delivery";
    public static final String DELIVERY_ROUTING_KEY = "deliver";
    public static final String DELAY_QUEUE_PREFIX = "labs64-audit-delay.";
    public static final String DLX_EXCHANGE = "labs64-audit-dlx";
    public static final String DLQ_QUEUE_PREFIX = "labs64-audit-dlq.";
    public static final String QUARANTINE_EXCHANGE = "labs64-audit-quarantine";
    public static final String QUARANTINE_QUEUE = "labs64-audit-quarantine.quarantine";
    public static final String QUARANTINE_ROUTING_KEY = "labs64-audit-quarantine";

    /** Exchange + routing key of a publish. */
    public record Address(String exchange, String routingKey) {
    }

    /** Queue type of the delivery, delay and DLQ queues: {@code classic} (default) or {@code quorum}. */
    private final String queueType;

    public RabbitTopology(String queueType) {
        this.queueType = queueType;
    }

    public static String delayQueueName(Duration tier) {
        return DELAY_QUEUE_PREFIX + tier.toSeconds() + "s";
    }

    /** Per-tenant DLQ queue name; tenant ids are validated at provisioning ({@code [A-Za-z0-9_.-]}). */
    public static String dlqQueueName(String tenantId) {
        return DLQ_QUEUE_PREFIX + tenantId;
    }

    /** The shortest delay tier that is at least {@code delay} (the longest tier if none is). */
    static Duration tierFor(Duration delay) {
        for (Duration tier : RetryPolicy.TIERS) {
            if (tier.compareTo(delay) >= 0) {
                return tier;
            }
        }
        return RetryPolicy.TIERS.get(RetryPolicy.TIERS.size() - 1);
    }

    /** Where a publish to {@code destination} goes. */
    public static Address address(Destination destination) {
        return switch (destination.kind()) {
            case INGEST -> new Address(INGEST_EXCHANGE, INGEST_ROUTING_KEY);
            // The binder bound the ingest DLQ to DLX with the ingest queue's name.
            case INGEST_DLQ -> new Address(INGEST_DLX, INGEST_QUEUE);
            case DELIVERY -> new Address(DELIVERY_EXCHANGE, DELIVERY_ROUTING_KEY);
            case DELAY -> new Address("", delayQueueName(tierFor(destination.delay())));
            case TENANT_DLQ -> new Address(DLX_EXCHANGE, destination.tenantId());
            case QUARANTINE -> new Address(QUARANTINE_EXCHANGE, QUARANTINE_ROUTING_KEY);
        };
    }

    /** The queue a DLQ scan reads. */
    public static String queueName(Destination destination) {
        return switch (destination.kind()) {
            case TENANT_DLQ -> dlqQueueName(destination.tenantId());
            case INGEST_DLQ -> INGEST_DLQ;
            default -> throw new IllegalArgumentException("Not a scannable queue: " + destination.kind());
        };
    }

    Map<String, Object> queueArguments() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-queue-type", queueType);
        return args;
    }

    public Declarables declarables() {
        List<Declarable> declarables = new ArrayList<>();

        // Ingest, as the binder declared it: no queue-type argument (it would not match).
        TopicExchange ingest = new TopicExchange(INGEST_EXCHANGE, true, false);
        Map<String, Object> ingestArgs = new HashMap<>();
        ingestArgs.put("x-dead-letter-exchange", INGEST_DLX);
        ingestArgs.put("x-dead-letter-routing-key", INGEST_QUEUE);
        Queue ingestQueue = new Queue(INGEST_QUEUE, true, false, false, ingestArgs);
        DirectExchange ingestDlx = new DirectExchange(INGEST_DLX, true, false);
        Queue ingestDlq = new Queue(INGEST_DLQ, true, false, false);
        declarables.add(ingest);
        declarables.add(ingestQueue);
        declarables.add(BindingBuilder.bind(ingestQueue).to(ingest).with("#"));
        declarables.add(ingestDlx);
        declarables.add(ingestDlq);
        declarables.add(BindingBuilder.bind(ingestDlq).to(ingestDlx).with(INGEST_QUEUE));

        DirectExchange delivery = new DirectExchange(DELIVERY_EXCHANGE, true, false);
        Queue deliveryQueue = new Queue(DELIVERY_QUEUE, true, false, false, queueArguments());
        declarables.add(delivery);
        declarables.add(deliveryQueue);
        declarables.add(BindingBuilder.bind(deliveryQueue).to(delivery).with(DELIVERY_ROUTING_KEY));

        for (Duration tier : RetryPolicy.TIERS) {
            Map<String, Object> args = queueArguments();
            args.put("x-message-ttl", tier.toMillis());
            args.put("x-dead-letter-exchange", DELIVERY_EXCHANGE);
            args.put("x-dead-letter-routing-key", DELIVERY_ROUTING_KEY);
            declarables.add(new Queue(delayQueueName(tier), true, false, false, args));
        }

        declarables.add(new DirectExchange(DLX_EXCHANGE, true, false));

        // Same names and properties the stream binder declared for the former quarantine-out-0
        // binding (topic exchange, durable queue without arguments, bound with "#"), so brokers that
        // already have them accept the declaration; a queue-type argument here would not match.
        TopicExchange quarantine = new TopicExchange(QUARANTINE_EXCHANGE, true, false);
        Queue quarantineQueue = new Queue(QUARANTINE_QUEUE, true, false, false);
        declarables.add(quarantine);
        declarables.add(quarantineQueue);
        declarables.add(BindingBuilder.bind(quarantineQueue).to(quarantine).with("#"));
        return new Declarables(declarables);
    }

    /** Queue + binding for one tenant's DLQ (routing key = tenant id). */
    Declarable[] tenantDlq(String tenantId) {
        Queue queue = new Queue(dlqQueueName(tenantId), true, false, false, queueArguments());
        Binding binding = new Binding(queue.getName(), Binding.DestinationType.QUEUE, DLX_EXCHANGE, tenantId, null);
        return new Declarable[] { queue, binding };
    }
}
