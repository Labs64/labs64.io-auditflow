package io.labs64.audit.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.tenant.SecretRefResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs ONE pipeline (transformer stages, then the sink with its optional fallback) for one event or a
 * batch of events, and classifies each event's outcome. Never throws for a delivery failure: the
 * caller ({@link io.labs64.audit.delivery.DeliveryWorker}) decides between retry, defer and DLQ.
 */
@Service
public class PipelineExecutor {

    private static final Logger logger = LoggerFactory.getLogger(PipelineExecutor.class);

    /**
     * Concurrent transformer calls while preparing ONE batch. Batches already run side by side
     * (delivery unit-concurrency), so a high value here only overruns the transformer's bulkhead.
     */
    private static final int TRANSFORM_CONCURRENCY = 4;

    /** Result of delivering one event through one pipeline. */
    public record Outcome(Kind kind, String error) {
        /** THROTTLED: local backpressure refused the call; defer without spending an attempt. */
        public enum Kind { SUCCESS, POISON, RETRYABLE, THROTTLED }

        public static final Outcome SUCCESS = new Outcome(Kind.SUCCESS, null);

        static Outcome of(Throwable e) {
            if (e instanceof PoisonDeliveryException) {
                return new Outcome(Kind.POISON, e.getMessage());
            }
            if (e instanceof io.labs64.audit.exception.ThrottledDeliveryException) {
                return new Outcome(Kind.THROTTLED, e.getMessage());
            }
            // Anything not explicitly poison is retryable: redeliver rather than risk a silent drop.
            return new Outcome(Kind.RETRYABLE, e.getMessage());
        }
    }

    private final TransformationService transformationService;
    private final SinkService sinkService;
    private final SecretRefResolver secretRefResolver;
    private final ObjectMapper objectMapper;

    public PipelineExecutor(TransformationService transformationService, SinkService sinkService,
                            SecretRefResolver secretRefResolver, ObjectMapper objectMapper) {
        this.transformationService = transformationService;
        this.sinkService = sinkService;
        this.secretRefResolver = secretRefResolver;
        this.objectMapper = objectMapper;
    }

    /** Deliver one event through the pipeline. */
    public Mono<Outcome> execute(PipelineProperties pipeline, JsonNode event, String tenantId) {
        // Mono.defer: a synchronous throw while assembling the chain (argument validation, secret
        // resolution) becomes an error signal of THIS delivery only.
        return Mono.defer(() -> applyTransformers(pipeline, event)
                        .flatMap(transformed -> sendWithFallback(pipeline.getSink(), pipeline.getName(), tenantId, transformed)))
                // A chain that completes without a sink response did not deliver anything: never count
                // it as delivered (an empty Mono used to slip through as success).
                .switchIfEmpty(Mono.error(new RetryableDeliveryException(
                        "Pipeline '" + pipeline.getName() + "' produced no sink response")))
                .thenReturn(Outcome.SUCCESS)
                .onErrorResume(e -> Mono.just(classify(pipeline.getName(), e)));
    }

    /**
     * Deliver several events of the same tenant through the pipeline with ONE sink call. Transformer
     * failures settle their own event; the rest go to the sink's batch endpoint, which answers per
     * event. On a retryable failure the configured fallback sink gets the affected events, also in one
     * call. The result list has one outcome per input event, in input order.
     */
    public Mono<List<Outcome>> executeBatch(PipelineProperties pipeline, List<JsonNode> events, String tenantId) {
        int n = events.size();
        Outcome[] outcomes = new Outcome[n];
        JsonNode[] transformed = new JsonNode[n];

        Mono<Void> transformAll = Flux.range(0, n)
                .flatMap(i -> Mono.defer(() -> applyTransformers(pipeline, events.get(i)))
                        .doOnNext(t -> transformed[i] = t)
                        .onErrorResume(e -> {
                            outcomes[i] = classify(pipeline.getName(), e);
                            return Mono.empty();
                        }), TRANSFORM_CONCURRENCY)
                .then();

        return transformAll
                .then(Mono.defer(() -> sendBatch(pipeline.getSink(), pipeline.getName(), tenantId, transformed, outcomes)))
                .then(Mono.defer(() -> {
                    SinkProperties fallback = pipeline.getSink().getFallback();
                    if (fallback == null || !StringUtils.hasText(fallback.getName())) {
                        return Mono.empty();
                    }
                    // Second chance on the fallback sink for retryable failures only.
                    JsonNode[] retry = new JsonNode[n];
                    Outcome[] retryOutcomes = new Outcome[n];
                    boolean any = false;
                    for (int i = 0; i < n; i++) {
                        if (outcomes[i] != null && outcomes[i].kind() == Outcome.Kind.RETRYABLE && transformed[i] != null) {
                            retry[i] = transformed[i];
                            any = true;
                        } else {
                            retryOutcomes[i] = outcomes[i];
                        }
                    }
                    if (!any) {
                        return Mono.empty();
                    }
                    logger.warn("Pipeline '{}' primary sink failed for part of a batch; trying fallback sink '{}'",
                            pipeline.getName(), fallback.getName());
                    return sendBatch(fallback, pipeline.getName(), tenantId, retry, retryOutcomes)
                            .then(Mono.fromRunnable(() -> {
                                for (int i = 0; i < n; i++) {
                                    if (retry[i] != null) {
                                        outcomes[i] = retryOutcomes[i];
                                    }
                                }
                            }));
                }))
                .then(Mono.fromSupplier(() -> {
                    List<Outcome> result = new ArrayList<>(n);
                    for (Outcome o : outcomes) {
                        result.add(o != null ? o : new Outcome(Outcome.Kind.RETRYABLE, "no outcome recorded"));
                    }
                    return result;
                }));
    }

