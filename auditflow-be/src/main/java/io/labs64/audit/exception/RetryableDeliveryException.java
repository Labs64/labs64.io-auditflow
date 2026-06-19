package io.labs64.audit.exception;

/**
 * A pipeline delivery failure that is worth retrying: a transient transformer/sink failure that
 * survived the HTTP-level retries (5xx, transport error, timeout) or an open circuit breaker.
 *
 * <p>When any pipeline ends in this state the event is redelivered and ultimately routed to the
 * broker dead-letter queue, rather than being silently dropped.</p>
 */
public class RetryableDeliveryException extends RuntimeException {

    public RetryableDeliveryException(String message) {
        super(message);
    }

    public RetryableDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
