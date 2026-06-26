package io.labs64.auditflow.client.exception;

/** Thrown when the request could not complete (IO error / timeout) after retries were exhausted. */
public class AuditFlowTransportException extends AuditFlowException {
    public AuditFlowTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
