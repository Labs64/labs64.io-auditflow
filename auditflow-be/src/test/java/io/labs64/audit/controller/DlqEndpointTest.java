package io.labs64.audit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;

import io.labs64.audit.delivery.DeadLetterPublisher;
import io.labs64.audit.delivery.DeliveryHeaders;
import io.labs64.audit.delivery.DeliveryQueue;

class DlqEndpointTest {

    private static final String LEGACY = DlqEndpoint.LEGACY_DLQ_QUEUE_NAME;
    private static final String INGEST = DlqEndpoint.INGEST_QUEUE_NAME;
    private static final String ACME_DLQ = "labs64-audit-dlq.acme";

    private Channel channel;
    private DeadLetterPublisher deadLetters;
    private DeliveryQueue deliveryQueue;
    private DlqEndpoint endpoint;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        channel = mock(Channel.class);
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(template.execute(any(ChannelCallback.class)))
                .thenAnswer(inv -> ((ChannelCallback<Object>) inv.getArgument(0)).doInRabbit(channel));
        deadLetters = mock(DeadLetterPublisher.class);
        deliveryQueue = mock(DeliveryQueue.class);
        endpoint = new DlqEndpoint(template, new ObjectMapper(), deadLetters, deliveryQueue);
    }

    /** A legacy (whole-event) DLQ message; its tenant is in the body. */
    private GetResponse legacy(String tenantId, long tag) {
        String body = "{\"eventId\":\"e\",\"tenantId\":\"" + tenantId + "\"}";
        return new GetResponse(new Envelope(tag, false, "", LEGACY), new AMQP.BasicProperties.Builder().build(),
                body.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** A per-pipeline DLQ entry in the tenant's own queue. */
    private GetResponse entry(String pipeline, String reason, long tag) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(DeliveryHeaders.PIPELINE, pipeline);
        headers.put(DeliveryHeaders.DLQ_REASON, reason);
        headers.put(DeliveryHeaders.EVENT_ID, "e" + tag);
        return new GetResponse(new Envelope(tag, false, "", ACME_DLQ),
                new AMQP.BasicProperties.Builder().headers(headers).build(),
                "{\"tenantId\":\"acme\"}".getBytes(StandardCharsets.UTF_8), 0);
    }

    @Test
    void inspectCountsTheTenantQueueAndItsLegacyShareWithoutRemovingAnything() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false)))
                .thenReturn(entry("archive", "POISON", 1), entry("archive", "ATTEMPTS_EXHAUSTED", 2),
                        entry("siem", "POISON", 3), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(legacy("acme", 4), legacy("globex", 5), (GetResponse) null);

        Map<String, Object> info = endpoint.getDlqInfo("acme");

        assertEquals(4L, info.get("messageCount"));
        assertEquals(1L, info.get("legacyMessageCount"));
        assertEquals(Map.of("archive", 2, "siem", 1), info.get("byPipeline"));
        assertEquals(Map.of("POISON", 2, "ATTEMPTS_EXHAUSTED", 1), info.get("byReason"));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        for (long tag = 1; tag <= 5; tag++) {
            verify(channel).basicNack(tag, false, true);
        }
        verify(deadLetters).ensureTenantDlq("acme");
    }

    @Test
    void replayReEnqueuesEachEntryBeforeAckingItAndForwardsOnlyTheTenantsLegacyEvents() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false))).thenReturn(entry("archive", "POISON", 1), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(legacy("acme", 2), legacy("globex", 3), (GetResponse) null);

        Map<String, Object> result = endpoint.retry("acme", null);

        assertEquals("success", result.get("status"));
        assertEquals(2, result.get("retriedCount"));
        assertEquals(1, result.get("legacyRetriedCount"));
        InOrder order = inOrder(deliveryQueue, channel);
        order.verify(deliveryQueue).replay(any(Message.class));
        order.verify(channel).basicAck(1, false);
        verify(channel).basicPublish(eq(""), eq(INGEST), any(), any(byte[].class));
        verify(channel).basicAck(2, false);
        verify(channel).basicNack(3, false, true);               // globex: untouched
        verify(channel, never()).basicAck(3, false);
    }

    @Test
    void replayCanBeLimitedToOnePipelineAndThenLeavesLegacyEventsAlone() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false)))
                .thenReturn(entry("archive", "POISON", 1), entry("siem", "POISON", 2), (GetResponse) null);

        Map<String, Object> result = endpoint.retry("acme", "siem");

        assertEquals(1, result.get("retriedCount"));
        ArgumentCaptor<Message> replayed = ArgumentCaptor.forClass(Message.class);
        verify(deliveryQueue).replay(replayed.capture());
        assertEquals("siem", replayed.getValue().getMessageProperties().getHeaders().get(DeliveryHeaders.PIPELINE));
        verify(channel).basicNack(1, false, true);
        verify(channel).basicAck(2, false);
        verify(channel, never()).basicGet(eq(LEGACY), anyBoolean());
    }

    @Test
    void aFailedReplayPublishLeavesTheEntryInTheDlq() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false))).thenReturn(entry("archive", "POISON", 1), (GetResponse) null);
        org.mockito.Mockito.doThrow(new io.labs64.audit.delivery.BrokerPublishException("nacked"))
                .when(deliveryQueue).replay(any());

        Map<String, Object> result = endpoint.retry("acme", null);

        assertEquals("error", result.get("status"));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel).basicNack(1, false, true);
    }

    @Test
    void purgeDiscardsOnlyTheRequestingTenantsEntriesAndNeverForwards() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false))).thenReturn(entry("archive", "POISON", 1), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(legacy("acme", 2), legacy("globex", 3), (GetResponse) null);

        Map<String, Object> result = endpoint.purge("acme", null);

        assertEquals(2, result.get("purgedCount"));
        verify(channel).basicAck(1, false);
        verify(channel).basicAck(2, false);
        verify(channel).basicNack(3, false, true);
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
        verify(deliveryQueue, never()).replay(any());
    }

    @Test
    void tenantlessLegacyMessagesBelongToThePlatformBucket() throws Exception {
        GetResponse tenantless = new GetResponse(new Envelope(7, false, "", LEGACY),
                new AMQP.BasicProperties.Builder().build(), "{\"eventId\":\"e\"}".getBytes(StandardCharsets.UTF_8), 0);
        when(channel.basicGet(eq("labs64-audit-dlq._platform"), eq(false))).thenReturn((GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(tenantless, (GetResponse) null);

        assertEquals(1L, endpoint.getDlqInfo("_platform").get("messageCount"));
    }

    @Test
    void anInvalidTenantIdIsRejectedWithoutTouchingTheBroker() {
        Map<String, Object> result = endpoint.getDlqInfo("../../etc");

        assertEquals("error", result.get("status"));
        verify(deadLetters, never()).ensureTenantDlq(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectWithALimitReturnsEntriesWithContentAndStillRemovesNothing() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false)))
                .thenReturn(entry("archive", "POISON", 1), entry("siem", "ATTEMPTS_EXHAUSTED", 2),
                        entry("archive", "POISON", 3), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(legacy("acme", 4), (GetResponse) null);

        Map<String, Object> info = endpoint.getDlqInfo("acme", 2, null);

        var entries = (java.util.List<Map<String, Object>>) info.get("entries");
        assertEquals(2, entries.size());
        assertEquals("e1", entries.get(0).get("eventId"));
        assertEquals("archive", entries.get(0).get("pipeline"));
        assertEquals("POISON", entries.get(0).get("reason"));
        assertEquals("acme", ((Map<String, Object>) entries.get(0).get("event")).get("tenantId"));
        assertEquals(4L, info.get("messageCount"), "counts still cover everything");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void inspectCanFilterEntriesByPipelineAndCapsTheLimit() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false)))
                .thenReturn(entry("archive", "POISON", 1), entry("siem", "POISON", 2), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn(legacy("acme", 3), (GetResponse) null);

        var entries = (java.util.List<Map<String, Object>>) endpoint.getDlqInfo("acme", 1000, "siem").get("entries");

        assertEquals(1, entries.size());
        assertEquals("siem", entries.get(0).get("pipeline"));
    }

    @Test
    void withoutALimitNoContentIsReturned() throws Exception {
        when(channel.basicGet(eq(ACME_DLQ), eq(false))).thenReturn(entry("archive", "POISON", 1), (GetResponse) null);
        when(channel.basicGet(eq(LEGACY), eq(false))).thenReturn((GetResponse) null);

        assertEquals(false, endpoint.getDlqInfo("acme").containsKey("entries"));
    }
}
