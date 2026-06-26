package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;

/** Thrown on HTTP 400 — the event payload failed server-side validation. */
public class ValidationException extends AuditFlowException {
    public ValidationException(String message, int statusCode, ErrorResponse errorResponse) {
        super(message, statusCode, errorResponse);
    }
}
