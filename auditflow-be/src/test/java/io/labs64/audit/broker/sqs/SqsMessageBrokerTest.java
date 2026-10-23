package io.labs64.audit.broker.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker.Outgoing;
import io.labs64.audit.broker.MessageBroker.ScanAction;
import io.labs64.audit.delivery.DeliveryHeaders;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;

class SqsMessageBrokerTest {

    private static final String P = "t-";

    private FakeSqs sqs;
    private SqsBrokerProperties properties;
    private SqsMessageBroker broker;

    @BeforeEach
    void setUp() {
        sqs = new FakeSqs().withQueue(P + "ingest").withQueue(P + "delivery").withQueue(P + "ingest-dlq")
                .withQueue(P + "quarantine");
        properties = new SqsBrokerProperties();
        properties.setQueuePrefix(P);
        broker = newBroker();
    }

    private SqsMessageBroker newBroker() {
        return new SqsMessageBroker(sqs, new SqsQueues(sqs, properties), properties, Duration.ofSeconds(5), () -> sqs.now);
    }

    private static Outgoing out(Destination d, String body) {
        return new Outgoing(d, BrokerMessage.of(body));
    }

    @Test
    void messagesAreSentInBatchesOfTenPerQueueAndAllStored() {
        List<Outgoing> out = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            out.add(out(Destination.ingest(), "{\"n\":" + i + "}"));
        }
        out.add(out(Destination.quarantine(), "{bad"));

        List<String> results = broker.publishEach(out);

