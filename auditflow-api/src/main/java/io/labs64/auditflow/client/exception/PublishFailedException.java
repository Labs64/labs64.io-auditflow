package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;

/** Thrown on HTTP 503 — the message broker was unavailable. Retryable. */
public class PublishFailedException extends AuditFlowException {
    public PublishFailedException(String message, int statusCode, ErrorResponse errorResponse) {
        super(message, statusCode, errorResponse);
    }
}
