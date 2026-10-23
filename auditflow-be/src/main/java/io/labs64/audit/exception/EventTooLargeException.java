package io.labs64.audit.exception;

/**
 * Thrown when an audit event is larger than the configured message broker accepts (e.g. 1 MiB on
 * Amazon SQS). Maps to HTTP 413: unlike {@link PublishException}, retrying cannot help.
 */
public class EventTooLargeException extends RuntimeException {

    public EventTooLargeException(String message) {
        super(message);
    }
}
