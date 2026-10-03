package io.labs64.audit.delivery;

/** Why a delivery ended in the tenant's DLQ (header {@link DeliveryHeaders#DLQ_REASON}). */
public enum DeadLetterReason {
    /** Permanent failure: a 4xx from the transformer/sink or malformed transformer output. */
    POISON,
    /** The pipeline's {@code retry.maxAttempts} was used up. */
    ATTEMPTS_EXHAUSTED,
    /** The pipeline's {@code retry.maxAge} passed before a delivery succeeded. */
    MAX_AGE_EXCEEDED,
    /** The pipeline no longer exists or is disabled for the tenant. */
    PIPELINE_UNAVAILABLE,
    /** The delivery message itself is unusable (no pipeline header, unparseable body). */
    MALFORMED
}
