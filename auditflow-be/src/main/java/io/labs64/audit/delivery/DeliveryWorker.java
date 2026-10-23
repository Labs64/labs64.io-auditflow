package io.labs64.audit.delivery;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.PipelineRateLimiterRegistry;
import io.labs64.audit.service.IdempotencyService;
import io.labs64.audit.service.IdempotencyService.PipelineDelivery;
import io.labs64.audit.service.PipelineExecutor;
import io.labs64.audit.service.PipelineExecutor.Outcome;
import io.labs64.audit.service.QuarantineService;
import io.labs64.audit.tenant.PipelineSet;
import io.labs64.audit.tenant.TenantConcurrencyLimiter;
import io.labs64.audit.tenant.TenantIds;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Second stage: handles the delivery queue (one message = one event for one pipeline) and
 * settles every message exactly one way before it is acked:
 *
 * <ul>
 *   <li><b>delivered</b> — the pipeline is marked done for the event (a duplicate is skipped later);</li>
 *   <li><b>deferred</b> — backpressure (pipeline rate limit, tenant concurrency cap): parked for a short
 *       delay WITHOUT spending an attempt;</li>
 *   <li><b>retried</b> — retryable failure: parked in the delay tier for the next attempt (5s … 3h);</li>
 *   <li><b>dead-lettered</b> — poison, attempts used up, max age reached, or the pipeline is gone: one
 *       entry in the tenant's own DLQ.</li>
 * </ul>
 *
 * <p>The broker adapter receives messages in batches (up to {@code batch-size}, or whatever arrived
 * within {@code batch-receive-timeout}) and hands each batch to {@link #process}. Events of a pipeline
 * with {@code batch.enabled} go to its sink in one call; the rest are delivered one by one,
 * concurrently. The adapter acks the whole batch only after {@link #process} returned, i.e. every
 * outcome is stored (a retry/DLQ publish is confirmed); if it throws, the adapter requeues the batch
 * and the already delivered entries are skipped on redelivery via their done markers.</p>
 */
@Component
public class DeliveryWorker {

    /** One unit of work: a message for one pipeline of one tenant, with its parsed event. */
    record Delivery(BrokerMessage message, String tenantId, String eventId, PipelineProperties pipeline, JsonNode event) {
    }

    private final TenantPipelineRegistry tenantRegistry;
    private final PipelineExecutor executor;
    private final DeliveryQueue deliveryQueue;
    private final DeadLetterPublisher deadLetters;
    private final IdempotencyService idempotencyService;
    private final QuarantineService quarantineService;
    private final PipelineRateLimiterRegistry rateLimiters;
    private final TenantConcurrencyLimiter concurrencyLimiter;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final RetryPolicy defaultPolicy;
    private final int unitConcurrency;

    public DeliveryWorker(TenantPipelineRegistry tenantRegistry, PipelineExecutor executor, DeliveryQueue deliveryQueue,
                          DeadLetterPublisher deadLetters, IdempotencyService idempotencyService,
                          QuarantineService quarantineService, PipelineRateLimiterRegistry rateLimiters,
                          TenantConcurrencyLimiter concurrencyLimiter, ObjectMapper objectMapper,
                          MeterRegistry meterRegistry,
                          @Value("${auditflow.delivery.retry.max-attempts:20}") int defaultMaxAttempts,
                          @Value("${auditflow.delivery.retry.max-age:PT24H}") Duration defaultMaxAge,
                          @Value("${auditflow.delivery.unit-concurrency:16}") int unitConcurrency) {
        this.tenantRegistry = tenantRegistry;
        this.executor = executor;
        this.deliveryQueue = deliveryQueue;
        this.deadLetters = deadLetters;
        this.idempotencyService = idempotencyService;
        this.quarantineService = quarantineService;
        this.rateLimiters = rateLimiters;
        this.concurrencyLimiter = concurrencyLimiter;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.defaultPolicy = new RetryPolicy(defaultMaxAttempts, defaultMaxAge);
        this.unitConcurrency = Math.max(1, unitConcurrency);
    }

    /** Settle every message (deliver, defer, retry or dead-letter). Throws if an outcome cannot be stored. */
    public void process(List<BrokerMessage> messages) {
        List<Delivery> resolved = new ArrayList<>(messages.size());
        for (BrokerMessage message : messages) {
            resolve(message).ifPresent(resolved::add);
        }
        Map<String, List<Delivery>> batchGroups = new LinkedHashMap<>();
        List<Delivery> singles = new ArrayList<>();
        for (Delivery d : notYetDelivered(resolved)) {
            String pipelineName = d.pipeline().getName();
            if (!rateLimiters.tryAcquirePermission(pipelineName)) {
                deliveryQueue.defer(d.message());
                count("deferred", d.tenantId(), pipelineName);
                continue;
            }
            if (d.pipeline().isBatchEnabled()) {
                batchGroups.computeIfAbsent(d.tenantId() + "\u0000" + pipelineName, k -> new ArrayList<>()).add(d);
            } else {
                singles.add(d);
            }
        }

        List<Mono<Void>> units = new ArrayList<>();
        for (Delivery d : singles) {
            units.add(Mono.defer(() -> runSingle(d)));
        }
        for (List<Delivery> group : batchGroups.values()) {
            int maxSize = group.get(0).pipeline().getBatch().getMaxSize();
            for (int from = 0; from < group.size(); from += maxSize) {
                List<Delivery> chunk = group.subList(from, Math.min(group.size(), from + maxSize));
                units.add(Mono.defer(() -> runBatch(chunk)));
            }
        }
        // Units block (concurrency-cap wait, confirmed publishes, Redis): run them on boundedElastic,
        // never on the Netty event loop that completes the transformer/sink calls.
        Flux.fromIterable(units)
                .flatMap(u -> u.subscribeOn(Schedulers.boundedElastic()), unitConcurrency)
                .then()
                .block();
    }

    /** Parse and route one message to its pipeline, or settle it right here (empty result). */
    private Optional<Delivery> resolve(BrokerMessage message) {
        String pipelineName = DeliveryHeaders.string(message, DeliveryHeaders.PIPELINE);
        String headerTenant = TenantIds.resolve(DeliveryHeaders.string(message, DeliveryHeaders.TENANT));
        JsonNode event;
        try {
            event = objectMapper.readTree(message.bodyAsString());
        } catch (Exception e) {
            deadLetters.deadLetter(message, headerTenant, DeadLetterReason.MALFORMED, "Unparseable body: " + e.getMessage());
            return Optional.empty();
        }
        // The body tenant is authoritative (stamped at ingest from the trusted auth context).
        String tenantId = TenantIds.resolve(event.path("tenantId").asText(null));
        if (!StringUtils.hasText(pipelineName)) {
            deadLetters.deadLetter(message, tenantId, DeadLetterReason.MALFORMED, "Missing pipeline header");
            return Optional.empty();
        }
        Optional<PipelineSet> set = tenantRegistry.pipelinesFor(tenantId);
        if (set.isEmpty()) {
            // Offboarded or disabled since routing: never another tenant's sink, never this tenant's DLQ.
            quarantineService.quarantine(message.bodyAsString(),
                    "TENANT_UNRESOLVED: tenant '" + tenantId + "' state=" + tenantRegistry.stateFor(tenantId));
            return Optional.empty();
        }
        Optional<PipelineProperties> pipeline = set.get().pipelines().stream()
                .filter(p -> pipelineName.equals(p.getName()) && p.isEnabled())
                .findFirst();
        if (pipeline.isEmpty()) {
            deadLetters.deadLetter(message, tenantId, DeadLetterReason.PIPELINE_UNAVAILABLE,
                    "Pipeline '" + pipelineName + "' is not configured or disabled for tenant '" + tenantId + "'");
            return Optional.empty();
        }
        String eventId = event.path("eventId").asText(null);
        return Optional.of(new Delivery(message, tenantId, eventId, pipeline.get(), event));
    }

    /** Drops (and counts) the deliveries a previous attempt already completed: one store call for the batch. */
    private List<Delivery> notYetDelivered(List<Delivery> deliveries) {
        List<Delivery> withId = deliveries.stream().filter(d -> StringUtils.hasText(d.eventId())).toList();
        if (withId.isEmpty()) {
            return deliveries;
        }
        List<Boolean> done = idempotencyService.arePipelinesDone(withId.stream()
                .map(d -> new PipelineDelivery(d.eventId(), d.pipeline().getName())).toList());
        Set<Delivery> skip = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (int i = 0; i < withId.size(); i++) {
            if (Boolean.TRUE.equals(done.get(i))) {
                Delivery d = withId.get(i);
                count("duplicate", d.tenantId(), d.pipeline().getName());
                skip.add(d);
            }
        }
        return skip.isEmpty() ? deliveries : deliveries.stream().filter(d -> !skip.contains(d)).toList();
    }

    private Mono<Void> runSingle(Delivery d) {
        if (!concurrencyLimiter.tryAcquire(d.tenantId())) {
            return Mono.fromRunnable(() -> defer(List.of(d)));
        }
        long start = System.nanoTime();
        return executor.execute(d.pipeline(), d.event(), d.tenantId())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(outcome -> {
                    timer(d.pipeline().getName(), outcome.kind().name(), "single", start);
                    settle(d, outcome);
                })
                .doFinally(s -> concurrencyLimiter.release(d.tenantId()))
                .then();
    }

    private Mono<Void> runBatch(List<Delivery> chunk) {
        Delivery first = chunk.get(0);
        // A batch is one call to the sink: it takes one slot of the tenant's in-flight cap.
        if (!concurrencyLimiter.tryAcquire(first.tenantId())) {
            return Mono.fromRunnable(() -> defer(chunk));
        }
        List<JsonNode> events = chunk.stream().map(Delivery::event).toList();
        long start = System.nanoTime();
        return executor.executeBatch(first.pipeline(), events, first.tenantId())
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(outcomes -> {
                    timer(first.pipeline().getName(), "BATCH", "batch", start);
                    meterRegistry.summary("auditflow.delivery.batch.size", "pipeline", first.pipeline().getName())
                            .record(chunk.size());
                    // The sink took the chunk in one call: mark its delivered events in one store call too.
                    List<PipelineDelivery> delivered = new ArrayList<>();
                    for (int i = 0; i < chunk.size(); i++) {
                        Delivery d = chunk.get(i);
                        if (outcomes.get(i).kind() == Outcome.Kind.SUCCESS && StringUtils.hasText(d.eventId())) {
                            delivered.add(new PipelineDelivery(d.eventId(), d.pipeline().getName()));
                        }
                    }
                    idempotencyService.markPipelinesDone(delivered);
                    for (int i = 0; i < chunk.size(); i++) {
                        settle(chunk.get(i), outcomes.get(i), false);
                    }
                })
                .doFinally(s -> concurrencyLimiter.release(first.tenantId()))
                .then();
    }

    private void defer(List<Delivery> deliveries) {
        for (Delivery d : deliveries) {
            deliveryQueue.defer(d.message());
            count("deferred", d.tenantId(), d.pipeline().getName());
        }
    }

    /** Store the outcome of one delivery (throws if the broker does not confirm it). */
    void settle(Delivery d, Outcome outcome) {
        settle(d, outcome, true);
    }

    /** @param markDone false when the caller already marked this delivery done (a batch, in one call) */
    private void settle(Delivery d, Outcome outcome, boolean markDone) {
        String pipelineName = d.pipeline().getName();
        switch (outcome.kind()) {
            case SUCCESS -> {
                if (markDone && StringUtils.hasText(d.eventId())) {
                    idempotencyService.markPipelineDone(d.eventId(), pipelineName);
                }
                count("delivered", d.tenantId(), pipelineName);
                meterRegistry.counter("auditflow.tenant.events", "tenant", d.tenantId(),
                        "provider", String.valueOf(tenantRegistry.providerFor(d.tenantId())),
                        "outcome", "delivered").increment();
            }
            case POISON -> deadLetters.deadLetter(d.message(), d.tenantId(), DeadLetterReason.POISON, outcome.error());
            case THROTTLED -> {
                deliveryQueue.defer(d.message());
                count("deferred", d.tenantId(), pipelineName);
            }
            case RETRYABLE -> {
                RetryPolicy policy = RetryPolicy.of(d.pipeline(), defaultPolicy);
                int failed = DeliveryHeaders.intHeader(d.message(), DeliveryHeaders.ATTEMPTS) + 1;
                long now = System.currentTimeMillis();
                long firstEnqueuedAt = DeliveryHeaders.longHeader(d.message(), DeliveryHeaders.FIRST_ENQUEUED_AT, now);
                Duration delay = RetryPolicy.delayAfter(failed);
                if (failed >= policy.maxAttempts()) {
                    deadLetters.deadLetter(d.message(), d.tenantId(), DeadLetterReason.ATTEMPTS_EXHAUSTED,
                            failed + " failed attempt(s): " + outcome.error());
                } else if (policy.ageExceeded(firstEnqueuedAt, now, delay)) {
                    deadLetters.deadLetter(d.message(), d.tenantId(), DeadLetterReason.MAX_AGE_EXCEEDED,
                            "older than " + policy.maxAge() + " after " + failed + " attempt(s): " + outcome.error());
                } else {
                    deliveryQueue.retryLater(d.message(), failed, outcome.error(), delay);
                    count("retried", d.tenantId(), pipelineName);
                }
            }
        }
    }

    private void timer(String pipeline, String outcome, String mode, long startNanos) {
        meterRegistry.timer("auditflow.pipeline.duration", "pipeline", pipeline, "outcome", outcome, "mode", mode)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void count(String outcome, String tenantId, String pipeline) {
        meterRegistry.counter("auditflow.delivery.outcomes", "tenant", tenantId, "pipeline", pipeline,
                "outcome", outcome).increment();
    }
}
