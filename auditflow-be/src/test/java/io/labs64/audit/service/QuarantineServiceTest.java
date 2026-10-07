package io.labs64.audit.service;

import java.nio.charset.StandardCharsets;

import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.delivery.BrokerTopology;
import io.labs64.audit.delivery.ConfirmingPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuarantineServiceTest {

    @Mock
    private ConfirmingPublisher publisher;

    private SimpleMeterRegistry meterRegistry;
    private QuarantineService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QuarantineService(publisher, meterRegistry);
    }

    @Test
    @DisplayName("quarantine publishes the raw payload with a broker confirm and counts it")
    void quarantinePublishesConfirmedAndCounts() {
        service.quarantine("{bad json", "parse error");

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(org.mockito.ArgumentMatchers.eq(BrokerTopology.QUARANTINE_EXCHANGE),
                org.mockito.ArgumentMatchers.eq(BrokerTopology.QUARANTINE_ROUTING_KEY), captor.capture());
        Message sent = captor.getValue();
        assertEquals("{bad json", new String(sent.getBody(), StandardCharsets.UTF_8));
        assertEquals("parse error", sent.getMessageProperties().getHeader(QuarantineService.REASON_HEADER));
        assertEquals("application/json", sent.getMessageProperties().getContentType());
        assertEquals(MessageDeliveryMode.PERSISTENT, sent.getMessageProperties().getDeliveryMode());
        assertEquals(1.0, meterRegistry.counter("auditflow.events.quarantined").count());
    }

    @Test
    @DisplayName("a quarantine the broker did not store fails the caller and is not counted")
    void unconfirmedQuarantineFails() {
        doThrow(new BrokerPublishException("no broker confirm within timeout"))
                .when(publisher).publish(anyString(), anyString(), any(Message.class));

        assertThrows(BrokerPublishException.class, () -> service.quarantine("{bad json", "parse error"));

        assertEquals(0.0, meterRegistry.counter("auditflow.events.quarantined").count());
    }
}
