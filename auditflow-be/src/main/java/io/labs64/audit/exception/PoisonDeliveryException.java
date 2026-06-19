package io.labs64.audit.exception;

/**
 * A pipeline delivery failure that will never succeed on retry: a client error (4xx) from the
 * transformer/sink, or a transformer returning a non-JSON / malformed payload.
 *
 * <p>A poison failure is logged and counted but does <em>not</em> fail the whole event — retrying
 * or dead-lettering it would loop forever without ever succeeding.</p>
 */
public class PoisonDeliveryException extends RuntimeException {

    public PoisonDeliveryException(String message) {
        super(message);
    }

    public PoisonDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
