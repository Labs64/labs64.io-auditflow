package io.labs64.audit.exception;

import io.labs64.auditflow.model.ErrorCode;
import io.labs64.auditflow.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.JsonMappingException.Reference;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Global exception handler for the AuditFlow application.
 * Provides consistent {@link ErrorResponse} format across all endpoints.
 *
 * <p>The {@code traceId} field in every response is populated from the SLF4J MDC
 * {@code correlationId} key set by {@link io.labs64.audit.config.CorrelationIdFilter},
 * enabling end-to-end request tracing via the {@code X-Correlation-ID} header.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** MDC key set by {@link io.labs64.audit.config.CorrelationIdFilter}. */
    private static final String MDC_CORRELATION_ID = "correlationId";

    // -------------------------------------------------------------------------
    // Validation errors
    // -------------------------------------------------------------------------

    /**
     * Handle bean-validation errors from {@code @Valid} annotations.
     * Returns HTTP 400 with a comma-separated list of field violations.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(
            MethodArgumentNotValidException ex,
            WebRequest request) {

        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fieldError) {
                        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
                    }
                    return error.getDefaultMessage();
                })
                .collect(Collectors.joining(", "));

        logger.warn("Validation error: {}", message);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(buildError(ErrorCode.VALIDATION_ERROR, message));
    }

    /**
     * Handle {@link IllegalArgumentException} — typically malformed input that
     * passes JSON parsing but fails business-level validation.
     * Returns HTTP 400.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request) {

        logger.warn("Illegal argument: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(buildError(ErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    /**
     * Handle {@link HttpMessageNotReadableException} — thrown when Jackson fails
     * to deserialize the request body (e.g. malformed JSON, invalid UUID).
     * Returns HTTP 400.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        String message;
        if (ex.getCause() instanceof InvalidFormatException ife) {
            String field = ife.getPath().stream()
                    .map(Reference::getFieldName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            message = field.isBlank()
                    ? "Invalid value in request body"
                    : "Invalid value for field '" + field + "'";
        } else {
            message = "Malformed request body";
        }
        logger.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(buildError(ErrorCode.VALIDATION_ERROR, message));
    }

    // -------------------------------------------------------------------------
    // Publish failure
    // -------------------------------------------------------------------------

    /**
     * Handle {@link PublishException} — thrown when the message broker rejects
     * or cannot accept the audit event.
     * Returns HTTP 503 Service Unavailable so callers can retry.
     */
    @ExceptionHandler(PublishException.class)
    public ResponseEntity<ErrorResponse> handlePublishException(
            PublishException ex,
            WebRequest request) {

        logger.error("Failed to publish audit event: {}", ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(ErrorCode.PUBLISH_FAILED, ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Tenant ingest gate (provisioning / quota)
    // -------------------------------------------------------------------------

    @ExceptionHandler(TenantNotProvisionedException.class)
    public ResponseEntity<ErrorResponse> handleTenantNotProvisioned(TenantNotProvisionedException ex) {
        logger.warn("Tenant not provisioned: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError(ErrorCode.TENANT_NOT_PROVISIONED, ex.getMessage()));
    }

    @ExceptionHandler(TenantDisabledException.class)
    public ResponseEntity<ErrorResponse> handleTenantDisabled(TenantDisabledException ex) {
        logger.warn("Tenant disabled: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(buildError(ErrorCode.TENANT_DISABLED, ex.getMessage()));
    }

    @ExceptionHandler(TenantRateLimitedException.class)
    public ResponseEntity<ErrorResponse> handleTenantRateLimited(TenantRateLimitedException ex) {
        logger.warn("Tenant rate limited: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(buildError(ErrorCode.TENANT_RATE_LIMITED, ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Request routing (wrong method / unsupported media type)
    // -------------------------------------------------------------------------

    /**
     * Handle {@link HttpRequestMethodNotSupportedException} — the endpoint exists
     * but the HTTP method is not mapped (e.g. {@code GET} on a POST-only path).
     * Returns HTTP 405 Method Not Allowed instead of falling through to the
     * generic 500 catch-all.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            WebRequest request) {

        logger.warn("Method not allowed: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(buildError(ErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    /**
     * Handle {@link HttpMediaTypeNotSupportedException} — the request carries an
     * unsupported {@code Content-Type} (e.g. {@code text/plain} on a JSON-only
     * endpoint). Returns HTTP 415 Unsupported Media Type instead of 500.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            WebRequest request) {

        logger.warn("Unsupported media type: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(buildError(ErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Not-found (static resources, favicon, actuator root, etc.)
    // -------------------------------------------------------------------------

    /**
     * Handle missing static resources (e.g. {@code /favicon.ico}, trailing-slash
     * actuator paths).  Logged at WARN instead of ERROR to avoid noise from
     * harmless 404s.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(
            NoResourceFoundException ex,
            WebRequest request) {

        logger.warn("Resource not found: {}", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(buildError(ErrorCode.INTERNAL_ERROR,
                        "The requested resource was not found"));
    }

    // -------------------------------------------------------------------------
    // Catch-all
    // -------------------------------------------------------------------------

    /**
     * Handle all other exceptions.
     * Returns HTTP 500 with a generic message to avoid leaking internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            WebRequest request) {

        logger.error("Internal error occurred", ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(ErrorCode.INTERNAL_ERROR,
                        "An internal error occurred while processing your request"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build an {@link ErrorResponse} with the current timestamp and the
     * correlation ID from MDC (if present).
     */
    private ErrorResponse buildError(ErrorCode code, String message) {
        ErrorResponse response = new ErrorResponse()
                .code(code)
                .message(message)
                .timestamp(OffsetDateTime.now());

        String correlationId = MDC.get(MDC_CORRELATION_ID);
        if (correlationId != null) {
            response.traceId(correlationId);
        }

        return response;
    }
}
