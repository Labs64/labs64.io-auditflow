package io.labs64.audit.service;

import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.AuditFlowConfiguration.TransformerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditFlowConfiguration auditFlowConfiguration;

    @Mock
    private TransformationService transformationService;

    @Mock
    private SinkService sinkService;

    @Mock
    private ConditionEvaluator conditionEvaluator;

    private AuditService auditService;

    private static final String VALID_MESSAGE = "{\"eventType\":\"api.call\",\"sourceSystem\":\"test\"}";

    @BeforeEach
    void setUp() {
        auditService = new AuditService(
                auditFlowConfiguration,
                transformationService,
                sinkService,
                conditionEvaluator
        );
    }

    // -------------------------------------------------------------------------
    // Null / empty message guard
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processAuditEvent(null) logs warning and does not throw")
    void shouldSkipNullMessage() {
        assertDoesNotThrow(() -> auditService.processAuditEvent(null));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    @Test
    @DisplayName("processAuditEvent(\"\") logs warning and does not throw")
    void shouldSkipEmptyMessage() {
        assertDoesNotThrow(() -> auditService.processAuditEvent(""));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    @Test
    @DisplayName("processAuditEvent with blank message logs warning and does not throw")
    void shouldSkipBlankMessage() {
        assertDoesNotThrow(() -> auditService.processAuditEvent("   "));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    // -------------------------------------------------------------------------
    // No pipelines configured
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("processAuditEvent with null pipelines logs warning and does not throw")
    void shouldSkipWhenPipelinesNull() {
        when(auditFlowConfiguration.getPipelines()).thenReturn(null);
        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    @Test
    @DisplayName("processAuditEvent with empty pipelines list logs warning and does not throw")
    void shouldSkipWhenPipelinesEmpty() {
        when(auditFlowConfiguration.getPipelines()).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    // -------------------------------------------------------------------------
    // Enabled pipeline, condition matches → transformer + sink invoked
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Enabled pipeline with matching condition invokes transformer and sink")
    void shouldInvokeTransformerAndSinkWhenConditionMatches() {
        PipelineProperties pipeline = buildPipeline("test-pipeline", true, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(conditionEvaluator.evaluate(eq(VALID_MESSAGE), any())).thenReturn(true);
        when(transformationService.transform(VALID_MESSAGE, "my_transformer")).thenReturn("{\"transformed\":true}");
        when(sinkService.sendToSink(anyString(), eq("my_sink"), any())).thenReturn("ok");

        auditService.processAuditEvent(VALID_MESSAGE);

        verify(transformationService).transform(VALID_MESSAGE, "my_transformer");
        verify(sinkService).sendToSink("{\"transformed\":true}", "my_sink", Map.of());
    }

    // -------------------------------------------------------------------------
    // Enabled pipeline, condition does NOT match → transformer + sink NOT invoked
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Enabled pipeline with non-matching condition skips transformer and sink")
    void shouldSkipTransformerAndSinkWhenConditionDoesNotMatch() {
        PipelineProperties pipeline = buildPipeline("test-pipeline", true, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(conditionEvaluator.evaluate(eq(VALID_MESSAGE), any())).thenReturn(false);

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService, sinkService);
    }

    // -------------------------------------------------------------------------
    // Disabled pipeline → transformer + sink NOT invoked
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Disabled pipeline is skipped entirely")
    void shouldSkipDisabledPipeline() {
        PipelineProperties pipeline = buildPipeline("disabled-pipeline", false, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    // -------------------------------------------------------------------------
    // Pipeline throws → exception logged, remaining pipelines still processed
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Exception in one pipeline is caught and remaining pipelines are still processed")
    void shouldContinueProcessingAfterPipelineFailure() {
        PipelineProperties failingPipeline = buildPipeline("failing-pipeline", true, "bad_transformer", "my_sink");
        PipelineProperties goodPipeline = buildPipeline("good-pipeline", true, "good_transformer", "my_sink");

        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(failingPipeline, goodPipeline));
        when(conditionEvaluator.evaluate(eq(VALID_MESSAGE), any())).thenReturn(true);
        when(transformationService.transform(VALID_MESSAGE, "bad_transformer"))
                .thenThrow(new RuntimeException("transformer unavailable"));
        when(transformationService.transform(VALID_MESSAGE, "good_transformer")).thenReturn("{\"ok\":true}");
        when(sinkService.sendToSink(anyString(), eq("my_sink"), any())).thenReturn("ok");

        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));

        // Good pipeline must still be processed
        verify(transformationService).transform(VALID_MESSAGE, "good_transformer");
        verify(sinkService).sendToSink("{\"ok\":true}", "my_sink", Map.of());
    }

    // -------------------------------------------------------------------------
    // No transformer configured → sink invoked with original message
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Pipeline with no transformer sends original message to sink")
    void shouldSendOriginalMessageWhenNoTransformerConfigured() {
        PipelineProperties pipeline = buildPipelineNoTransformer("no-transformer-pipeline", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(conditionEvaluator.evaluate(eq(VALID_MESSAGE), any())).thenReturn(true);
        when(sinkService.sendToSink(anyString(), eq("my_sink"), any())).thenReturn("ok");

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService);
        verify(sinkService).sendToSink(VALID_MESSAGE, "my_sink", Map.of());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PipelineProperties buildPipeline(String name, boolean enabled, String transformerName, String sinkName) {
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setName(name);
        pipeline.setEnabled(enabled);
        pipeline.setCondition(new ConditionProperties());

        TransformerProperties transformer = new TransformerProperties();
        transformer.setName(transformerName);
        pipeline.setTransformer(transformer);

        SinkProperties sink = new SinkProperties();
        sink.setName(sinkName);
        sink.setProperties(Map.of());
        pipeline.setSink(sink);

        return pipeline;
    }

    private PipelineProperties buildPipelineNoTransformer(String name, String sinkName) {
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setName(name);
        pipeline.setEnabled(true);
        pipeline.setCondition(new ConditionProperties());
        pipeline.setTransformer(null);

        SinkProperties sink = new SinkProperties();
        sink.setName(sinkName);
        sink.setProperties(Map.of());
        pipeline.setSink(sink);

        return pipeline;
    }
}
