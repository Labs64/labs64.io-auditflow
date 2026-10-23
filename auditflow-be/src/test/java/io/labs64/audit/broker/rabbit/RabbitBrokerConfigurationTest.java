package io.labs64.audit.broker.rabbit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.DirectFieldAccessor;

import com.rabbitmq.client.Channel;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryWorker;

class RabbitBrokerConfigurationTest {

    private final RabbitBrokerConfiguration config = new RabbitBrokerConfiguration();

    // ---- topology ------------------------------------------------------------------------------

    private static Map<String, Declarable> byName(List<Declarable> declarables) {
        return declarables.stream().filter(d -> d instanceof Queue || d instanceof Exchange)
                .collect(Collectors.toMap(d -> d instanceof Queue q ? "queue:" + q.getName() : "exchange:" + ((Exchange) d).getName(),
                        d -> d));
    }

    @Test
    void theIngestObjectsAreExactlyWhatTheStreamBinderDeclared() {
        // A broker that already has these from the binder rejects a declaration with other arguments
        // (PRECONDITION_FAILED), so they must stay identical.
        List<Declarable> all = new RabbitTopology("quorum").declarables().getDeclarables().stream().toList();
        Map<String, Declarable> named = byName(all);

        Queue ingest = (Queue) named.get("queue:labs64-audit-topic.labs64.io-auditflow");
        assertTrue(ingest.isDurable());
        assertEquals(Map.of("x-dead-letter-exchange", "DLX",
                "x-dead-letter-routing-key", "labs64-audit-topic.labs64.io-auditflow"), ingest.getArguments());
        Queue dlq = (Queue) named.get("queue:labs64-audit-topic.labs64.io-auditflow.dlq");
        assertTrue(dlq.isDurable());
        assertTrue(dlq.getArguments().isEmpty());
        assertEquals(ExchangeTypes.TOPIC, ((Exchange) named.get("exchange:labs64-audit-topic")).getType());
        assertEquals(ExchangeTypes.DIRECT, ((Exchange) named.get("exchange:DLX")).getType());
        assertTrue(all.stream().anyMatch(d -> d instanceof Binding b && b.getDestination().equals(ingest.getName())
                && b.getExchange().equals("labs64-audit-topic") && b.getRoutingKey().equals("#")));
        assertTrue(all.stream().anyMatch(d -> d instanceof Binding b && b.getDestination().equals(dlq.getName())
                && b.getExchange().equals("DLX") && b.getRoutingKey().equals(ingest.getName())));
        // The configured queue type applies to the delivery stage only.
        assertEquals("quorum", ((Queue) named.get("queue:labs64-audit-delivery")).getArguments().get("x-queue-type"));
    }

    @Test
    void destinationsMapToTheExchangesOfTheTopology() {
        assertEquals(new RabbitTopology.Address("labs64-audit-topic", "audit.event"),
                RabbitTopology.address(Destination.ingest()));
        assertEquals(new RabbitTopology.Address("DLX", "labs64-audit-topic.labs64.io-auditflow"),
                RabbitTopology.address(Destination.ingestDlq()));
        assertEquals(new RabbitTopology.Address("labs64-audit-dlx", "acme"),
                RabbitTopology.address(Destination.tenantDlq("acme")));
        assertEquals(new RabbitTopology.Address("", "labs64-audit-delay.30s"),
                RabbitTopology.address(Destination.delay(Duration.ofSeconds(30))));
        // A delay between tiers waits for the next tier, never less.
        assertEquals(new RabbitTopology.Address("", "labs64-audit-delay.120s"),
                RabbitTopology.address(Destination.delay(Duration.ofSeconds(31))));
        assertEquals(new RabbitTopology.Address("", "labs64-audit-delay.10800s"),
                RabbitTopology.address(Destination.delay(Duration.ofHours(9))));
    }

