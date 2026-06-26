package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;

/** Thrown on HTTP 401 — missing or invalid bearer token. */
public class UnauthorizedException extends AuditFlowException {
    public UnauthorizedException(String message, int statusCode, ErrorResponse errorResponse) {
        super(message, statusCode, errorResponse);
    }
}
