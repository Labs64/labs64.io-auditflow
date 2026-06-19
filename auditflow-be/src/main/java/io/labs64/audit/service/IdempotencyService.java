package io.labs64.audit.service;

/**
 * Idempotency / dedup guard keyed by audit {@code eventId}, with a secondary per-pipeline
 * completion key so a redelivery never re-delivers to an already-succeeded pipeline.
 *
 * <p>Claim-on-receive: {@link #claim(String)} atomically marks an event only if absent. A
 * duplicate (or an in-flight redelivery) fails to claim and is dropped by the caller. After
 * successful processing the caller calls {@link #markProcessed(String)} to extend the marker;
 * on an event-level failure the caller calls {@link #release(String)} so an at-least-once
 * redelivery can retry.</p>
 *
 * <p>Two implementations are selected by {@code auditflow.idempotency.store}:
 * {@code redis} (default, {@link RedisIdempotencyService}) for the full stack, and
 * {@code memory} ({@link InMemoryIdempotencyService}) for the lite local profile so Redis is
 * not required.</p>
 */
public interface IdempotencyService {

    /** @return true if this caller acquired the claim (first time seeing this eventId). */
    boolean claim(String eventId);

    /** Mark an event as fully processed, extending the marker so late duplicates are still dropped. */
    void markProcessed(String eventId);

    /** Release a claim so an at-least-once redelivery can retry this event. */
    void release(String eventId);

    /**
     * Mark a single pipeline as successfully delivered for this event. Survives a redelivery
     * (uses the longer "done" retention) so the pipeline is not delivered to twice.
     */
    void markPipelineDone(String eventId, String pipelineName);

    /** @return true if this pipeline already delivered successfully for this event on a prior attempt. */
    boolean isPipelineDone(String eventId, String pipelineName);
}
