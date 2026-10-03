package io.labs64.audit.exception;

/**
 * A call was refused by local backpressure (a full bulkhead), not by the destination. The delivery
 * is deferred without spending an attempt and never goes to the fallback sink.
 */
public class ThrottledDeliveryException extends RetryableDeliveryException {

    public ThrottledDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
