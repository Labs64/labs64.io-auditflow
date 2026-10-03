package io.labs64.audit.delivery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.PipelineRateLimiterRegistry;
import io.labs64.audit.service.IdempotencyService;
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
 * Second stage: consumes {@code labs64-audit-delivery} (one message = one event for one pipeline) and
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
 * <p>Messages are received in batches (up to {@code batch-size}, or whatever arrived within
 * {@code batch-receive-timeout}). Events of a pipeline with {@code batch.enabled} go to its sink in one
 * call; the rest are delivered one by one, concurrently. The whole batch is acked only after every
 * outcome is stored (a retry/DLQ publish is confirmed); if storing fails, the batch is requeued and
 * the already delivered entries are skipped on redelivery via their done markers.</p>
 */
@Configuration
public class DeliveryWorker {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryWorker.class);

    /** One unit of work: a message for one pipeline of one tenant, with its parsed event. */
    record Delivery(Message message, String tenantId, String eventId, PipelineProperties pipeline, JsonNode event) {
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

    @Bean
    public SimpleMessageListenerContainer deliveryListenerContainer(
            ConnectionFactory connectionFactory,
            @Value("${auditflow.delivery.concurrency:4}") int concurrency,
            @Value("${auditflow.delivery.batch-size:50}") int batchSize,
            @Value("${auditflow.delivery.batch-receive-timeout:PT1S}") Duration receiveTimeout,
            @Value("${auditflow.delivery.enabled:true}") boolean enabled) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(BrokerTopology.DELIVERY_QUEUE);
        container.setConcurrentConsumers(Math.max(1, concurrency));
        container.setConsumerBatchEnabled(true);
        container.setBatchSize(Math.max(1, batchSize));
        container.setPrefetchCount(Math.max(1, batchSize));
        container.setReceiveTimeout(receiveTimeout.toMillis());
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setShutdownTimeout(25_000);
        container.setMissingQueuesFatal(false);
        container.setAutoStartup(enabled);
        container.setMessageListener((ChannelAwareBatchMessageListener) this::onBatch);
        return container;
    }

    /** Settle every message of the batch, then ack them all; on a storage failure requeue them all. */
    void onBatch(List<Message> messages, Channel channel) {
        if (messages.isEmpty()) {
            return;
        }
        long lastTag = messages.get(messages.size() - 1).getMessageProperties().getDeliveryTag();
        try {
            try {
                process(messages);
            } catch (RuntimeException e) {
                logger.error("Could not settle a delivery batch of {} message(s); requeueing it: {}",
                        messages.size(), e.getMessage(), e);
                channel.basicNack(lastTag, true, true);
                return;
            }
            channel.basicAck(lastTag, true);
        } catch (java.io.IOException e) {
            // The channel is gone: the broker requeues every unacked message of this batch itself.
            throw new org.springframework.amqp.AmqpIOException(e);
        }
    }

    /** Settle every message (deliver, defer, retry or dead-letter). Throws if an outcome cannot be stored. */
    void process(List<Message> messages) {
        Map<String, List<Delivery>> batchGroups = new LinkedHashMap<>();
        List<Delivery> singles = new ArrayList<>();
        for (Message message : messages) {
            resolve(message).ifPresent(d -> {
                if (d.pipeline().isBatchEnabled()) {
                    batchGroups.computeIfAbsent(d.tenantId() + "\u0000" + d.pipeline().getName(), k -> new ArrayList<>()).add(d);
                } else {
                    singles.add(d);
                }
            });
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
    private Optional<Delivery> resolve(Message message) {
        String pipelineName = DeliveryHeaders.string(message, DeliveryHeaders.PIPELINE);
        String headerTenant = TenantIds.resolve(DeliveryHeaders.string(message, DeliveryHeaders.TENANT));
        JsonNode event;
        try {
            event = objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
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
            quarantineService.quarantine(new String(message.getBody(), StandardCharsets.UTF_8),
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
        if (StringUtils.hasText(eventId) && idempotencyService.isPipelineDone(eventId, pipelineName)) {
            count("duplicate", tenantId, pipelineName);
            return Optional.empty();
        }
        if (!rateLimiters.tryAcquirePermission(pipelineName)) {
            deliveryQueue.defer(message);
            count("deferred", tenantId, pipelineName);
            return Optional.empty();
        }
        return Optional.of(new Delivery(message, tenantId, eventId, pipeline.get(), event));
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
                    for (int i = 0; i < chunk.size(); i++) {
                        settle(chunk.get(i), outcomes.get(i));
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
        String pipelineName = d.pipeline().getName();
        switch (outcome.kind()) {
            case SUCCESS -> {
                if (StringUtils.hasText(d.eventId())) {
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
