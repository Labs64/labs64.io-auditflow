package io.labs64.audit.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import io.labs64.audit.config.AuditFlowConfiguration.BatchProperties;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.RetryProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.PipelineRateLimiterRegistry;
import io.labs64.audit.service.IdempotencyService;
import io.labs64.audit.service.PipelineExecutor;
import io.labs64.audit.service.PipelineExecutor.Outcome;
import io.labs64.audit.service.QuarantineService;
import io.labs64.audit.tenant.TenantConcurrencyLimiter;
import io.labs64.audit.tenant.TenantConfig;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class DeliveryWorkerTest {

    @Mock
    private PipelineExecutor executor;
    @Mock
    private DeliveryQueue deliveryQueue;
    @Mock
    private DeadLetterPublisher deadLetters;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private QuarantineService quarantineService;
    @Mock
    private PipelineRateLimiterRegistry rateLimiters;

    private TenantPipelineRegistry registry;
    private TenantConcurrencyLimiter concurrencyLimiter;
    private DeliveryWorker worker;

    @BeforeEach
    void setUp() {
        registry = new TenantPipelineRegistry();
        concurrencyLimiter = new TenantConcurrencyLimiter(16);
        worker = newWorker(concurrencyLimiter);
        lenient().when(rateLimiters.tryAcquirePermission(anyString())).thenReturn(true);
    }

    private DeliveryWorker newWorker(TenantConcurrencyLimiter limiter) {
        return new DeliveryWorker(registry, executor, deliveryQueue, deadLetters, idempotencyService, quarantineService,
                rateLimiters, limiter, new ObjectMapper(), new SimpleMeterRegistry(), 5, Duration.ofHours(24), 8);
    }

    private static PipelineProperties pipeline(String name) {
        PipelineProperties p = new PipelineProperties();
        p.setName(name);
        p.setEnabled(true);
        SinkProperties s = new SinkProperties();
        s.setName("sink");
        s.setProperties(Map.of());
        p.setSink(s);
        return p;
    }

    private void tenant(String id, PipelineProperties... pipelines) {
        registry.upsert(new TenantConfig(id, true, TenantConfig.Quota.DEFAULT, List.of(pipelines)), "test");
    }

    private static Message delivery(String tenant, String pipeline, String eventId, int attempts, long firstEnqueuedAt) {
        Message m = DeliveryHeaders.newDelivery("{\"eventId\":\"" + eventId + "\",\"tenantId\":\"" + tenant + "\"}",
                tenant, pipeline, eventId, firstEnqueuedAt);
        m.getMessageProperties().setHeader(DeliveryHeaders.ATTEMPTS, attempts);
        return m;
    }

    private static Message delivery(String tenant, String pipeline, String eventId) {
        return delivery(tenant, pipeline, eventId, 0, System.currentTimeMillis());
    }

    private void outcome(Outcome outcome) {
        when(executor.execute(any(), any(), anyString())).thenReturn(Mono.just(outcome));
    }

    @Test
    void aDeliveredEventMarksThePipelineDone() {
        tenant("acme", pipeline("archive"));
        outcome(Outcome.SUCCESS);

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(idempotencyService).markPipelineDone("e1", "archive");
        verifyNoInteractions(deliveryQueue, deadLetters);
    }

    @Test
    void poisonIsDeadLetteredImmediately() {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.POISON, "400 bad request"));

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.POISON), eq("400 bad request"));
        verify(deliveryQueue, never()).retryLater(any(), anyInt(), any(), any());
    }

    @Test
    void aRetryableFailureIsParkedWithTheNextAttemptsDelay() {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.RETRYABLE, "503"));

        worker.process(List.of(delivery("acme", "archive", "e1", 2, System.currentTimeMillis())));

        // third failure -> third tier (2 minutes)
        verify(deliveryQueue).retryLater(any(), eq(3), eq("503"), eq(Duration.ofMinutes(2)));
        verifyNoInteractions(deadLetters);
    }

    @Test
    void theLastAllowedAttemptDeadLetters() {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.RETRYABLE, "503"));

        worker.process(List.of(delivery("acme", "archive", "e1", 4, System.currentTimeMillis()))); // default max 5

        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.ATTEMPTS_EXHAUSTED), contains("5 failed attempt"));
        verify(deliveryQueue, never()).retryLater(any(), anyInt(), any(), any());
    }

    @Test
    void thePipelinesOwnRetryPolicyOverridesTheDefault() {
        PipelineProperties p = pipeline("archive");
        RetryProperties retry = new RetryProperties();
        retry.setMaxAttempts(1);
        p.setRetry(retry);
        tenant("acme", p);
        outcome(new Outcome(Outcome.Kind.RETRYABLE, "503"));

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.ATTEMPTS_EXHAUSTED), anyString());
    }

    @Test
    void aDeliveryOlderThanMaxAgeIsDeadLettered() {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.RETRYABLE, "503"));
        long dayAgo = System.currentTimeMillis() - Duration.ofHours(24).toMillis();

        worker.process(List.of(delivery("acme", "archive", "e1", 1, dayAgo)));

        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.MAX_AGE_EXCEEDED), anyString());
    }

    @Test
    void aRateLimitedDeliveryIsDeferredWithoutSpendingAnAttempt() {
        tenant("acme", pipeline("archive"));
        when(rateLimiters.tryAcquirePermission("archive")).thenReturn(false);

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(deliveryQueue).defer(any());
        verifyNoInteractions(executor, deadLetters);
        verify(deliveryQueue, never()).retryLater(any(), anyInt(), any(), any());
    }

    @Test
    void overTheTenantsConcurrencyCapTheDeliveryIsDeferred() {
        TenantConcurrencyLimiter full = mock(TenantConcurrencyLimiter.class);
        when(full.tryAcquire("acme")).thenReturn(false);
        worker = newWorker(full);
        tenant("acme", pipeline("archive"));

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(deliveryQueue).defer(any());
        verifyNoInteractions(executor);
    }

    @Test
    void aRemovedOrDisabledPipelineIsDeadLetteredNotDropped() {
        PipelineProperties off = pipeline("siem");
        off.setEnabled(false);
        tenant("acme", pipeline("archive"), off);

        worker.process(List.of(delivery("acme", "gone", "e1"), delivery("acme", "siem", "e2")));

        verify(deadLetters, times(2)).deadLetter(any(), eq("acme"), eq(DeadLetterReason.PIPELINE_UNAVAILABLE), anyString());
        verifyNoInteractions(executor);
    }

    @Test
    void anOffboardedTenantsDeliveryIsQuarantinedNeverDeadLetteredOrDelivered() {
        worker.process(List.of(delivery("acme", "archive", "e1")));

        verify(quarantineService).quarantine(anyString(), contains("TENANT_UNRESOLVED"));
        verifyNoInteractions(executor, deadLetters);
    }

    @Test
    void theBodyTenantWinsOverTheHeader() {
        tenant("acme", pipeline("archive"));
        tenant("globex", pipeline("archive"));
        outcome(Outcome.SUCCESS);
        Message m = delivery("acme", "archive", "e1");
        m.getMessageProperties().setHeader(DeliveryHeaders.TENANT, "globex");

        worker.process(List.of(m));

        verify(executor).execute(any(), any(), eq("acme"));
    }

    @Test
    void anAlreadyDeliveredPipelineIsSkipped() {
        tenant("acme", pipeline("archive"));
        when(idempotencyService.isPipelineDone("e1", "archive")).thenReturn(true);

        worker.process(List.of(delivery("acme", "archive", "e1")));

        verifyNoInteractions(executor, deliveryQueue, deadLetters);
    }

    @Test
    void anUnparseableDeliveryIsDeadLetteredAsMalformed() {
        Message m = new Message("{nope".getBytes(), DeliveryHeaders.newDelivery("{}", "acme", "archive", "e1", 0)
                .getMessageProperties());

        worker.process(List.of(m));

        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.MALFORMED), anyString());
    }

    @Test
    void batchEnabledPipelinesAreDeliveredInChunksOfMaxSize() {
        PipelineProperties p = pipeline("archive");
        BatchProperties batch = new BatchProperties();
        batch.setEnabled(true);
        batch.setMaxSize(2);
        p.setBatch(batch);
        tenant("acme", p);
        when(executor.executeBatch(any(), anyList(), eq("acme"))).thenAnswer(inv -> {
            int n = ((List<?>) inv.getArgument(1)).size();
            List<Outcome> outcomes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                outcomes.add(Outcome.SUCCESS);
            }
            return Mono.just(outcomes);
        });

        worker.process(List.of(delivery("acme", "archive", "e1"), delivery("acme", "archive", "e2"),
                delivery("acme", "archive", "e3")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> chunks = ArgumentCaptor.forClass(List.class);
        verify(executor, times(2)).executeBatch(any(), chunks.capture(), eq("acme"));
        assertEquals(List.of(2, 1), chunks.getAllValues().stream().map(List::size).sorted((a, b) -> b - a).toList());
        verify(idempotencyService, times(3)).markPipelineDone(anyString(), eq("archive"));
        verify(executor, never()).execute(any(), any(), anyString());
    }

    private static PipelineProperties batchPipeline(String name, int maxSize) {
        PipelineProperties p = pipeline(name);
        BatchProperties batch = new BatchProperties();
        batch.setEnabled(true);
        batch.setMaxSize(maxSize);
        p.setBatch(batch);
        return p;
    }

    private void everyBatchSucceeds() {
        when(executor.executeBatch(any(), anyList(), anyString())).thenAnswer(inv -> {
            int n = ((List<?>) inv.getArgument(1)).size();
            return Mono.just(java.util.Collections.nCopies(n, Outcome.SUCCESS));
        });
    }

    @Test
    void aBatchNeverMixesTenantsEvenWhenThePipelineNamesAreEqual() {
        tenant("acme", batchPipeline("archive", 100));
        tenant("globex", batchPipeline("archive", 100));
        everyBatchSucceeds();

        worker.process(List.of(delivery("acme", "archive", "a1"), delivery("globex", "archive", "g1"),
                delivery("acme", "archive", "a2")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> acme = ArgumentCaptor.forClass(List.class);
        verify(executor, times(1)).executeBatch(any(), acme.capture(), eq("acme"));
        assertEquals(List.of("a1", "a2"), acme.getValue().stream().map(e -> e.path("eventId").asText()).toList());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> globex = ArgumentCaptor.forClass(List.class);
        verify(executor, times(1)).executeBatch(any(), globex.capture(), eq("globex"));
        assertEquals(List.of("g1"), globex.getValue().stream().map(e -> e.path("eventId").asText()).toList());
    }

    @Test
    void batchAndSinglePipelinesOfOneReceivedBatchEachGoTheirOwnWay() {
        PipelineProperties single = pipeline("alerts");
        PipelineProperties sizeOnly = pipeline("size-only");
        BatchProperties notEnabled = new BatchProperties();
        notEnabled.setMaxSize(10);                       // maxSize without enabled: still one by one
        sizeOnly.setBatch(notEnabled);
        tenant("acme", batchPipeline("archive", 100), single, sizeOnly);
        everyBatchSucceeds();
        outcome(Outcome.SUCCESS);

        worker.process(List.of(delivery("acme", "archive", "e1"), delivery("acme", "alerts", "e1"),
                delivery("acme", "archive", "e2"), delivery("acme", "size-only", "e2")));

        verify(executor, times(1)).executeBatch(any(), anyList(), eq("acme"));
        verify(executor, times(2)).execute(any(), any(), eq("acme"));
        verify(idempotencyService, times(2)).markPipelineDone(anyString(), eq("archive"));
        verify(idempotencyService).markPipelineDone("e1", "alerts");
        verify(idempotencyService).markPipelineDone("e2", "size-only");
    }

    @Test
    void everyEventOfABatchIsSettledByItsOwnOutcome() {
        tenant("acme", batchPipeline("archive", 100));
        when(executor.executeBatch(any(), anyList(), eq("acme"))).thenReturn(Mono.just(List.of(
                Outcome.SUCCESS, new Outcome(Outcome.Kind.POISON, "refused"),
                new Outcome(Outcome.Kind.RETRYABLE, "timeout"), new Outcome(Outcome.Kind.THROTTLED, "busy"))));

        worker.process(List.of(delivery("acme", "archive", "ok"), delivery("acme", "archive", "poison"),
                delivery("acme", "archive", "retry"), delivery("acme", "archive", "busy")));

        verify(idempotencyService).markPipelineDone("ok", "archive");
        verify(idempotencyService, times(1)).markPipelineDone(anyString(), anyString());
        verify(deadLetters).deadLetter(any(), eq("acme"), eq(DeadLetterReason.POISON), eq("refused"));
        verify(deliveryQueue).retryLater(any(), eq(1), eq("timeout"), any());
        verify(deliveryQueue, times(1)).defer(any());      // throttled: no attempt spent
    }

    @Test
    void aDeliveredEventIsNotSentAgainInALaterBatch() {
        tenant("acme", batchPipeline("archive", 100));
        lenient().when(idempotencyService.isPipelineDone("done", "archive")).thenReturn(true);
        everyBatchSucceeds();

        worker.process(List.of(delivery("acme", "archive", "done"), delivery("acme", "archive", "new")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.fasterxml.jackson.databind.JsonNode>> sent = ArgumentCaptor.forClass(List.class);
        verify(executor).executeBatch(any(), sent.capture(), eq("acme"));
        assertEquals(List.of("new"), sent.getValue().stream().map(e -> e.path("eventId").asText()).toList());
    }

    @Test
    void theBatchIsAckedOnceEverythingIsSettled() throws Exception {
        tenant("acme", pipeline("archive"));
        outcome(Outcome.SUCCESS);
        Channel channel = mock(Channel.class);
        Message first = delivery("acme", "archive", "e1");
        Message last = delivery("acme", "archive", "e2");
        first.getMessageProperties().setDeliveryTag(7);
        last.getMessageProperties().setDeliveryTag(8);

        worker.onBatch(List.of(first, last), channel);

        verify(channel).basicAck(8, true);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ifAnOutcomeCannotBeStoredTheWholeBatchIsRequeued() throws Exception {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.POISON, "400"));
        doThrow(new BrokerPublishException("no broker confirm within timeout"))
                .when(deadLetters).deadLetter(any(), anyString(), any(), any());
        Channel channel = mock(Channel.class);
        Message m = delivery("acme", "archive", "e1");
        m.getMessageProperties().setDeliveryTag(3);

        worker.onBatch(List.of(m), channel);

        verify(channel).basicNack(3, true, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void aThrottledDeliveryIsDeferredWithoutSpendingAnAttempt() {
        tenant("acme", pipeline("archive"));
        outcome(new Outcome(Outcome.Kind.THROTTLED, "bulkhead full"));

        worker.process(List.of(delivery("acme", "archive", "e1", 3, System.currentTimeMillis())));

        verify(deliveryQueue).defer(any());
        verify(deliveryQueue, never()).retryLater(any(), anyInt(), any(), any());
        verifyNoInteractions(deadLetters);
    }
}
