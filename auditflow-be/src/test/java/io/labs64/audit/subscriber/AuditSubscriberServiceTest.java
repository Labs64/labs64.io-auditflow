package io.labs64.audit.subscriber;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.support.MessageBuilder;

import io.labs64.audit.service.AuditService;

/** The ingest consumer hands the broker's redelivered flag to the router. */
@ExtendWith(MockitoExtension.class)
class AuditSubscriberServiceTest {

    @Mock
    private AuditService auditService;

    @Test
    void firstDeliveryIsNotMarkedRedelivered() {
        new AuditSubscriberService(auditService).audit().accept(MessageBuilder.withPayload("{}").build());

        verify(auditService).processAuditEvent("{}", false);
    }

    @Test
    void redeliveredFlagIsPassedOn() {
        new AuditSubscriberService(auditService).audit().accept(
                MessageBuilder.withPayload("{}").setHeader(AmqpHeaders.REDELIVERED, true).build());

        verify(auditService).processAuditEvent("{}", true);
    }
}
