package io.labs64.audit.broker.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryWorker;

class SqsConsumersTest {

    private static final String P = "t-";

    private FakeSqs sqs;
    private SqsMessageBroker broker;
    private IngestConsumer ingest;
    private DeliveryWorker worker;
    private SqsConsumers consumers;

    @BeforeEach
    void setUp() {
        sqs = new FakeSqs().withQueue(P + "ingest").withQueue(P + "delivery");
        SqsBrokerProperties properties = new SqsBrokerProperties();
        properties.setQueuePrefix(P);
        broker = new SqsMessageBroker(sqs, new SqsQueues(sqs, properties), properties, Duration.ofSeconds(5), () -> sqs.now);
        ingest = mock(IngestConsumer.class);
        worker = mock(DeliveryWorker.class);
        consumers = new SqsConsumers(broker, properties, ingest, worker, 1,
                new SqsConsumers.DeliverySettings(true, 1, 50, Duration.ZERO, Duration.ZERO));
        consumers.running = true;
    }

    @Test
    void aHandledIngestMessageIsDeleted() {
        broker.publish(Destination.ingest(), BrokerMessage.of("{\"eventId\":\"e\"}"));

        consumers.pollIngest();

        verify(ingest).consume(any(BrokerMessage.class), eq(false));
        assertTrue(sqs.stored(P + "ingest").isEmpty());
    }

    @Test
    void aFailedIngestMessageComesBackLaterAsRedelivered() {
        broker.publish(Destination.ingest(), BrokerMessage.of("{}"));
        doThrow(new BrokerPublishException("dlq down")).doNothing().when(ingest).consume(any(), anyBoolean());

        consumers.pollIngest();
        assertEquals(1, sqs.stored(P + "ingest").size());
        consumers.pollIngest();                      // still invisible: backoff
        sqs.advance(5_000);
        consumers.pollIngest();

        verify(ingest).consume(any(BrokerMessage.class), eq(true));
        assertTrue(sqs.stored(P + "ingest").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSettledDeliveryBatchIsDeleted() {
        broker.publish(Destination.delivery(), DeliveryHeaders.newDelivery("{}", "acme", "archive", "e1", 0));
        broker.publish(Destination.delivery(), DeliveryHeaders.newDelivery("{}", "acme", "siem", "e1", 0));

        consumers.pollDelivery();

        ArgumentCaptor<List<BrokerMessage>> batch = ArgumentCaptor.forClass(List.class);
        verify(worker).process(batch.capture());
        assertEquals(List.of("archive", "siem"), batch.getValue().stream()
                .map(m -> m.header(DeliveryHeaders.PIPELINE)).toList());
        assertTrue(sqs.stored(P + "delivery").isEmpty());
    }

    @Test
    void anUnsettledDeliveryBatchIsVisibleAgainAtOnce() {
        broker.publish(Destination.delivery(), DeliveryHeaders.newDelivery("{}", "acme", "archive", "e1", 0));
        doThrow(new BrokerPublishException("no confirm")).when(worker).process(any());

        consumers.pollDelivery();

        assertEquals(1, sqs.stored(P + "delivery").size());
        assertTrue(sqs.stored(P + "delivery").get(0).visibleAt <= sqs.now);
    }

    @Test
    void aDeliveryThatIsNotDueYetIsParkedAgainUntilItIs() {
        broker.publish(Destination.delay(Duration.ofHours(1)), DeliveryHeaders.newDelivery("{}", "acme", "archive", "e1", 0));

        sqs.advance(900_000);                        // first 15-minute hop
        consumers.pollDelivery();
        verify(worker, never()).process(any());
        assertEquals(1, sqs.stored(P + "delivery").size(), "the original is replaced by the parked copy");
        assertEquals(900, sqs.sends.get(1).entries().get(0).delaySeconds());

        sqs.advance(900_000 * 2);
        consumers.pollDelivery();                    // 30 min: parked once more for the last 15
        verify(worker, never()).process(any());

        sqs.advance(900_000);
        consumers.pollDelivery();                    // due
        verify(worker).process(any());
        assertTrue(sqs.stored(P + "delivery").isEmpty());
    }

    @Test
    void messagesReceivedWhileStoppingAreReleasedNotWorkedOn() {
        broker.publish(Destination.ingest(), BrokerMessage.of("{}"));
        consumers.running = false;

        consumers.pollIngest();

        verify(ingest, never()).consume(any(), anyBoolean());
        assertEquals(1, sqs.stored(P + "ingest").size());
        assertTrue(sqs.stored(P + "ingest").get(0).visibleAt <= sqs.now);
    }
}
