package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.AuditFlowConfiguration.TransformerProperties;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.service.PipelineExecutor.Outcome;
import io.labs64.audit.tenant.EnvSecretRefResolver;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PipelineExecutorTest {

    @Mock
    private TransformationService transformationService;
    @Mock
    private SinkService sinkService;

    private final ObjectMapper mapper = new ObjectMapper();
    private PipelineExecutor executor;

    @BeforeEach
    void setUp() {
        // Hermetic env resolver: plain properties pass through, any ${secretRef:...} fails retryable.
        executor = new PipelineExecutor(transformationService, sinkService, new EnvSecretRefResolver(), mapper);
    }

    private JsonNode event(String id) throws Exception {
        return mapper.readTree("{\"eventId\":\"" + id + "\",\"tenantId\":\"acme\"}");
    }

    private static PipelineProperties pipeline(String sink, String... transformers) {
        PipelineProperties p = new PipelineProperties();
        p.setName("p");
        p.setEnabled(true);
        if (transformers.length > 0) {
            p.setTransformers(java.util.Arrays.stream(transformers).map(n -> {
                TransformerProperties t = new TransformerProperties();
                t.setName(n);
                return t;
            }).toList());
        }
        SinkProperties s = new SinkProperties();
        s.setName(sink);
        s.setProperties(Map.of());
        p.setSink(s);
        return p;
    }

    private static PipelineProperties withFallback(PipelineProperties p, String fallbackSink) {
        SinkProperties f = new SinkProperties();
        f.setName(fallbackSink);
        f.setProperties(Map.of());
        p.getSink().setFallback(f);
        return p;
    }

    // --- single event ---

    @Test
    void transformsThenSendsTheTransformedEvent() throws Exception {
        when(transformationService.transform(any(), eq("t1"))).thenReturn(Mono.just("{\"transformed\":true}"));
        when(sinkService.sendToSink(any(), eq("s"), any())).thenReturn(Mono.just("ok"));

        Outcome outcome = executor.execute(pipeline("s", "t1"), event("1"), "acme").block();

        assertEquals(Outcome.Kind.SUCCESS, outcome.kind());
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(sinkService).sendToSink(sent.capture(), eq("s"), eq(Map.of()));
        assertEquals(true, sent.getValue().path("transformed").asBoolean());
    }

    @Test
    void withoutTransformerTheOriginalEventIsSent() throws Exception {
        when(sinkService.sendToSink(any(), eq("s"), any())).thenReturn(Mono.just("ok"));

        executor.execute(pipeline("s"), event("1"), "acme").block();

        verifyNoInteractions(transformationService);
        verify(sinkService).sendToSink(eq(event("1")), eq("s"), any());
    }

    @Test
    void transformersRunInOrder() throws Exception {
        when(transformationService.transform(any(), eq("a"))).thenReturn(Mono.just("{\"step\":\"a\"}"));
        when(transformationService.transform(any(), eq("b"))).thenReturn(Mono.just("{\"step\":\"b\"}"));
        when(sinkService.sendToSink(any(), any(), any())).thenReturn(Mono.just("ok"));

        executor.execute(pipeline("s", "a", "b"), event("1"), "acme").block();

        InOrder order = inOrder(transformationService);
        order.verify(transformationService).transform(any(), eq("a"));
        order.verify(transformationService).transform(eq(mapper.readTree("{\"step\":\"a\"}")), eq("b"));
    }

    @Test
    void invalidTransformerOutputIsPoison() throws Exception {
        when(transformationService.transform(any(), any())).thenReturn(Mono.just("not json"));

        Outcome outcome = executor.execute(pipeline("s", "t1"), event("1"), "acme").block();

        assertEquals(Outcome.Kind.POISON, outcome.kind());
        verifyNoInteractions(sinkService);
    }

    @Test
    void aSink4xxIsPoisonAndA5xxIsRetryable() throws Exception {
        when(sinkService.sendToSink(any(), eq("bad"), any())).thenReturn(Mono.error(new PoisonDeliveryException("400", null)));
        when(sinkService.sendToSink(any(), eq("down"), any())).thenReturn(Mono.error(new RetryableDeliveryException("503", null)));

        assertEquals(Outcome.Kind.POISON, executor.execute(pipeline("bad"), event("1"), "acme").block().kind());
        assertEquals(Outcome.Kind.RETRYABLE, executor.execute(pipeline("down"), event("1"), "acme").block().kind());
    }

    @Test
    void aRetryableFailureFallsBackAndPoisonDoesNot() throws Exception {
        when(sinkService.sendToSink(any(), eq("down"), any())).thenReturn(Mono.error(new RetryableDeliveryException("503", null)));
        when(sinkService.sendToSink(any(), eq("backup"), any())).thenReturn(Mono.just("ok"));
        when(sinkService.sendToSink(any(), eq("bad"), any())).thenReturn(Mono.error(new PoisonDeliveryException("400", null)));

        assertEquals(Outcome.Kind.SUCCESS,
                executor.execute(withFallback(pipeline("down"), "backup"), event("1"), "acme").block().kind());
        assertEquals(Outcome.Kind.POISON,
                executor.execute(withFallback(pipeline("bad"), "backup"), event("2"), "acme").block().kind());
        verify(sinkService).sendToSink(eq(event("1")), eq("backup"), any());
        verify(sinkService, never()).sendToSink(eq(event("2")), eq("backup"), any());
    }

    @Test
    void anUnresolvableSecretRefIsRetryableAndNeverCallsTheSink() throws Exception {
        PipelineProperties p = pipeline("secure");
        p.getSink().setProperties(Map.of("password", "${secretRef:absent}"));

        Outcome outcome = executor.execute(p, event("1"), "acme").block();

        assertEquals(Outcome.Kind.RETRYABLE, outcome.kind());
        verifyNoInteractions(sinkService);
    }

    // --- batch ---

    @Test
    void aBatchIsOneSinkCallWithAResultPerEvent() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s"), any())).thenReturn(Mono.just(List.of(
                SinkService.EntryResult.OK,
                new SinkService.EntryResult(false, false, "bad field"),
                new SinkService.EntryResult(false, true, "timeout"))));

        List<Outcome> outcomes = executor.executeBatch(pipeline("s"), List.of(event("1"), event("2"), event("3")), "acme").block();

        assertEquals(List.of(Outcome.Kind.SUCCESS, Outcome.Kind.POISON, Outcome.Kind.RETRYABLE),
                outcomes.stream().map(Outcome::kind).toList());
        verify(sinkService, never()).sendToSink(any(), any(), any());
    }

    @Test
    void aTransformerFailureSettlesOnlyItsEvent() throws Exception {
        when(transformationService.transform(eq(event("1")), eq("t"))).thenReturn(Mono.just("{\"ok\":1}"));
        when(transformationService.transform(eq(event("2")), eq("t"))).thenReturn(Mono.just("not json"));
        when(sinkService.sendBatchToSink(anyList(), eq("s"), any())).thenReturn(Mono.just(List.of(SinkService.EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(pipeline("s", "t"), List.of(event("1"), event("2")), "acme").block();

        assertEquals(List.of(Outcome.Kind.SUCCESS, Outcome.Kind.POISON), outcomes.stream().map(Outcome::kind).toList());
        ArgumentCaptor<List<JsonNode>> sent = ArgumentCaptor.forClass(List.class);
        verify(sinkService).sendBatchToSink(sent.capture(), eq("s"), any());
        assertEquals(1, sent.getValue().size());
    }

    @Test
    void aFailedBatchCallFailsEveryEventTheSameWay() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s"), any()))
                .thenReturn(Mono.error(new RetryableDeliveryException("connection refused", null)));

        List<Outcome> outcomes = executor.executeBatch(pipeline("s"), List.of(event("1"), event("2")), "acme").block();

        assertEquals(List.of(Outcome.Kind.RETRYABLE, Outcome.Kind.RETRYABLE), outcomes.stream().map(Outcome::kind).toList());
    }

    @Test
    void retryableBatchEntriesGoToTheFallbackInOneCall() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s"), any())).thenReturn(Mono.just(List.of(
                SinkService.EntryResult.OK,
                new SinkService.EntryResult(false, true, "timeout"),
                new SinkService.EntryResult(false, false, "bad field"))));
        when(sinkService.sendBatchToSink(anyList(), eq("backup"), any()))
                .thenReturn(Mono.just(List.of(SinkService.EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(withFallback(pipeline("s"), "backup"),
                List.of(event("1"), event("2"), event("3")), "acme").block();

        assertEquals(List.of(Outcome.Kind.SUCCESS, Outcome.Kind.SUCCESS, Outcome.Kind.POISON),
                outcomes.stream().map(Outcome::kind).toList());
        ArgumentCaptor<List<JsonNode>> fallback = ArgumentCaptor.forClass(List.class);
        verify(sinkService).sendBatchToSink(fallback.capture(), eq("backup"), any());
        assertEquals(List.of(event("2")), fallback.getValue());
    }

    @Test
    void aThrottledCallIsReportedAsThrottledAndNeverFallsBack() throws Exception {
        when(sinkService.sendToSink(any(), eq("busy"), any())).thenReturn(Mono.error(
                new io.labs64.audit.exception.ThrottledDeliveryException("bulkhead full", null)));

        Outcome outcome = executor.execute(withFallback(pipeline("busy"), "backup"), event("1"), "acme").block();

        assertEquals(Outcome.Kind.THROTTLED, outcome.kind());
        verify(sinkService, never()).sendToSink(any(), eq("backup"), any());
    }

    @Test
    void aSinkWithoutPropertiesIsStillCalled() throws Exception {
        // Regression: null properties resolved to null, Mono.fromSupplier(null) completed empty, the sink
        // was never called and the delivery still counted as delivered (a silent loss).
        PipelineProperties p = pipeline("missing");
        p.getSink().setProperties(null);
        when(sinkService.sendToSink(any(), eq("missing"), eq(Map.of()))).thenReturn(Mono.error(new PoisonDeliveryException("404", null)));

        Outcome outcome = executor.execute(p, event("1"), "acme").block();

        assertEquals(Outcome.Kind.POISON, outcome.kind());
        verify(sinkService).sendToSink(any(), eq("missing"), eq(Map.of()));
    }

    @Test
    void anEmptySinkResponseIsNeverCountedAsDelivered() throws Exception {
        when(sinkService.sendToSink(any(), eq("s"), any())).thenReturn(Mono.empty());

        Outcome outcome = executor.execute(pipeline("s"), event("1"), "acme").block();

        assertEquals(Outcome.Kind.RETRYABLE, outcome.kind());
    }

    @Test
    void aBatchSinkWithoutPropertiesGetsAnEmptyMap() throws Exception {
        PipelineProperties p = pipeline("s");
        p.getSink().setProperties(null);
        when(sinkService.sendBatchToSink(anyList(), eq("s"), eq(Map.of()))).thenReturn(Mono.just(List.of(SinkService.EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(p, List.of(event("1")), "acme").block();

        assertEquals(Outcome.Kind.SUCCESS, outcomes.get(0).kind());
    }
}
