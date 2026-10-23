package io.labs64.audit.service;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.delivery.BrokerPublishException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QuarantineServiceTest {

    @Mock
    private MessageBroker broker;

    private SimpleMeterRegistry meterRegistry;
    private QuarantineService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QuarantineService(broker, meterRegistry);
    }

    @Test
    @DisplayName("quarantine publishes the raw payload with a broker confirm and counts it")
    void quarantinePublishesConfirmedAndCounts() {
        service.quarantine("{bad json", "parse error");

        ArgumentCaptor<BrokerMessage> captor = ArgumentCaptor.forClass(BrokerMessage.class);
        verify(broker).publish(org.mockito.ArgumentMatchers.eq(Destination.quarantine()), captor.capture());
        BrokerMessage sent = captor.getValue();
        assertEquals("{bad json", sent.bodyAsString());
        assertEquals("parse error", sent.header(QuarantineService.REASON_HEADER));
        assertEquals(1.0, meterRegistry.counter("auditflow.events.quarantined").count());
    }

    @Test
    @DisplayName("a quarantine the broker did not store fails the caller and is not counted")
    void unconfirmedQuarantineFails() {
        doThrow(new BrokerPublishException("no broker confirm within timeout"))
                .when(broker).publish(any(Destination.class), any(BrokerMessage.class));

        assertThrows(BrokerPublishException.class, () -> service.quarantine("{bad json", "parse error"));

        assertEquals(0.0, meterRegistry.counter("auditflow.events.quarantined").count());
    }
}
