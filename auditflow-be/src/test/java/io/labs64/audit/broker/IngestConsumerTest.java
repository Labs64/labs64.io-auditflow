package io.labs64.audit.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.service.AuditService;

/** The ingest semantics every adapter shares: route, retry in place, then the ingest DLQ. */
class IngestConsumerTest {

    private AuditService auditService;
    private ConsumerHealthIndicator health;
    private InMemoryBroker broker;
    private IngestConsumer consumer;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        health = mock(ConsumerHealthIndicator.class);
        broker = new InMemoryBroker();
        consumer = new IngestConsumer(auditService, broker, health, 3, Duration.ofMillis(1));
    }

    @Test
    void aRoutedMessageIsDone() {
        consumer.consume(BrokerMessage.of("{}"), false);

        verify(auditService).processAuditEvent("{}", false);
        assertTrue(broker.published().isEmpty());
    }

    @Test
    void theRedeliveredFlagIsPassedOn() {
        consumer.consume(BrokerMessage.of("{}"), true);

        verify(auditService).processAuditEvent("{}", true);
    }

    @Test
    void aFailureIsRetriedThenMovedToTheIngestDlqWithItsError() {
        doThrow(new IllegalStateException("redis down")).when(auditService).processAuditEvent(anyString(), anyBoolean());

        consumer.consume(new BrokerMessage("{\"eventId\":\"e\"}".getBytes(), null, "e"), false);

        verify(auditService, times(3)).processAuditEvent(anyString(), anyBoolean());
        BrokerMessage dead = broker.messages(Destination.ingestDlq()).get(0);
        assertEquals("{\"eventId\":\"e\"}", dead.bodyAsString());
        assertEquals("redis down", dead.header(IngestConsumer.EXCEPTION_MESSAGE));
        assertEquals("e", dead.messageId());
    }

    @Test
    void aSuccessfulRetryNeverReachesTheDlq() {
        doThrow(new IllegalStateException("blip")).doNothing().when(auditService)
                .processAuditEvent(anyString(), anyBoolean());

        consumer.consume(BrokerMessage.of("{}"), false);

        verify(auditService, times(2)).processAuditEvent(anyString(), anyBoolean());
        assertTrue(broker.published().isEmpty());
    }

    @Test
    void ifTheDlqDoesNotStoreItTheMessageMustStayOnTheIngestQueue() {
        doThrow(new IllegalStateException("redis down")).when(auditService).processAuditEvent(anyString(), anyBoolean());
        broker.failWith("no broker confirm within timeout");

        assertThrows(BrokerPublishException.class, () -> consumer.consume(BrokerMessage.of("{}"), false));
    }

    @Test
    void duringShutdownAFailureLeavesTheMessageForAnotherPod() {
        doThrow(new IllegalStateException("shutting down")).when(auditService).processAuditEvent(anyString(), anyBoolean());
        when(health.isShutdownRequested()).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> consumer.consume(BrokerMessage.of("{}"), false));

        verify(auditService, times(1)).processAuditEvent(anyString(), anyBoolean());
        assertTrue(broker.published().isEmpty());
    }
}
