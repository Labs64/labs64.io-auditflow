package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.delivery.DeliveryQueue;
import io.labs64.audit.telemetry.NoopBusinessTelemetry;
import io.labs64.audit.tenant.TenantConfig;
import io.labs64.audit.tenant.TenantIds;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** The router: one ingest event becomes one confirmed delivery message per matching pipeline. */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditFlowConfiguration auditFlowConfiguration;
    @Mock
    private ConditionEvaluator conditionEvaluator;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private QuarantineService quarantineService;
    @Mock
    private DeliveryQueue deliveryQueue;

    private TenantPipelineRegistry registry;
    private AuditService auditService;

    private static final String VALID_MESSAGE =
            "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"eventType\":\"api.call\",\"sourceSystem\":\"test\"}";
    private static final String ACME_EVENT =
            "{\"eventId\":\"22222222-2222-2222-2222-222222222222\",\"eventType\":\"security.login\",\"sourceSystem\":\"t\",\"tenantId\":\"acme\"}";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        registry = new TenantPipelineRegistry();
        auditService = new AuditService(auditFlowConfiguration, conditionEvaluator, idempotencyService,
                quarantineService, new ObjectMapper(), meterRegistry, new ConsumerHealthIndicator(meterRegistry),
                new NoopBusinessTelemetry(), registry, deliveryQueue);
        lenient().when(idempotencyService.claim(anyString())).thenReturn(true);
        lenient().when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
    }

    private static PipelineProperties pipeline(String name, boolean enabled) {
        PipelineProperties p = new PipelineProperties();
        p.setName(name);
        p.setEnabled(enabled);
        SinkProperties s = new SinkProperties();
        s.setName("logging_sink");
        s.setProperties(Map.of());
        p.setSink(s);
        return p;
    }

    private void tenant(String id, PipelineProperties... pipelines) {
        registry.upsert(new TenantConfig(id, true, TenantConfig.Quota.DEFAULT, List.of(pipelines)), "test");
    }

    @Test
    void nullEmptyAndBlankMessagesAreSkipped() {
        assertDoesNotThrow(() -> auditService.processAuditEvent(null));
        assertDoesNotThrow(() -> auditService.processAuditEvent(""));
        assertDoesNotThrow(() -> auditService.processAuditEvent("   "));
        verifyNoInteractions(deliveryQueue, conditionEvaluator);
    }

    @Test
    @DisplayName("one delivery message per matching pipeline, enqueued in one confirmed call")
    void enqueuesOneDeliveryPerMatchingPipeline() {
        tenant("acme", pipeline("archive", true), pipeline("siem", true));

        auditService.processAuditEvent(ACME_EVENT);

        verify(deliveryQueue).enqueue(eq(ACME_EVENT), eq("acme"), eq(List.of("archive", "siem")),
                eq("22222222-2222-2222-2222-222222222222"));
        verify(idempotencyService).markProcessed("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void disabledAndNonMatchingPipelinesAreNotEnqueued() {
        PipelineProperties failedOnly = pipeline("failed-only", true);
        AuditFlowConfiguration.ConditionProperties neverMatches = new AuditFlowConfiguration.ConditionProperties();
        failedOnly.setCondition(neverMatches);
        tenant("acme", pipeline("archive", true), pipeline("off", false), failedOnly);
        when(conditionEvaluator.evaluate(any(JsonNode.class), eq(neverMatches))).thenReturn(false);

        auditService.processAuditEvent(ACME_EVENT);

        ArgumentCaptor<List<String>> names = ArgumentCaptor.forClass(List.class);
        verify(deliveryQueue).enqueue(anyString(), eq("acme"), names.capture(), anyString());
        assertEquals(List.of("archive"), names.getValue());
    }

    @Test
    void routesOnlyToTheOwningTenantsPipelines() {
        tenant("acme", pipeline("acme-pipe", true));
        tenant("globex", pipeline("globex-pipe", true));

        auditService.processAuditEvent(ACME_EVENT);

        verify(deliveryQueue).enqueue(anyString(), eq("acme"), eq(List.of("acme-pipe")), anyString());
    }

    @Test
    void tenantlessEventsRouteToThePlatformTenant() {
        tenant(TenantIds.PLATFORM, pipeline("platform-logging", true));

        auditService.processAuditEvent(VALID_MESSAGE);

        verify(deliveryQueue).enqueue(anyString(), eq(TenantIds.PLATFORM), eq(List.of("platform-logging")), anyString());
    }

    @Test
    void anUnprovisionedTenantIsQuarantinedAndNothingIsEnqueued() {
        auditService.processAuditEvent(ACME_EVENT);

        verify(quarantineService).quarantine(eq(ACME_EVENT), contains(AuditService.TENANT_UNRESOLVED));
        verify(deliveryQueue, never()).enqueue(anyString(), anyString(), anyList(), anyString());
    }

    @Test
    void anEmptyPipelineSetIsANoOp() {
        tenant("acme");

        auditService.processAuditEvent(ACME_EVENT);

        verifyNoInteractions(deliveryQueue);
        verify(idempotencyService).markProcessed(anyString());
    }

    @Test
    @DisplayName("a redelivered event does not re-enqueue a pipeline that already delivered")
    void alreadyDeliveredPipelinesAreNotEnqueuedAgain() {
        tenant("acme", pipeline("archive", true), pipeline("siem", true));
        when(idempotencyService.isPipelineDone(anyString(), eq("archive"))).thenReturn(true);

        auditService.processAuditEvent(ACME_EVENT);

        verify(deliveryQueue).enqueue(anyString(), eq("acme"), eq(List.of("siem")), anyString());
    }

    @Test
    @DisplayName("a failed enqueue releases the claim and rethrows so the binder redelivers the event")
    void failedEnqueueReleasesAndRethrows() {
        tenant("acme", pipeline("archive", true));
        doThrow(new BrokerPublishException("no broker confirm within timeout"))
                .when(deliveryQueue).enqueue(anyString(), anyString(), anyList(), anyString());

        assertThrows(BrokerPublishException.class, () -> auditService.processAuditEvent(ACME_EVENT));
        verify(idempotencyService).release("22222222-2222-2222-2222-222222222222");
        verify(idempotencyService, never()).markProcessed(anyString());
    }

    @Test
    void unparseableMessagesAreQuarantined() {
        auditService.processAuditEvent("{not json");

        verify(quarantineService).quarantine(eq("{not json"), contains("Unparseable JSON"));
        verifyNoInteractions(deliveryQueue);
    }

    @Test
    void duplicatesAreDropped() {
        tenant("acme", pipeline("archive", true));
        when(idempotencyService.claim(anyString())).thenReturn(false);

        auditService.processAuditEvent(ACME_EVENT);

        verifyNoInteractions(deliveryQueue);
    }

    @Test
    @DisplayName("a redelivered event whose claim is unfinished (pod killed mid-routing) is routed, not dropped")
    void redeliveryTakesOverAnUnfinishedClaim() {
        tenant("acme", pipeline("archive", true), pipeline("siem", true));
        when(idempotencyService.claim(anyString())).thenReturn(false);
        when(idempotencyService.takeOver(anyString())).thenReturn(true);
        // "archive" was delivered by the attempt that died; only "siem" is enqueued again.
        when(idempotencyService.isPipelineDone("22222222-2222-2222-2222-222222222222", "archive")).thenReturn(true);

        auditService.processAuditEvent(ACME_EVENT, true);

        verify(deliveryQueue).enqueue(eq(ACME_EVENT), eq("acme"), eq(List.of("siem")),
                eq("22222222-2222-2222-2222-222222222222"));
        verify(idempotencyService).markProcessed("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void redeliveryOfADoneEventIsDropped() {
        tenant("acme", pipeline("archive", true));
        when(idempotencyService.claim(anyString())).thenReturn(false);
        when(idempotencyService.takeOver(anyString())).thenReturn(false);

        auditService.processAuditEvent(ACME_EVENT, true);

        verifyNoInteractions(deliveryQueue);
    }

    @Test
    void aFirstDeliveryNeverTakesOverAClaim() {
        tenant("acme", pipeline("archive", true));
        when(idempotencyService.claim(anyString())).thenReturn(false);

        auditService.processAuditEvent(ACME_EVENT, false);

        verify(idempotencyService, never()).takeOver(anyString());
        verifyNoInteractions(deliveryQueue);
    }

    @Test
    void legacyGlobalPipelinesFailStartup() {
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline("legacy", true)));
        assertThrows(IllegalStateException.class, auditService::validateConfiguration);
    }
}
