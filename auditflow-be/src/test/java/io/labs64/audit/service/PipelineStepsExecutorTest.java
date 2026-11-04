package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.AuditFlowConfiguration.StepProperties;
import io.labs64.audit.config.AuditFlowConfiguration.TransformerProperties;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.service.PipelineExecutor.Outcome;
import io.labs64.audit.service.SinkService.EntryResult;
import io.labs64.audit.tenant.EnvSecretRefResolver;
import reactor.core.publisher.Mono;

/** Pipelines with {@code steps}: order, the context between steps, and where a failed delivery resumes. */
@ExtendWith(MockitoExtension.class)
class PipelineStepsExecutorTest {

    @Mock
    private TransformationService transformationService;
    @Mock
    private SinkService sinkService;

    private final ObjectMapper mapper = new ObjectMapper();
    private PipelineExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new PipelineExecutor(transformationService, sinkService, new EnvSecretRefResolver(), mapper);
    }

    private JsonNode event(String id) throws Exception {
        return mapper.readTree("{\"eventId\":\"" + id + "\",\"tenantId\":\"acme\"}");
    }

    private static StepProperties step(String name, String sink, String transformer) {
        StepProperties s = new StepProperties();
        s.setName(name);
        if (transformer != null) {
            TransformerProperties t = new TransformerProperties();
            t.setName(transformer);
            s.setTransformer(t);
        }
        SinkProperties sp = new SinkProperties();
        sp.setName(sink);
        sp.setProperties(Map.of());
        s.setSink(sp);
        return s;
    }

    /** original: zero -> s3, then index: t2 -> ch */
    private static PipelineProperties archive() {
        PipelineProperties p = new PipelineProperties();
        p.setName("archive");
        p.setEnabled(true);
        p.setSteps(List.of(step("original", "s3", "zero"), step("index", "ch", "t2")));
        return p;
    }

    private static String sinkAnswer(String key) {
        return "{\"status\":\"success\",\"sink\":\"s3\",\"result\":{\"sent\":true,\"context\":{\"key\":\"" + key + "\"}}}";
    }

    private ObjectNode context(String key) {
        ObjectNode c = mapper.createObjectNode();
        c.putObject("original").put("key", key);
        return c;
    }

    private static EntryResult ok(JsonNode context) {
        return new EntryResult(true, false, null, context);
    }

    // --- one event ---

    @Test
    void stepsRunInOrderAndTheNextTransformersSeeWhatTheFirstSinkReturned() throws Exception {
        when(sinkService.sendToSink(any(), eq("s3"), any())).thenReturn(Mono.just(sinkAnswer("k1")));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendToSink(any(), eq("ch"), any())).thenReturn(Mono.just("ok"));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.SUCCESS, outcome.kind());
        assertNull(outcome.progress());
        InOrder order = inOrder(sinkService, transformationService);
        ArgumentCaptor<JsonNode> original = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(sinkService).sendToSink(original.capture(), eq("s3"), any());
        ArgumentCaptor<JsonNode> toTransform = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(transformationService).transform(toTransform.capture(), eq("t2"));
        order.verify(sinkService).sendToSink(any(), eq("ch"), any());
        assertFalse(original.getValue().has("context"), "the first step writes the event as it arrived");
        assertEquals("k1", toTransform.getValue().path("context").path("original").path("key").asText());
        assertEquals("e1", toTransform.getValue().path("eventId").asText());
    }

    @Test
    void aFailureInTheFirstStepRecordsNoProgressAndRunsNothingAfterIt() throws Exception {
        when(sinkService.sendToSink(any(), eq("s3"), any()))
                .thenReturn(Mono.error(new RetryableDeliveryException("s3 down")));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.RETRYABLE, outcome.kind());
        assertNull(outcome.progress());
        verify(sinkService, never()).sendToSink(any(), eq("ch"), any());
    }

    @Test
    void aFailureInTheSecondStepReportsHowFarItGot() throws Exception {
        when(sinkService.sendToSink(any(), eq("s3"), any())).thenReturn(Mono.just(sinkAnswer("k1")));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendToSink(any(), eq("ch"), any()))
                .thenReturn(Mono.error(new RetryableDeliveryException("ch down")));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.RETRYABLE, outcome.kind());
        assertEquals(1, outcome.progress().stepsDone());
        assertEquals(context("k1"), outcome.progress().context());
    }

    @Test
    void aResumedDeliveryStartsAtItsStepAndNeverRepeatsTheFinishedOne() throws Exception {
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendToSink(any(), eq("ch"), any())).thenReturn(Mono.just("ok"));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme", new StepProgress(1, context("k1"))).block();

        assertEquals(Outcome.Kind.SUCCESS, outcome.kind());
        verify(sinkService, never()).sendToSink(any(), eq("s3"), any());
        ArgumentCaptor<JsonNode> toTransform = ArgumentCaptor.forClass(JsonNode.class);
        verify(transformationService).transform(toTransform.capture(), eq("t2"));
        assertEquals("k1", toTransform.getValue().path("context").path("original").path("key").asText());
    }

    @Test
    void aContextOverTheCapIsPoisonAndTheNextStepNeverRuns() throws Exception {
        String big = "x".repeat(PipelineExecutor.MAX_CONTEXT_BYTES);
        when(sinkService.sendToSink(any(), eq("s3"), any())).thenReturn(Mono.just(sinkAnswer(big)));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.POISON, outcome.kind());
        assertTrue(outcome.error().contains("more than " + PipelineExecutor.MAX_CONTEXT_BYTES));
        verify(sinkService, never()).sendToSink(any(), eq("ch"), any());
    }

    @Test
    void aContextThatIsNotAJsonObjectIsPoison() throws Exception {
        when(sinkService.sendToSink(any(), eq("s3"), any()))
                .thenReturn(Mono.just("{\"status\":\"success\",\"result\":{\"context\":\"k1\"}}"));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.POISON, outcome.kind());
        verify(sinkService, never()).sendToSink(any(), eq("ch"), any());
    }

    @Test
    void aStepWhoseSinkReturnsNoContextLeavesTheEventWithoutOne() throws Exception {
        when(sinkService.sendToSink(any(), eq("s3"), any())).thenReturn(Mono.just("{\"status\":\"success\"}"));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendToSink(any(), eq("ch"), any())).thenReturn(Mono.just("ok"));

        Outcome outcome = executor.execute(archive(), event("e1"), "acme").block();

        assertEquals(Outcome.Kind.SUCCESS, outcome.kind());
        ArgumentCaptor<JsonNode> toTransform = ArgumentCaptor.forClass(JsonNode.class);
        verify(transformationService).transform(toTransform.capture(), eq("t2"));
        assertFalse(toTransform.getValue().has("context"));
    }

    // --- a batch ---

    @Test
    void aBatchMakesOneSinkCallPerStepAndKeepsEachEventsOwnContext() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s3"), any()))
                .thenReturn(Mono.just(List.of(ok(context("k1").get("original")), ok(context("k2").get("original")))));
        when(transformationService.transform(any(), eq("t2"))).thenAnswer(inv -> Mono.just(
                "{\"archive_key\":\"" + ((JsonNode) inv.getArgument(0)).path("context").path("original").path("key").asText() + "\"}"));
        when(sinkService.sendBatchToSink(anyList(), eq("ch"), any()))
                .thenReturn(Mono.just(List.of(EntryResult.OK, EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(archive(), List.of(event("e1"), event("e2")), "acme").block();

        assertEquals(List.of(Outcome.Kind.SUCCESS, Outcome.Kind.SUCCESS), outcomes.stream().map(Outcome::kind).toList());
        verify(sinkService, times(1)).sendBatchToSink(anyList(), eq("s3"), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> rows = ArgumentCaptor.forClass(List.class);
        verify(sinkService, times(1)).sendBatchToSink(rows.capture(), eq("ch"), any());
        assertEquals(List.of("k1", "k2"), rows.getValue().stream().map(r -> r.path("archive_key").asText()).toList());
    }

    @Test
    void inABatchAnEventFailingTheFirstStepNeverReachesTheSecond() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s3"), any())).thenReturn(Mono.just(List.of(
                ok(context("k1").get("original")), new EntryResult(false, true, "SlowDown"))));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendBatchToSink(anyList(), eq("ch"), any())).thenReturn(Mono.just(List.of(EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(archive(), List.of(event("e1"), event("e2")), "acme").block();

        assertEquals(Outcome.Kind.SUCCESS, outcomes.get(0).kind());
        assertEquals(Outcome.Kind.RETRYABLE, outcomes.get(1).kind());
        assertNull(outcomes.get(1).progress(), "nothing finished for it, so it starts over");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> rows = ArgumentCaptor.forClass(List.class);
        verify(sinkService).sendBatchToSink(rows.capture(), eq("ch"), any());
        assertEquals(1, rows.getValue().size());
    }

    @Test
    void inABatchEachEventStartsAtItsOwnStep() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s3"), any()))
                .thenReturn(Mono.just(List.of(ok(context("k1").get("original")))));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendBatchToSink(anyList(), eq("ch"), any()))
                .thenReturn(Mono.just(List.of(EntryResult.OK, EntryResult.OK)));

        List<Outcome> outcomes = executor.executeBatch(archive(), List.of(event("e1"), event("e2")), "acme",
                List.of(StepProgress.START, new StepProgress(1, context("k2")))).block();

        assertEquals(List.of(Outcome.Kind.SUCCESS, Outcome.Kind.SUCCESS), outcomes.stream().map(Outcome::kind).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JsonNode>> originals = ArgumentCaptor.forClass(List.class);
        verify(sinkService).sendBatchToSink(originals.capture(), eq("s3"), any());
        assertEquals(List.of("e1"), originals.getValue().stream().map(e -> e.path("eventId").asText()).toList());
    }

    @Test
    void inABatchASecondStepFailureCarriesEachEventsOwnContext() throws Exception {
        when(sinkService.sendBatchToSink(anyList(), eq("s3"), any()))
                .thenReturn(Mono.just(List.of(ok(context("k1").get("original")), ok(context("k2").get("original")))));
        when(transformationService.transform(any(), eq("t2"))).thenReturn(Mono.just("{\"row\":1}"));
        when(sinkService.sendBatchToSink(anyList(), eq("ch"), any()))
                .thenReturn(Mono.error(new RetryableDeliveryException("ch down")));

        List<Outcome> outcomes = executor.executeBatch(archive(), List.of(event("e1"), event("e2")), "acme").block();

        assertEquals(Outcome.Kind.RETRYABLE, outcomes.get(0).kind());
        assertEquals(new StepProgress(1, context("k1")), outcomes.get(0).progress());
        assertEquals(new StepProgress(1, context("k2")), outcomes.get(1).progress());
    }
}
