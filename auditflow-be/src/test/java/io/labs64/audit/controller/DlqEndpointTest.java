package io.labs64.audit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DlqEndpointTest {

    private static final String DLQ = "labs64-audit-topic.labs64.io-auditflow.dlq";
    private static final String MAIN = "labs64-audit-topic.labs64.io-auditflow";

    private GetResponse msg(String tenantId, long deliveryTag) {
        String body = "{\"eventId\":\"e\",\"tenantId\":\"" + tenantId + "\"}";
        return new GetResponse(new Envelope(deliveryTag, false, "", DLQ),
                new AMQP.BasicProperties.Builder().build(),
                body.getBytes(StandardCharsets.UTF_8), 0);
    }

    /** The template executes the callback against our mocked channel. */
    @SuppressWarnings("unchecked")
    private RabbitTemplate templateOver(Channel channel) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        when(template.execute(any(ChannelCallback.class)))
                .thenAnswer(inv -> ((ChannelCallback<Object>) inv.getArgument(0)).doInRabbit(channel));
        return template;
    }

    @Test
    void replayForwardsMatchingTenantAckAfterForwardAndNacksOthersBack() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.basicGet(eq(DLQ), eq(false)))
                .thenReturn(msg("acme", 1), msg("globex", 2), null);
        DlqEndpoint endpoint = new DlqEndpoint(templateOver(channel), new ObjectMapper());

        var result = endpoint.retry("acme");

        assertEquals(1, result.get("retriedCount"));
        assertEquals(1, result.get("requeuedCount"));
        var order = inOrder(channel);
        // acme -> forwarded to main queue FIRST, acked only after
        order.verify(channel).basicPublish(eq(""), eq(MAIN), any(),
                argThat(b -> new String(b, StandardCharsets.UTF_8).contains("acme")));
        order.verify(channel).basicAck(1, false);
        // globex -> nacked back onto the DLQ (requeue), never published to main
        order.verify(channel).basicNack(2, false, true);
        verify(channel, never()).basicPublish(anyString(), eq(MAIN), any(),
                argThat(b -> new String(b, StandardCharsets.UTF_8).contains("globex")));
    }

    @Test
    void inspectIsANonDestructivePeek() throws Exception {
        Channel channel = mock(Channel.class);
        when(channel.basicGet(eq(DLQ), eq(false)))
                .thenReturn(msg("acme", 1), msg("globex", 2), null);
        DlqEndpoint endpoint = new DlqEndpoint(templateOver(channel), new ObjectMapper());

        var info = endpoint.getDlqInfo("acme");

        assertEquals(1L, info.get("messageCount"));
        verify(channel).basicNack(1, false, true);  // every message returned to the queue
        verify(channel).basicNack(2, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicPublish(anyString(), anyString(), any(), any(byte[].class));
    }

    @Test
    void tenantlessDlqMessagesBelongToThePlatformBucket() throws Exception {
        Channel channel = mock(Channel.class);
        GetResponse tenantless = new GetResponse(new Envelope(7, false, "", DLQ),
                new AMQP.BasicProperties.Builder().build(),
                "{\"eventId\":\"e\"}".getBytes(StandardCharsets.UTF_8), 0);
        when(channel.basicGet(eq(DLQ), eq(false))).thenReturn(tenantless, (GetResponse) null);
        DlqEndpoint endpoint = new DlqEndpoint(templateOver(channel), new ObjectMapper());

        var info = endpoint.getDlqInfo("-");

        assertEquals(1L, info.get("messageCount"));
    }
}
