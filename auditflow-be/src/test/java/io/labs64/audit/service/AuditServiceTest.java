package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.AuditFlowConfiguration.TransformerProperties;
import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.config.PipelineRateLimiterRegistry;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private QuarantineService quarantineService;

    private AuditService auditService;

    private static final String VALID_MESSAGE =
            "{\"eventId\":\"11111111-1111-1111-1111-111111111111\",\"eventType\":\"api.call\",\"sourceSystem\":\"test\"}";

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        auditService = new AuditService(
                auditFlowConfiguration,
                transformationService,
                sinkService,
                conditionEvaluator,
                idempotencyService,
                quarantineService,
                new ObjectMapper(),
                meterRegistry,
                new ConsumerHealthIndicator(meterRegistry),
                new PipelineRateLimiterRegistry(
                        new io.labs64.audit.config.RateLimitProperties(),
                        io.github.resilience4j.ratelimiter.RateLimiterRegistry.ofDefaults(),
                        meterRegistry
                )
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
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(auditFlowConfiguration.getPipelines()).thenReturn(null);
        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    @Test
    @DisplayName("processAuditEvent with empty pipelines list logs warning and does not throw")
    void shouldSkipWhenPipelinesEmpty() {
        when(idempotencyService.claim(anyString())).thenReturn(true);
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
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(transformationService.transform(any(JsonNode.class), eq("my_transformer"))).thenReturn(Mono.just("{\"transformed\":true}"));
        when(sinkService.sendToSink(any(JsonNode.class), eq("my_sink"), any())).thenReturn(Mono.just("ok"));

        auditService.processAuditEvent(VALID_MESSAGE);

        verify(transformationService).transform(any(JsonNode.class), eq("my_transformer"));
        verify(sinkService).sendToSink(any(JsonNode.class), eq("my_sink"), eq(Map.of()));
    }

    // -------------------------------------------------------------------------
    // Enabled pipeline, condition does NOT match → transformer + sink NOT invoked
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Enabled pipeline with non-matching condition skips transformer and sink")
    void shouldSkipTransformerAndSinkWhenConditionDoesNotMatch() {
        PipelineProperties pipeline = buildPipeline("test-pipeline", true, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(false);

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
        when(idempotencyService.claim(anyString())).thenReturn(true);

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
    }

    // -------------------------------------------------------------------------
    // Retryable failure propagates (for DLQ); other pipelines still run; poison does not
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Retryable failure in one pipeline propagates (for DLQ) while others still run")
    void shouldPropagateRetryableFailureButStillRunOtherPipelines() {
        PipelineProperties failingPipeline = buildPipeline("failing-pipeline", true, "bad_transformer", "my_sink");
        PipelineProperties goodPipeline = buildPipeline("good-pipeline", true, "good_transformer", "my_sink");

        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(failingPipeline, goodPipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(transformationService.transform(any(JsonNode.class), eq("bad_transformer")))
                .thenReturn(Mono.error(new RuntimeException("transformer unavailable")));
        when(transformationService.transform(any(JsonNode.class), eq("good_transformer"))).thenReturn(Mono.just("{\"ok\":true}"));
        when(sinkService.sendToSink(any(JsonNode.class), eq("my_sink"), any())).thenReturn(Mono.just("ok"));

        // Retryable failure must propagate so the broker redelivers / DLQs the event.
        assertThrows(RetryableDeliveryException.class, () -> auditService.processAuditEvent(VALID_MESSAGE));

        // Good pipeline must still be processed
        verify(transformationService).transform(any(JsonNode.class), eq("good_transformer"));
        verify(sinkService).sendToSink(any(JsonNode.class), eq("my_sink"), eq(Map.of()));
        // Claim released for redelivery; event NOT marked processed.
        verify(idempotencyService).release("11111111-1111-1111-1111-111111111111");
        verify(idempotencyService, never()).markProcessed(anyString());
    }

    @Test
    @DisplayName("Poison failure (malformed transform output) does not fail the event")
    void shouldNotFailEventOnPoison() {
        PipelineProperties pipeline = buildPipeline("test-pipeline", true, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(transformationService.transform(any(JsonNode.class), eq("my_transformer")))
                .thenReturn(Mono.just("<<not valid json>>"));

        // Poison is logged/counted but the event succeeds — retrying could never parse it.
        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));

        verify(sinkService, never()).sendToSink(any(JsonNode.class), anyString(), any());
        verify(idempotencyService).markProcessed("11111111-1111-1111-1111-111111111111");
        verify(idempotencyService, never()).release(anyString());
    }

    @Test
    @DisplayName("Pipeline already delivered on a prior attempt is skipped (no duplicate delivery)")
    void shouldSkipAlreadyDeliveredPipelineOnRedelivery() {
        PipelineProperties pipeline = buildPipeline("test-pipeline", true, "my_transformer", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(idempotencyService.isPipelineDone("11111111-1111-1111-1111-111111111111", "test-pipeline"))
                .thenReturn(true);

        auditService.processAuditEvent(VALID_MESSAGE);

        // Already delivered → transformer/sink not called again.
        verifyNoInteractions(transformationService, sinkService);
        verify(idempotencyService).markProcessed("11111111-1111-1111-1111-111111111111");
    }

    // -------------------------------------------------------------------------
    // No transformer configured → sink invoked with original message
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Pipeline with no transformer sends original message to sink")
    void shouldSendOriginalMessageWhenNoTransformerConfigured() {
        PipelineProperties pipeline = buildPipelineNoTransformer("no-transformer-pipeline", "my_sink");
        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(sinkService.sendToSink(any(JsonNode.class), eq("my_sink"), any())).thenReturn(Mono.just("ok"));

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService);
        verify(sinkService).sendToSink(any(JsonNode.class), eq("my_sink"), eq(Map.of()));
    }

    // -------------------------------------------------------------------------
    // Fail-closed quarantine + dedup
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Unparseable message is quarantined and never reaches pipelines")
    void shouldQuarantineUnparseableMessage() {
        auditService.processAuditEvent("{not valid json");

        verify(quarantineService).quarantine(eq("{not valid json"), anyString());
        verifyNoInteractions(transformationService, sinkService, conditionEvaluator, idempotencyService);
    }

    @Test
    @DisplayName("Duplicate eventId (claim refused) is dropped before any pipeline runs")
    void shouldDropDuplicateEvent() {
        when(idempotencyService.claim(anyString())).thenReturn(false);

        auditService.processAuditEvent(VALID_MESSAGE);

        verifyNoInteractions(transformationService, sinkService, conditionEvaluator);
        verify(idempotencyService, never()).markProcessed(anyString());
    }

    // -------------------------------------------------------------------------
    // Declarative routing: multi-stage transforms + fallback sink
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Multi-stage transformers are chained in order, feeding each stage's output forward")
    void shouldChainMultipleTransformersInOrder() {
        PipelineProperties pipeline = new PipelineProperties();
        pipeline.setName("multi");
        pipeline.setEnabled(true);
        pipeline.setCondition(new ConditionProperties());
        TransformerProperties t1 = new TransformerProperties();
        t1.setName("t1");
        TransformerProperties t2 = new TransformerProperties();
        t2.setName("t2");
        pipeline.setTransformers(List.of(t1, t2));
        SinkProperties sink = new SinkProperties();
        sink.setName("my_sink");
        sink.setProperties(Map.of());
        pipeline.setSink(sink);

        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(transformationService.transform(any(JsonNode.class), eq("t1"))).thenReturn(Mono.just("{\"stage\":1}"));
        when(transformationService.transform(any(JsonNode.class), eq("t2"))).thenReturn(Mono.just("{\"stage\":2}"));
        when(sinkService.sendToSink(any(JsonNode.class), eq("my_sink"), any())).thenReturn(Mono.just("ok"));

        auditService.processAuditEvent(VALID_MESSAGE);

        // Stage 2 must receive stage 1's output.
        ArgumentCaptor<JsonNode> stage2Input = ArgumentCaptor.forClass(JsonNode.class);
        verify(transformationService).transform(stage2Input.capture(), eq("t2"));
        org.junit.jupiter.api.Assertions.assertEquals(1, stage2Input.getValue().path("stage").asInt());
        // Sink receives stage 2's output.
        ArgumentCaptor<JsonNode> sinkInput = ArgumentCaptor.forClass(JsonNode.class);
        verify(sinkService).sendToSink(sinkInput.capture(), eq("my_sink"), any());
        org.junit.jupiter.api.Assertions.assertEquals(2, sinkInput.getValue().path("stage").asInt());
    }

    @Test
    @DisplayName("Retryable primary-sink failure falls back to the configured fallback sink")
    void shouldUseFallbackSinkOnRetryableFailure() {
        PipelineProperties pipeline = buildPipeline("fb", true, "my_transformer", "primary_sink");
        SinkProperties fallback = new SinkProperties();
        fallback.setName("fallback_sink");
        fallback.setProperties(Map.of());
        pipeline.getSink().setFallback(fallback);

        when(auditFlowConfiguration.getPipelines()).thenReturn(List.of(pipeline));
        when(idempotencyService.claim(anyString())).thenReturn(true);
        when(conditionEvaluator.evaluate(any(JsonNode.class), any())).thenReturn(true);
        when(transformationService.transform(any(JsonNode.class), eq("my_transformer"))).thenReturn(Mono.just("{\"x\":1}"));
        when(sinkService.sendToSink(any(JsonNode.class), eq("primary_sink"), any()))
                .thenReturn(Mono.error(new RetryableDeliveryException("primary down")));
        when(sinkService.sendToSink(any(JsonNode.class), eq("fallback_sink"), any())).thenReturn(Mono.just("ok-fallback"));

        // Fallback succeeds → event is processed, not failed.
        assertDoesNotThrow(() -> auditService.processAuditEvent(VALID_MESSAGE));

        verify(sinkService).sendToSink(any(JsonNode.class), eq("primary_sink"), any());
        verify(sinkService).sendToSink(any(JsonNode.class), eq("fallback_sink"), any());
        verify(idempotencyService).markProcessed("11111111-1111-1111-1111-111111111111");
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