        assertTrue(results.stream().allMatch(r -> r == null), results.toString());
        assertEquals(23, sqs.stored(P + "ingest").size());
        assertEquals(1, sqs.stored(P + "quarantine").size());
        assertEquals(4, sqs.sends.size(), "3 ingest batches (10+10+3) + 1 quarantine");
    }

    @Test
    void aRejectedEntryFailsOnlyThatMessage() {
        sqs.rejectIds.add("1");

        List<String> results = broker.publishEach(List.of(out(Destination.ingest(), "{}"), out(Destination.ingest(), "{}")));

        assertNull(results.get(0));
        assertTrue(results.get(1).startsWith("rejected by SQS"));
    }

    @Test
    void headersAndMessageIdSurviveTheRoundTrip() {
        BrokerMessage m = DeliveryHeaders.newDelivery("{\"tenantId\":\"acme\"}", "acme", "archive", "e1", 1234567890123L);
        broker.publish(Destination.delivery(), m);

        var received = SqsCodec.decode(broker.receive(broker.url(Destination.delivery()), 10, 1, 30).get(0));

        assertEquals("{\"tenantId\":\"acme\"}", received.message().bodyAsString());
        assertEquals("e1/archive", received.message().messageId());
        assertEquals("archive", received.message().header(DeliveryHeaders.PIPELINE));
        assertEquals(0, DeliveryHeaders.intHeader(received.message(), DeliveryHeaders.ATTEMPTS));
        assertEquals(1234567890123L, DeliveryHeaders.longHeader(received.message(), DeliveryHeaders.FIRST_ENQUEUED_AT, 0));
        assertEquals(1, received.receiveCount());
        assertEquals(0, received.notBefore());
    }

    @Test
    void aBodySqsCannotCarryAsTextTravelsBase64AndArrivesUnchanged() {
        byte[] body = "{\"x\":\"\u0001\"}".getBytes(StandardCharsets.UTF_8);
        broker.publish(Destination.quarantine(), new BrokerMessage(body, Map.of("x-quarantine-reason", "r"), null));

        var received = SqsCodec.decode(broker.receive(broker.url(Destination.quarantine()), 10, 1, 30).get(0));

        assertEquals(new String(body, StandardCharsets.UTF_8), received.message().bodyAsString());
        assertEquals(Map.of("x-quarantine-reason", "r"), received.message().headers());
    }

    @Test
    void aShortDelayIsAnSqsDeliveryDelay() {
        broker.publish(Destination.delay(Duration.ofSeconds(30)), BrokerMessage.of("{}"));

        SendMessageBatchRequestEntry sent = sqs.sends.get(0).entries().get(0);
        assertEquals(30, sent.delaySeconds());
        assertTrue(sqs.sends.get(0).queueUrl().endsWith(P + "delivery"));
        assertEquals(0, SqsCodec.decode(receiveAfter(31_000)).notBefore());
    }

    @Test
    void aDelayBeyondFifteenMinutesWaitsTheMaximumAndCarriesItsDueTime() {
        long now = sqs.now;
        broker.publish(Destination.delay(Duration.ofHours(3)), BrokerMessage.of("{}"));

        assertEquals(900, sqs.sends.get(0).entries().get(0).delaySeconds());
        assertEquals(now + Duration.ofHours(3).toMillis(), SqsCodec.decode(receiveAfter(900_000)).notBefore());
    }

    private software.amazon.awssdk.services.sqs.model.Message receiveAfter(long millis) {
        sqs.advance(millis);
        return broker.receive(broker.url(Destination.delivery()), 10, 1, 30).get(0);
    }

    @Test
    void aMessageOverTheSqsLimitIsNotSent() {
        String big = "{\"x\":\"" + "a".repeat(SqsBrokerProperties.SQS_MAX_MESSAGE_BYTES) + "\"}";

        String result = broker.publishEach(List.of(out(Destination.ingest(), big))).get(0);

        assertTrue(result.startsWith("message too large"), result);
        assertTrue(sqs.sends.isEmpty());
        assertEquals(SqsBrokerProperties.SQS_MAX_MESSAGE_BYTES, broker.maxMessageBytes());
    }

    @Test
    void aMissingTenantDlqIsCreatedEncryptedWithTheConfiguredRetention() {
        broker.ensureTenantDlq("acme");

        Map<QueueAttributeName, String> attributes = sqs.created.get(P + "dlq-acme");
        assertEquals("1209600", attributes.get(QueueAttributeName.MESSAGE_RETENTION_PERIOD));
        assertEquals("true", attributes.get(QueueAttributeName.SQS_MANAGED_SSE_ENABLED));
    }

    @Test
    void withoutCreateQueuesAMissingQueueIsAClearFailure() {
        properties.setCreateQueues(false);
        broker = newBroker();

        assertThrows(IllegalStateException.class, () -> broker.ensureTenantDlq("acme"));
        assertTrue(broker.publishEach(List.of(out(Destination.tenantDlq("globex"), "{}"))).get(0)
                .contains("create-queues is false"));
    }

    @Test
    void scanVisitsEveryMessageOnceRemovesOnlyAckedOnesAndReleasesTheRest() {
        for (int i = 0; i < 25; i++) {
            broker.publish(Destination.ingestDlq(), BrokerMessage.of("{\"n\":" + i + "}"));
        }

        long counted = broker.scan(Destination.ingestDlq(),
                m -> m.bodyAsString().matches(".*\"n\":\\d?[02468]}") ? ScanAction.ACK_COUNTED : ScanAction.KEEP);

        assertEquals(13, counted);
        assertEquals(12, sqs.stored(P + "ingest-dlq").size());
        assertTrue(sqs.stored(P + "ingest-dlq").stream().allMatch(s -> s.visibleAt <= sqs.now),
                "kept messages are visible again right away");
    }

    @Test
    void aFailingVisitorLeavesTheMessageAndPropagates() {
        broker.publish(Destination.ingestDlq(), BrokerMessage.of("{}"));

        assertThrows(IllegalStateException.class, () -> broker.scan(Destination.ingestDlq(), m -> {
            throw new IllegalStateException("replay failed");
        }));

        assertEquals(1, sqs.stored(P + "ingest-dlq").size());
        assertTrue(sqs.stored(P + "ingest-dlq").get(0).visibleAt <= sqs.now);
    }
}
