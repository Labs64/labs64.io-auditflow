package io.labs64.audit.exception;

/**
 * Thrown when an audit event cannot be published to the message broker.
 * Maps to HTTP 503 Service Unavailable so callers know the failure is transient
 * and the request can be retried.
 */
public class PublishException extends RuntimeException {

    public PublishException(String message) {
        super(message);
    }

    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
