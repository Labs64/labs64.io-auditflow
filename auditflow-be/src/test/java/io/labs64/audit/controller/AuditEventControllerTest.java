package io.labs64.audit.controller;

import io.labs64.audit.config.CorrelationIdFilter;
import io.labs64.audit.publisher.AuditPublisherService;
import io.labs64.audit.v1.model.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventControllerTest {

    @Mock
    private AuditPublisherService publisherService;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private AuditEvent newEvent() {
        return new AuditEvent().eventType("user.login").sourceSystem("auth-service");
    }

    @Test
    void returnsGeneratedEventIdInResponseHeader() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = new AuditEventController(publisherService);

        ResponseEntity<String> response = controller.publishEvent(newEvent());

        String headerId = response.getHeaders().getFirst("X-Audit-Event-Id");
        assertNotNull(headerId);
        assertDoesNotThrow(() -> UUID.fromString(headerId));
        assertNotNull(response.getHeaders().getFirst("X-Audit-Received-At"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(publisherService).publishMessage(captor.capture());
        assertEquals(captor.getValue().getTimestamp().toString(),
                response.getHeaders().getFirst("X-Audit-Received-At"));
    }

    @Test
    void preservesClientSuppliedEventIdInHeader() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = new AuditEventController(publisherService);
        UUID clientId = UUID.randomUUID();

        ResponseEntity<String> response = controller.publishEvent(newEvent().eventId(clientId));

        assertEquals(clientId.toString(), response.getHeaders().getFirst("X-Audit-Event-Id"));
    }

    @Test
    void autoPopulatesCorrelationIdFromMdcWhenAbsent() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = new AuditEventController(publisherService);
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-123");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent());
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("corr-123", captor.getValue().getCorrelationId());
    }

    @Test
    void preservesClientSuppliedCorrelationId() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = new AuditEventController(publisherService);
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-mdc");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent().correlationId("corr-client"));
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("corr-client", captor.getValue().getCorrelationId());
    }

    @Test
    void leavesCorrelationIdNullWhenAbsentFromClientAndMdc() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = new AuditEventController(publisherService);
        // MDC is cleared by @AfterEach; ensure it's empty here too
        MDC.clear();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent());
        verify(publisherService).publishMessage(captor.capture());

        assertNull(captor.getValue().getCorrelationId());
    }
}
