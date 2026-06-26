package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;

/** Thrown on HTTP 500 and other unexpected server responses. */
public class AuditFlowServerException extends AuditFlowException {
    public AuditFlowServerException(String message, int statusCode, ErrorResponse errorResponse) {
        super(message, statusCode, errorResponse);
    }
}
