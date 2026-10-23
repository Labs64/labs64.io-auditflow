package io.labs64.audit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.InMemoryBroker;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.delivery.DeadLetterPublisher;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DlqEndpointTest {

    private static final Destination LEGACY = Destination.ingestDlq();
    private static final Destination ACME_DLQ = Destination.tenantDlq("acme");

    private InMemoryBroker broker;
    private DlqEndpoint endpoint;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        endpoint = new DlqEndpoint(broker, new ObjectMapper(), new DeadLetterPublisher(broker, new SimpleMeterRegistry()),
                new DeliveryQueue(broker));
    }

    /** A legacy (whole-event) DLQ message; its tenant is in the body. */
    private BrokerMessage legacy(String tenantId) {
        BrokerMessage m = BrokerMessage.of("{\"eventId\":\"e\",\"tenantId\":\"" + tenantId + "\"}");
        m.headers().put(IngestConsumer.EXCEPTION_MESSAGE, "routing failed");
        return m;
    }

    /** A per-pipeline DLQ entry in the tenant's own queue. */
    private static BrokerMessage entry(String pipeline, String reason, int n) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(DeliveryHeaders.PIPELINE, pipeline);
        headers.put(DeliveryHeaders.DLQ_REASON, reason);
        headers.put(DeliveryHeaders.EVENT_ID, "e" + n);
        headers.put(DeliveryHeaders.ATTEMPTS, 3);
        return new BrokerMessage("{\"tenantId\":\"acme\"}".getBytes(), headers, "e" + n + "/" + pipeline);
    }

    @Test
    void inspectCountsTheTenantQueueAndItsLegacyShareWithoutRemovingAnything() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1)).put(ACME_DLQ, entry("archive", "ATTEMPTS_EXHAUSTED", 2))
                .put(ACME_DLQ, entry("siem", "POISON", 3));
        broker.put(LEGACY, legacy("acme")).put(LEGACY, legacy("globex"));

        Map<String, Object> info = endpoint.getDlqInfo("acme");

        assertEquals(4L, info.get("messageCount"));
        assertEquals(1L, info.get("legacyMessageCount"));
        assertEquals(Map.of("archive", 2, "siem", 1), info.get("byPipeline"));
        assertEquals(Map.of("POISON", 2, "ATTEMPTS_EXHAUSTED", 1), info.get("byReason"));
        assertEquals(3, broker.messages(ACME_DLQ).size());
        assertEquals(2, broker.messages(LEGACY).size());
        assertTrue(broker.tenantDlqs().contains("acme"));
    }

    @Test
    void replayReEnqueuesEachEntryAsAFreshDeliveryAndForwardsOnlyTheTenantsLegacyEvents() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1));
        broker.put(LEGACY, legacy("acme")).put(LEGACY, legacy("globex"));

        Map<String, Object> result = endpoint.retry("acme", null);

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("retriedCount"));
        assertEquals(1, result.get("legacyRetriedCount"));
        assertTrue(broker.messages(ACME_DLQ).isEmpty());
        BrokerMessage replayed = broker.messages(Destination.delivery()).get(0);
        assertEquals("archive", replayed.header(DeliveryHeaders.PIPELINE));
        assertEquals(0, replayed.header(DeliveryHeaders.ATTEMPTS));
        assertNull(replayed.header(DeliveryHeaders.DLQ_REASON));
        BrokerMessage reingested = broker.messages(Destination.ingest()).get(0);
        assertTrue(reingested.bodyAsString().contains("acme"));
        assertNull(reingested.header(IngestConsumer.EXCEPTION_MESSAGE), "a fresh attempt, not the old error");
        assertEquals(1, broker.messages(LEGACY).size(), "globex untouched");
        assertTrue(broker.messages(LEGACY).get(0).bodyAsString().contains("globex"));
    }

    @Test
    void replayCanBeLimitedToOnePipelineAndThenLeavesLegacyEventsAlone() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1)).put(ACME_DLQ, entry("siem", "POISON", 2));
        broker.put(LEGACY, legacy("acme"));

        Map<String, Object> result = endpoint.retry("acme", "siem");

        assertEquals(1, result.get("retriedCount"));
        assertEquals("siem", broker.messages(Destination.delivery()).get(0).header(DeliveryHeaders.PIPELINE));
        assertEquals(List.of("archive"), broker.messages(ACME_DLQ).stream()
                .map(m -> m.header(DeliveryHeaders.PIPELINE)).toList());
        assertEquals(1, broker.messages(LEGACY).size());
    }

    @Test
    void aFailedReplayPublishLeavesTheEntryInTheDlq() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1));
        broker.failWith("nacked");

        Map<String, Object> result = endpoint.retry("acme", null);

        assertEquals("error", result.get("status"));
        assertEquals(1, broker.messages(ACME_DLQ).size());
    }

    @Test
    void purgeDiscardsOnlyTheRequestingTenantsEntriesAndNeverForwards() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1));
        broker.put(LEGACY, legacy("acme")).put(LEGACY, legacy("globex"));

        Map<String, Object> result = endpoint.purge("acme", null);

        assertEquals(2, result.get("purgedCount"));
        assertTrue(broker.messages(ACME_DLQ).isEmpty());
        assertEquals(1, broker.messages(LEGACY).size());
        assertTrue(broker.published().isEmpty());
    }

    @Test
    void tenantlessLegacyMessagesBelongToThePlatformBucket() {
        broker.put(LEGACY, BrokerMessage.of("{\"eventId\":\"e\"}"));

        assertEquals(1L, endpoint.getDlqInfo("_platform").get("messageCount"));
    }

    @Test
    void anInvalidTenantIdIsRejectedWithoutTouchingTheBroker() {
        Map<String, Object> result = endpoint.getDlqInfo("../../etc");

        assertEquals("error", result.get("status"));
        assertTrue(broker.tenantDlqs().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectWithALimitReturnsEntriesWithContentAndStillRemovesNothing() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1)).put(ACME_DLQ, entry("siem", "ATTEMPTS_EXHAUSTED", 2))
                .put(ACME_DLQ, entry("archive", "POISON", 3));
        broker.put(LEGACY, legacy("acme"));

        Map<String, Object> info = endpoint.getDlqInfo("acme", 2, null);

        var entries = (List<Map<String, Object>>) info.get("entries");
        assertEquals(2, entries.size());
        assertEquals("e1", entries.get(0).get("eventId"));
        assertEquals("archive", entries.get(0).get("pipeline"));
        assertEquals("POISON", entries.get(0).get("reason"));
        assertEquals(3, entries.get(0).get("attempts"));
        assertEquals("acme", ((Map<String, Object>) entries.get(0).get("event")).get("tenantId"));
        assertEquals(4L, info.get("messageCount"), "counts still cover everything");
        assertEquals(3, broker.messages(ACME_DLQ).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyEntriesShowTheRoutingError() {
        broker.put(LEGACY, legacy("acme"));

        var entries = (List<Map<String, Object>>) endpoint.getDlqInfo("acme", 10, null).get("entries");

        assertEquals(true, entries.get(0).get("legacy"));
        assertEquals("routing failed", entries.get(0).get("lastError"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectCanFilterEntriesByPipelineAndCapsTheLimit() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1)).put(ACME_DLQ, entry("siem", "POISON", 2));
        broker.put(LEGACY, legacy("acme"));

        var entries = (List<Map<String, Object>>) endpoint.getDlqInfo("acme", 1000, "siem").get("entries");

        assertEquals(1, entries.size());
        assertEquals("siem", entries.get(0).get("pipeline"));
    }

    @Test
    void withoutALimitNoContentIsReturned() {
        broker.put(ACME_DLQ, entry("archive", "POISON", 1));

        assertFalse(endpoint.getDlqInfo("acme").containsKey("entries"));
    }
}