    /** Send the not-yet-settled entries ({@code events[i] != null && outcomes[i] == null}) in one call. */
    private Mono<Void> sendBatch(SinkProperties sink, String pipelineName, String tenantId,
                                 JsonNode[] events, Outcome[] outcomes) {
        List<Integer> indexes = new ArrayList<>();
        List<JsonNode> payload = new ArrayList<>();
        for (int i = 0; i < events.length; i++) {
            if (events[i] != null && outcomes[i] == null) {
                indexes.add(i);
                payload.add(events[i]);
            }
        }
        if (payload.isEmpty()) {
            return Mono.empty();
        }
        return Mono.defer(() -> {
                    Map<String, String> props = resolvedProperties(tenantId, sink);
                    return sinkService.sendBatchToSink(payload, sink.getName(), props);
                })
                .doOnNext(results -> {
                    for (int k = 0; k < indexes.size(); k++) {
                        SinkService.EntryResult r = results.get(k);
                        outcomes[indexes.get(k)] = r.success() ? Outcome.SUCCESS
                                : new Outcome(r.retryable() ? Outcome.Kind.RETRYABLE : Outcome.Kind.POISON, r.error());
                    }
                })
                .onErrorResume(e -> {
                    Outcome failure = classify(pipelineName, e);
                    indexes.forEach(i -> outcomes[i] = failure);
                    return Mono.empty();
                })
                .then();
    }

    /** The sink's properties with secret references resolved; never null (no properties = empty). */
    private Map<String, String> resolvedProperties(String tenantId, SinkProperties sink) {
        Map<String, String> configured = sink.getProperties() == null ? Map.of() : sink.getProperties();
        Map<String, String> resolved = secretRefResolver.resolve(tenantId, configured);
        return resolved == null ? Map.of() : resolved;
    }

    private Mono<JsonNode> applyTransformers(PipelineProperties pipeline, JsonNode eventJson) {
        Mono<JsonNode> chain = Mono.just(eventJson);
        for (AuditFlowConfiguration.TransformerProperties stage : pipeline.getEffectiveTransformers()) {
            if (stage == null || !StringUtils.hasText(stage.getName())) {
                continue;
            }
            String transformerName = stage.getName();
            chain = chain.flatMap(current -> transformationService.transform(current, transformerName)
                    .map(result -> parseTransformerOutput(transformerName, result)));
        }
        return chain;
    }

    private JsonNode parseTransformerOutput(String transformerName, String result) {
        try {
            return objectMapper.readTree(result);
        } catch (Exception e) {
            // Malformed transformer output will never parse on retry: poison.
            throw new PoisonDeliveryException("Transformer '" + transformerName
                    + "' returned invalid JSON: " + e.getMessage(), e);
        }
    }

    /** Primary sink; on a RETRYABLE failure the configured fallback sink. Poison is not retried. */
    private Mono<String> sendWithFallback(SinkProperties sink, String pipelineName, String tenantId, JsonNode event) {
        // ${secretRef:...} placeholders resolve from THIS tenant's secret store only; a resolution
        // failure is retryable, never a blank or another tenant's credential.
        Mono<String> primary = Mono
                .fromSupplier(() -> resolvedProperties(tenantId, sink))
                .flatMap(props -> sinkService.sendToSink(event, sink.getName(), props));
        SinkProperties fallback = sink.getFallback();
        if (fallback == null || !StringUtils.hasText(fallback.getName())) {
            return primary;
        }
        return primary.onErrorResume(e -> e instanceof RetryableDeliveryException
                && !(e instanceof io.labs64.audit.exception.ThrottledDeliveryException), e -> {
            logger.warn("Pipeline '{}' primary sink '{}' failed ({}); attempting fallback sink '{}'",
                    pipelineName, sink.getName(), e.getMessage(), fallback.getName());
            return Mono.fromSupplier(() -> resolvedProperties(tenantId, fallback))
                    .flatMap(props -> sinkService.sendToSink(event, fallback.getName(), props));
        });
    }

    private Outcome classify(String pipelineName, Throwable e) {
        Outcome outcome = Outcome.of(e);
        if (outcome.kind() == Outcome.Kind.THROTTLED) {
            logger.debug("Pipeline '{}' delivery deferred by backpressure: {}", pipelineName, e.getMessage());
        } else if (outcome.kind() == Outcome.Kind.POISON) {
            logger.error("Pipeline '{}' delivery is poison (not retryable): {}", pipelineName, e.getMessage());
        } else {
            logger.warn("Pipeline '{}' delivery failed (retryable): {}", pipelineName, e.getMessage());
            logger.debug("Retryable error stack trace", e);
        }
        return outcome;
    }
}
