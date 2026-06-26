package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;

/** Base type for all AuditFlow client failures. */
public class AuditFlowException extends RuntimeException {

    private final int statusCode;
    private final transient ErrorResponse errorResponse;

    public AuditFlowException(String message, int statusCode, ErrorResponse errorResponse) {
        super(message);
        this.statusCode = statusCode;
        this.errorResponse = errorResponse;
    }

    public AuditFlowException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.errorResponse = null;
    }

    /** HTTP status code that triggered this exception, or {@code -1} for transport-level failures. */
    public int statusCode() {
        return statusCode;
    }

    /** Parsed server error body, or {@code null} when none was available. */
    public ErrorResponse errorResponse() {
        return errorResponse;
    }
}
