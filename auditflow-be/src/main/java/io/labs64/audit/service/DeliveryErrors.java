package io.labs64.audit.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.labs64.audit.exception.ThrottledDeliveryException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Classifies an outbound transformer/sink failure into a {@link PoisonDeliveryException}
 * (never retryable — e.g. a 4xx client error, or a response too large to read) or a {@link RetryableDeliveryException}
 * (transient — 5xx, transport/timeout, or an open circuit breaker).
 *
 * <p>The default is <em>retryable</em>: when in doubt we redeliver/DLQ rather than risk a silent
 * drop, which for an audit product is the worse failure mode.</p>
 */
final class DeliveryErrors {

    private DeliveryErrors() {
    }

    static RuntimeException classify(String context, Throwable e) {
        // Already classified upstream — keep as-is.
        if (e instanceof PoisonDeliveryException || e instanceof RetryableDeliveryException) {
            return (RuntimeException) e;
        }

        String message = context + ": " + e.getMessage();

        // 4xx client errors are poison: the request itself is bad, retrying cannot help.
        if (e instanceof WebClientResponseException responseException
                && responseException.getStatusCode().is4xxClientError()) {
            return new PoisonDeliveryException(message, e);
        }

        // A response above auditflow.http.max-response-size is as large on every retry: poison, so the
        // event is dead-lettered (and alerted on) at once instead of after a day of attempts.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataBufferLimitException) {
                return new PoisonDeliveryException(context + ": response larger than auditflow.http.max-response-size ("
                        + cause.getMessage() + ")", e);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }

        // Open circuit (target deemed down) and everything else (5xx, transport, timeout) → retryable.
        if (e instanceof io.github.resilience4j.bulkhead.BulkheadFullException) {
            return new ThrottledDeliveryException(context + ": local concurrency limit reached (bulkhead full)", e);
        }
        if (e instanceof CallNotPermittedException) {
            return new RetryableDeliveryException(context + ": circuit open (downstream unavailable)", e);
        }
        return new RetryableDeliveryException(message, e);
    }
}
