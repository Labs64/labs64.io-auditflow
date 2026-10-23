package io.labs64.audit.broker;

import java.util.List;

import io.labs64.audit.delivery.BrokerPublishException;

/**
 * The broker port: everything the application needs from a message broker. One adapter is active,
 * chosen by {@code auditflow.broker.type} ({@code rabbitmq} or {@code sqs}); the consumers of each
 * adapter feed {@link IngestConsumer} and {@code DeliveryWorker}.
 *
 * <p>Contract every adapter keeps:</p>
 * <ul>
 *   <li>a publish counts as stored only when the broker durably accepted it (RabbitMQ: publisher
 *       confirm and routable; SQS: a successful SendMessage);</li>
 *   <li>a consumed message is removed only after the consumer returned without throwing; a throw
 *       makes it available again (redelivered);</li>
 *   <li>delivery is at least once: callers are idempotent on redelivery.</li>
 * </ul>
 */
public interface MessageBroker {

    /** One message to publish. */
    record Outgoing(Destination destination, BrokerMessage message) {
    }

    /** What a {@link #scan} visitor does with one message. */
    enum ScanAction {
        /** Leave in the queue, not counted. */
        KEEP,
        /** Leave in the queue, counted. */
        KEEP_COUNTED,
        /** Remove from the queue (forwarded or discarded), counted. */
        ACK_COUNTED
    }

    @FunctionalInterface
    interface ScanVisitor {
        ScanAction visit(BrokerMessage message) throws Exception;
    }

    /** The configured broker type, as in {@code auditflow.broker.type}. */
    String type();

    /**
     * Publish every message and wait until the broker stored each one (pipelined where the broker
     * allows it). Returns one entry per message in input order: {@code null} when stored, otherwise
     * the failure reason. On failure some messages may have been stored.
     */
    List<String> publishEach(List<Outgoing> messages);

    /** Like {@link #publishEach} but throws {@link BrokerPublishException} naming the first failure. */
    default void publishAll(List<Outgoing> messages) {
        for (String failure : publishEach(messages)) {
            if (failure != null) {
                throw new BrokerPublishException(failure);
            }
        }
    }

    default void publish(Destination destination, BrokerMessage message) {
        publishAll(List.of(new Outgoing(destination, message)));
    }

    /** Make sure the tenant's DLQ exists (idempotent, cached after the first success). */
    void ensureTenantDlq(String tenantId);

    /**
     * Visit every message currently in {@code queue} ({@link Destination.Kind#TENANT_DLQ} or
     * {@link Destination.Kind#INGEST_DLQ}) once. A message is removed only when the visitor answers
     * {@link ScanAction#ACK_COUNTED}; the others stay. If the visitor throws, the current message
     * stays and the exception propagates.
     *
     * @return the number of messages counted
     */
    long scan(Destination queue, ScanVisitor visitor);

    /**
     * Largest message, body and headers, the broker accepts. Ingest rejects a larger event with 413
     * instead of failing it as unavailable on every retry.
     */
    default int maxMessageBytes() {
        return Integer.MAX_VALUE;
    }
}
