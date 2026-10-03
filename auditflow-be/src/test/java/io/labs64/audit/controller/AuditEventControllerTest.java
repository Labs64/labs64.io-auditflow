package io.labs64.audit.controller;

import io.labs64.audit.config.CorrelationIdFilter;
import io.labs64.audit.exception.TenantNotProvisionedException;
import io.labs64.audit.publisher.AuditPublisherService;
import io.labs64.audit.tenant.TenantGate;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.authcontext.test.WithAuthContext;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditEventControllerTest {

    @Mock
    private AuditPublisherService publisherService;

    /** No-op by default — gate behaviour itself is covered by TenantGateTest. */
    @Mock
    private TenantGate tenantGate;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build();
    private static final jakarta.validation.Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    private AuditEventController controller() {
        return new AuditEventController(publisherService, tenantGate, MAPPER, VALIDATOR);
    }

    private AuditEvent newEvent() {
        return new AuditEvent().eventType("user.login").sourceSystem("auth-service");
    }

    @Test
    void returnsGeneratedEventIdInResponseHeader() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();

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
        AuditEventController controller = controller();
        UUID clientId = UUID.randomUUID();

        ResponseEntity<String> response = controller.publishEvent(newEvent().eventId(clientId));

        assertEquals(clientId.toString(), response.getHeaders().getFirst("X-Audit-Event-Id"));
    }

    @Test
    void autoPopulatesCorrelationIdFromMdcWhenAbsent() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-123");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent());
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("corr-123", captor.getValue().getCorrelationId());
    }

    @Test
    void preservesClientSuppliedCorrelationId() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, "corr-mdc");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent().correlationId("corr-client"));
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("corr-client", captor.getValue().getCorrelationId());
    }

    @Test
    void leavesCorrelationIdNullWhenAbsentFromClientAndMdc() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();
        // MDC is cleared by @AfterEach; ensure it's empty here too
        MDC.clear();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent());
        verify(publisherService).publishMessage(captor.capture());

        assertNull(captor.getValue().getCorrelationId());
    }

    @Test
    @WithAuthContext(user = "jdoe", tenant = "t_100")
    void gatewayTenantOverridesClientSuppliedTenant() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent().tenantId("t_spoofed"));
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("t_100", captor.getValue().getTenantId());
    }

    @Test
    @WithAuthContext(user = "jdoe", tenant = "-")
    void tenantlessContextPreservesClientSuppliedTenant() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent().tenantId("t_client"));
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("t_client", captor.getValue().getTenantId());
    }

    @Test
    void noContextPreservesClientSuppliedTenant() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        controller.publishEvent(newEvent().tenantId("t_client"));
        verify(publisherService).publishMessage(captor.capture());

        assertEquals("t_client", captor.getValue().getTenantId());
    }

    @Test
    void notProvisionedTenantIsRejectedBeforePublish() {
        AuditEventController controller = controller();
        doThrow(new TenantNotProvisionedException("Tenant 'ghost' is not provisioned"))
                .when(tenantGate).check("ghost");

        assertThrows(TenantNotProvisionedException.class,
                () -> controller.publishEvent(newEvent().tenantId("ghost")));
        verify(publisherService, never()).publishMessage(any());
    }

    @Test
    @WithAuthContext(user = "jdoe", tenant = "t_100")
    void gateChecksTheStampedGatewayTenantNotTheClientOne() {
        when(publisherService.publishMessage(any())).thenReturn(true);
        AuditEventController controller = controller();

        controller.publishEvent(newEvent().tenantId("t_spoofed"));

        verify(tenantGate).check("t_100");
    }

    // --- POST /audit/publish/batch ---

    private static java.util.Map<String, Object> entry(String eventType, String eventId) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (eventType != null) {
            m.put("eventType", eventType);
        }
        m.put("sourceSystem", "netlicensing/core");
        if (eventId != null) {
            m.put("eventId", eventId);
        }
        return m;
    }

    private static io.labs64.auditflow.model.AuditEventBatch batch(java.util.Map<String, Object>... entries) {
        return new io.labs64.auditflow.model.AuditEventBatch().events(java.util.List.of(entries));
    }

    @Test
    void batchReturnsOneResultPerEventInOrderAndPublishesOnlyValidOnes() {
        when(tenantGate.tryAcquireQuota(any())).thenReturn(true);
        when(publisherService.publishMessages(any())).thenAnswer(inv ->
                java.util.Collections.nCopies(((java.util.List<?>) inv.getArgument(0)).size(), (String) null));
        String id = UUID.randomUUID().toString();

        var response = controller().publishEvents(batch(
                entry("api.call", id),
                entry(null, null),                    // missing eventType
                entry("api.call", "not-a-uuid"),      // malformed eventId
                entry("api.call", null)));

        var body = response.getBody();
        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, body.getAcceptedCount());
        assertEquals(2, body.getRejectedCount());
        var r = body.getResults();
        assertEquals(java.util.List.of(0, 1, 2, 3), r.stream().map(x -> x.getIndex()).toList());
        assertEquals("ACCEPTED", r.get(0).getStatus().getValue());
        assertEquals(id, r.get(0).getEventId().toString());
        assertEquals(io.labs64.auditflow.model.ErrorCode.VALIDATION_ERROR, r.get(1).getError().getCode());
        assertEquals(io.labs64.auditflow.model.ErrorCode.VALIDATION_ERROR, r.get(2).getError().getCode());
        assertNotNull(r.get(3).getEventId(), "server-generated id is returned");
        assertNull(response.getHeaders().getFirst("Retry-After"));

        ArgumentCaptor<java.util.List<AuditEvent>> published = ArgumentCaptor.forClass(java.util.List.class);
        verify(publisherService).publishMessages(published.capture());
        assertEquals(2, published.getValue().size());
    }

    @Test
    void batchOverQuotaAndUnconfirmedEntriesAreRejectedWithRetryAfter() {
        when(tenantGate.tryAcquireQuota(any())).thenReturn(true, false, true);
        when(publisherService.publishMessages(any())).thenReturn(java.util.Arrays.asList(null, "nacked"));

        var response = controller().publishEvents(batch(
                entry("a", null), entry("b", null), entry("c", null)));

        var r = response.getBody().getResults();
        assertEquals("ACCEPTED", r.get(0).getStatus().getValue());
        assertEquals(io.labs64.auditflow.model.ErrorCode.TENANT_RATE_LIMITED, r.get(1).getError().getCode());
        assertEquals(io.labs64.auditflow.model.ErrorCode.PUBLISH_FAILED, r.get(2).getError().getCode());
        assertNotNull(response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    @WithAuthContext(tenant = "acme")
    void batchStampsTheTrustedTenantOnEveryEvent() {
        when(tenantGate.tryAcquireQuota(any())).thenReturn(true);
        when(publisherService.publishMessages(any())).thenReturn(java.util.Arrays.asList(null, null));
        var spoofed = entry("a", null);
        spoofed.put("tenantId", "globex");

        controller().publishEvents(batch(spoofed, entry("b", null)));

        ArgumentCaptor<java.util.List<AuditEvent>> published = ArgumentCaptor.forClass(java.util.List.class);
        verify(publisherService).publishMessages(published.capture());
        assertEquals(java.util.List.of("acme", "acme"),
                published.getValue().stream().map(AuditEvent::getTenantId).toList());
    }

    @Test
    void batchForAnUnprovisionedTenantIsRejectedWholeWithoutSpendingQuota() {
        doThrow(new TenantNotProvisionedException("nope")).when(tenantGate).checkProvisioned(any());

        assertThrows(TenantNotProvisionedException.class,
                () -> controller().publishEvents(batch(entry("a", null))));
        verify(tenantGate, never()).tryAcquireQuota(any());
        verify(publisherService, never()).publishMessages(any());
    }
}