    @Test
    void messagesKeepBodyHeadersAndIdAcrossTheAmqpMapping() {
        BrokerMessage m = DeliveryHeaders.newDelivery("{\"a\":1}", "acme", "archive", "e1", 42);

        Message amqp = RabbitMessageBroker.toAmqp(m);
        BrokerMessage back = RabbitMessageBroker.fromAmqp(amqp);

        assertEquals(MessageProperties.CONTENT_TYPE_JSON, amqp.getMessageProperties().getContentType());
        assertEquals("{\"a\":1}", back.bodyAsString());
        assertEquals("e1/archive", back.messageId());
        assertEquals("archive", back.header(DeliveryHeaders.PIPELINE));
        assertEquals(42L, back.header(DeliveryHeaders.FIRST_ENQUEUED_AT));
    }

    // ---- delivery container --------------------------------------------------------------------

    @Test
    void deliveryContainerGathersABatchForTheConfiguredWindowAndWaitsOnlyTheReceiveTimeoutPerMessage() {
        SimpleMessageListenerContainer container = config.deliveryListenerContainer(mock(ConnectionFactory.class),
                mock(DeliveryWorker.class), 4, 50, Duration.ofSeconds(10), Duration.ofSeconds(60), false);

        DirectFieldAccessor fields = new DirectFieldAccessor(container);
        assertEquals(10_000L, fields.getPropertyValue("receiveTimeout"));
        assertEquals(60_000L, fields.getPropertyValue("batchReceiveTimeout"));
        assertEquals(50, fields.getPropertyValue("batchSize"));
    }

    @Test
    void deliveryContainerHasNoWindowByDefault() {
        SimpleMessageListenerContainer container = config.deliveryListenerContainer(mock(ConnectionFactory.class),
                mock(DeliveryWorker.class), 4, 50, Duration.ofSeconds(1), Duration.ZERO, false);

        assertEquals(0L, new DirectFieldAccessor(container).getPropertyValue("batchReceiveTimeout"));
    }

    private static Message amqp(long tag) {
        Message m = RabbitMessageBroker.toAmqp(DeliveryHeaders.newDelivery("{}", "acme", "archive", "e" + tag, 0));
        m.getMessageProperties().setDeliveryTag(tag);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDeliveryBatchIsAckedOnceEverythingIsSettled() throws Exception {
        DeliveryWorker worker = mock(DeliveryWorker.class);
        Channel channel = mock(Channel.class);

        RabbitBrokerConfiguration.onDeliveryBatch(worker, List.of(amqp(7), amqp(8)), channel);

        ArgumentCaptor<List<BrokerMessage>> batch = ArgumentCaptor.forClass(List.class);
        verify(worker).process(batch.capture());
        assertEquals("e8", batch.getValue().get(1).header(DeliveryHeaders.EVENT_ID));
        verify(channel).basicAck(8, true);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ifAnOutcomeCannotBeStoredTheWholeDeliveryBatchIsRequeued() throws Exception {
        DeliveryWorker worker = mock(DeliveryWorker.class);
        doThrow(new BrokerPublishException("no broker confirm within timeout")).when(worker).process(any());
        Channel channel = mock(Channel.class);

        RabbitBrokerConfiguration.onDeliveryBatch(worker, List.of(amqp(3)), channel);

        verify(channel).basicNack(3, true, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    // ---- ingest container ----------------------------------------------------------------------

    @Test
    void anIngestMessageIsAckedWhenHandledAndCarriesTheRedeliveredFlag() throws Exception {
        IngestConsumer consumer = mock(IngestConsumer.class);
        Channel channel = mock(Channel.class);
        Message m = amqp(5);
        m.getMessageProperties().setRedelivered(true);

        RabbitBrokerConfiguration.onIngest(consumer, m, channel);

        verify(consumer).consume(any(BrokerMessage.class), org.mockito.ArgumentMatchers.eq(true));
        verify(channel).basicAck(5, false);
    }

    @Test
    void anIngestMessageThatCouldNotBeHandledIsRequeued() throws Exception {
        IngestConsumer consumer = mock(IngestConsumer.class);
        doThrow(new BrokerPublishException("nacked")).when(consumer).consume(any(), anyBoolean());
        Channel channel = mock(Channel.class);

        RabbitBrokerConfiguration.onIngest(consumer, amqp(6), channel);

        verify(channel).basicNack(6, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
