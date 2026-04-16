package io.labs64.audit.exception;

import io.labs64.audit.v1.model.ErrorCode;
import io.labs64.audit.v1.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.OffsetDateTime;
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
