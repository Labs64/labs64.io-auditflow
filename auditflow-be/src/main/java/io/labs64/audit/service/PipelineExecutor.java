package io.labs64.audit.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.labs64.audit.config.AuditFlowConfiguration;
import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import io.labs64.audit.config.AuditFlowConfiguration.StepProperties;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.tenant.SecretRefResolver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs ONE pipeline for one event or a batch of events, and classifies each event's outcome. Never
 * throws for a delivery failure: the caller ({@link io.labs64.audit.delivery.DeliveryWorker}) decides
 * between retry, defer and DLQ.
 *
 * <p>A pipeline is a list of steps (transformer stages, then a sink with its optional fallback); one
 * without {@code steps} is a single step. A step runs for an event only after the previous one succeeded
 * for it. In a pipeline with {@code steps}, what a sink returns as {@code context} is kept under the
 * step's name and handed to the next steps' transformers as the event's {@code context} field; a failed
 * delivery reports how far it got ({@link Outcome#progress()}) so the next attempt continues there.
 */
@Service
public class PipelineExecutor {

    private static final Logger logger = LoggerFactory.getLogger(PipelineExecutor.class);

    /**
     * Concurrent transformer calls while preparing ONE batch. Batches already run side by side
     * (delivery unit-concurrency), so a high value here only overruns the transformer's bulkhead.
     */
    private static final int TRANSFORM_CONCURRENCY = 4;

    /** The event field that carries the step context to the transformers. Publishers can't set it (ingest refuses it). */
    public static final String CONTEXT_FIELD = "context";

    /** The most an event's whole step context may take, serialized: it travels in a broker header on every retry. */
    public static final int MAX_CONTEXT_BYTES = 4096;

    /**
     * Result of delivering one event through one pipeline.
     *
     * @param progress for a failure in a pipeline with steps, where the next attempt starts; null for a
     *                 success and for a failure before anything was done
     */
    public record Outcome(Kind kind, String error, StepProgress progress) {
        /** THROTTLED: local backpressure refused the call; defer without spending an attempt. */
        public enum Kind { SUCCESS, POISON, RETRYABLE, THROTTLED }

        public static final Outcome SUCCESS = new Outcome(Kind.SUCCESS, null, null);

        public Outcome(Kind kind, String error) {
            this(kind, error, null);
        }

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

        /** This failure, remembered as having happened at {@code where} (nothing is recorded for a fresh start). */
        Outcome at(StepProgress where) {
            return where == null || where.isStart() ? new Outcome(kind, error, null) : new Outcome(kind, error, where);
        }
    }

    /** One step's result for one event: the context after it, or the failure that stopped it. */
    private record StepResult(ObjectNode context, Outcome failure) {
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

    /** Deliver one event through the pipeline, from its first step. */
    public Mono<Outcome> execute(PipelineProperties pipeline, JsonNode event, String tenantId) {
        return execute(pipeline, event, tenantId, StepProgress.START);
    }

    /** Deliver one event through the pipeline, starting at {@code start.stepsDone()} with {@code start.context()}. */
    public Mono<Outcome> execute(PipelineProperties pipeline, JsonNode event, String tenantId, StepProgress start) {
        List<StepProperties> steps = pipeline.getEffectiveSteps();
        StepProgress from = start == null ? StepProgress.START : start;
        // Mono.defer: a synchronous throw while assembling the chain (argument validation, secret
        // resolution) becomes an error signal of THIS delivery only.
        return Mono.defer(() -> runStep(pipeline, steps, from.stepsDone(), from.context(), event, tenantId));
    }

    private Mono<Outcome> runStep(PipelineProperties pipeline, List<StepProperties> steps, int index, ObjectNode context,
                                  JsonNode event, String tenantId) {
        if (index >= steps.size()) {
            return Mono.just(Outcome.SUCCESS);
        }
        StepProperties step = steps.get(index);
        StepProgress here = new StepProgress(index, context);
        return Mono.defer(() -> applyTransformers(step.getEffectiveTransformers(), withContext(event, context))
                        .flatMap(transformed -> sendWithFallback(step.getSink(), where(pipeline, step), tenantId, transformed)))
                // A chain that completes without a sink response did not deliver anything: never count
                // it as delivered (an empty Mono used to slip through as success).
                .switchIfEmpty(Mono.error(new RetryableDeliveryException(
                        "Pipeline '" + pipeline.getName() + "' produced no sink response")))
                .map(response -> new StepResult(
                        advance(pipeline, steps, index, context, () -> contextOfResponse(step, response)), null))
                .onErrorResume(e -> Mono.just(new StepResult(null, classify(where(pipeline, step), e).at(here))))
                .flatMap(r -> r.failure() != null
                        ? Mono.just(r.failure())
                        : runStep(pipeline, steps, index + 1, r.context(), event, tenantId));
    }

    /**
     * Deliver several events of the same tenant through the pipeline, with ONE sink call per step.
     * Transformer failures settle their own event; the rest go to the step sink's batch endpoint, which
     * answers per event. On a retryable failure the step's fallback sink gets the affected events, also in
     * one call. The result list has one outcome per input event, in input order.
     */
    public Mono<List<Outcome>> executeBatch(PipelineProperties pipeline, List<JsonNode> events, String tenantId) {
        return executeBatch(pipeline, events, tenantId, Collections.nCopies(events.size(), StepProgress.START));
    }

    /** As {@link #executeBatch(PipelineProperties, List, String)}, each event starting at its own progress. */
    public Mono<List<Outcome>> executeBatch(PipelineProperties pipeline, List<JsonNode> events, String tenantId,
                                            List<StepProgress> starts) {
        int n = events.size();
        List<StepProperties> steps = pipeline.getEffectiveSteps();
        Outcome[] outcomes = new Outcome[n];
        ObjectNode[] contexts = new ObjectNode[n];
        int[] next = new int[n];
        int from = steps.size();
        for (int i = 0; i < n; i++) {
            StepProgress start = starts.get(i) == null ? StepProgress.START : starts.get(i);
            next[i] = start.stepsDone();
            contexts[i] = start.context();
            from = Math.min(from, next[i]);
            if (next[i] >= steps.size()) {
                outcomes[i] = Outcome.SUCCESS; // nothing left to do (cannot happen for a message the worker resolved)
            }
        }

        Mono<Void> chain = Mono.empty();
        for (int s = from; s < steps.size(); s++) {
            int index = s;
            chain = chain.then(Mono.defer(() -> runBatchStep(pipeline, steps, index, events, tenantId, next, contexts, outcomes)));
        }
        return chain.then(Mono.fromSupplier(() -> {
            List<Outcome> result = new ArrayList<>(n);
            for (Outcome o : outcomes) {
                result.add(o != null ? o : new Outcome(Outcome.Kind.RETRYABLE, "no outcome recorded"));
            }
            return result;
        }));
    }

    /** Step {@code index} for every event still waiting for it, then advance or settle each one. */
    private Mono<Void> runBatchStep(PipelineProperties pipeline, List<StepProperties> steps, int index,
                                    List<JsonNode> events, String tenantId, int[] next, ObjectNode[] contexts,
                                    Outcome[] outcomes) {
        int n = events.size();
        StepProperties step = steps.get(index);
        JsonNode[] transformed = new JsonNode[n];
        Outcome[] stepOutcomes = new Outcome[n];
        JsonNode[] returned = new JsonNode[n];
        List<Integer> participants = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (outcomes[i] == null && next[i] == index) {
                participants.add(i);
            }
        }
        if (participants.isEmpty()) {
            return Mono.empty();
        }

        Mono<Void> transformAll = Flux.fromIterable(participants)
                .flatMap(i -> Mono.defer(() -> applyTransformers(step.getEffectiveTransformers(), withContext(events.get(i), contexts[i])))
                        .doOnNext(t -> transformed[i] = t)
                        .onErrorResume(e -> {
                            stepOutcomes[i] = classify(where(pipeline, step), e);
                            return Mono.empty();
                        }), TRANSFORM_CONCURRENCY)
                .then();

        return transformAll
                .then(Mono.defer(() -> sendBatch(step.getSink(), where(pipeline, step), tenantId, transformed, stepOutcomes, returned)))
                .then(Mono.defer(() -> sendFallback(step, where(pipeline, step), tenantId, transformed, stepOutcomes, returned)))
                .then(Mono.fromRunnable(() -> {
                    for (int i : participants) {
                        StepProgress here = new StepProgress(index, contexts[i]);
                        Outcome stepOutcome = stepOutcomes[i] != null ? stepOutcomes[i]
                                : new Outcome(Outcome.Kind.RETRYABLE, "no outcome recorded");
                        if (stepOutcome.kind() != Outcome.Kind.SUCCESS) {
                            outcomes[i] = stepOutcome.at(here);
                            continue;
                        }
                        int event = i;
                        try {
                            contexts[i] = advance(pipeline, steps, index, contexts[i], () -> contextOf(step, returned[event]));
                        } catch (RuntimeException e) {
                            outcomes[i] = classify(where(pipeline, step), e).at(here);
                            continue;
                        }
                        next[i] = index + 1;
                        if (next[i] >= steps.size()) {
                            outcomes[i] = Outcome.SUCCESS;
                        }
                    }
                }));
    }

    /** Second chance on the step's fallback sink, for retryable failures only. */
    private Mono<Void> sendFallback(StepProperties step, String pipelineName, String tenantId, JsonNode[] transformed,
                                    Outcome[] stepOutcomes, JsonNode[] returned) {
        SinkProperties fallback = step.getSink().getFallback();
        if (fallback == null || !StringUtils.hasText(fallback.getName())) {
            return Mono.empty();
        }
        int n = transformed.length;
        JsonNode[] retry = new JsonNode[n];
        Outcome[] retryOutcomes = new Outcome[n];
        JsonNode[] retryReturned = new JsonNode[n];
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (stepOutcomes[i] != null && stepOutcomes[i].kind() == Outcome.Kind.RETRYABLE && transformed[i] != null) {
                retry[i] = transformed[i];
                any = true;
            } else {
                retryOutcomes[i] = stepOutcomes[i];
            }
        }
        if (!any) {
            return Mono.empty();
        }
        logger.warn("Pipeline '{}' primary sink failed for part of a batch; trying fallback sink '{}'",
                pipelineName, fallback.getName());
        return sendBatch(fallback, pipelineName, tenantId, retry, retryOutcomes, retryReturned)
                .then(Mono.fromRunnable(() -> {
                    for (int i = 0; i < n; i++) {
                        if (retry[i] != null) {
                            stepOutcomes[i] = retryOutcomes[i];
                            returned[i] = retryReturned[i];
                        }
                    }
                }));
    }

    /** Send the not-yet-settled entries ({@code events[i] != null && outcomes[i] == null}) in one call. */
    private Mono<Void> sendBatch(SinkProperties sink, String pipelineName, String tenantId,
                                 JsonNode[] events, Outcome[] outcomes, JsonNode[] returned) {
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
                        int i = indexes.get(k);
                        outcomes[i] = r.success() ? Outcome.SUCCESS
                                : new Outcome(r.retryable() ? Outcome.Kind.RETRYABLE : Outcome.Kind.POISON, r.error());
                        returned[i] = r.context();
                    }
                })
                .onErrorResume(e -> {
                    Outcome failure = classify(pipelineName, e);
                    indexes.forEach(i -> outcomes[i] = failure);
                    return Mono.empty();
                })
                .then();
    }

    /**
     * The context after step {@code index}: unchanged for a pipeline without steps and after the last
     * step (nothing would read it), else the previous context plus what this step's sink returned under
     * the step's name. Throws {@link PoisonDeliveryException} for a context that is not a JSON object or
     * is larger than {@link #MAX_CONTEXT_BYTES}: retrying would return the same.
     */
    private ObjectNode advance(PipelineProperties pipeline, List<StepProperties> steps, int index, ObjectNode context,
                               java.util.function.Supplier<JsonNode> returned) {
        if (!pipeline.hasSteps() || index >= steps.size() - 1) {
            return context;
        }
        JsonNode value = returned.get();
        if (value == null) {
            return context;
        }
        ObjectNode merged = context == null ? objectMapper.createObjectNode() : context.deepCopy();
        merged.set(steps.get(index).getName(), value);
        int size;
        try {
            size = objectMapper.writeValueAsBytes(merged).length;
        } catch (JsonProcessingException e) {
            throw new PoisonDeliveryException("Step context cannot be serialized: " + e.getMessage(), e);
        }
        if (size > MAX_CONTEXT_BYTES) {
            throw new PoisonDeliveryException("Step context of pipeline '" + pipeline.getName() + "' after step '"
                    + steps.get(index).getName() + "' is " + size + " bytes, more than " + MAX_CONTEXT_BYTES);
        }
        return merged;
    }

    /** The {@code context} a single-event sink call returned (inside its {@code result}), or null. */
    private JsonNode contextOfResponse(StepProperties step, String response) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new PoisonDeliveryException("Sink '" + step.getSink().getName() + "' answered with invalid JSON: "
                    + e.getMessage(), e);
        }
        return contextOf(step, root == null ? null : root.path("result").get(CONTEXT_FIELD));
    }

    private static JsonNode contextOf(StepProperties step, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isObject()) {
            throw new PoisonDeliveryException("Sink '" + step.getSink().getName() + "' returned a context that is not a JSON object");
        }
        return value;
    }

    /**
     * The event as the step's transformers see it: with the context so far, if there is one. {@code context} is
     * reserved for what steps returned: one an event brought along (ingest refuses it, but a message can reach the
     * broker another way) never reaches a transformer as if a step had written it.
     */
    private static JsonNode withContext(JsonNode event, ObjectNode context) {
        if (!(event instanceof ObjectNode object)) {
            return event;
        }
        boolean none = context == null || context.isEmpty();
        if (none && !object.has(CONTEXT_FIELD)) {
            return event;
        }
        ObjectNode copy = object.deepCopy();
        if (none) {
            copy.remove(CONTEXT_FIELD);
        } else {
            copy.set(CONTEXT_FIELD, context);
        }
        return copy;
    }

    /** The sink's properties with secret references resolved; never null (no properties = empty). */
    private Map<String, String> resolvedProperties(String tenantId, SinkProperties sink) {
        Map<String, String> configured = sink.getProperties() == null ? Map.of() : sink.getProperties();
        Map<String, String> resolved = secretRefResolver.resolve(tenantId, configured);
        return resolved == null ? Map.of() : resolved;
    }

    private Mono<JsonNode> applyTransformers(List<AuditFlowConfiguration.TransformerProperties> stages, JsonNode eventJson) {
        Mono<JsonNode> chain = Mono.just(eventJson);
        for (AuditFlowConfiguration.TransformerProperties stage : stages) {
            if (stage == null || !StringUtils.hasText(stage.getName()) || TransformationService.isIdentity(stage.getName())) {
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

    /** How logs name the place: the pipeline, and the step for a pipeline with steps. */
    private static String where(PipelineProperties pipeline, StepProperties step) {
        return step.getName() == null ? pipeline.getName() : pipeline.getName() + "', step '" + step.getName();
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
