package io.labs64.auditflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.auditflow.client.exception.AuditFlowException;
import io.labs64.auditflow.client.exception.AuditFlowServerException;
import io.labs64.auditflow.client.exception.AuditFlowTransportException;
import io.labs64.auditflow.client.exception.PublishFailedException;
import io.labs64.auditflow.client.exception.UnauthorizedException;
import io.labs64.auditflow.client.exception.ValidationException;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.auditflow.model.ErrorResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Default {@link AuditFlowClient} backed by {@link java.net.http.HttpClient}. */
final class DefaultAuditFlowClient implements AuditFlowClient {

    private static final String PUBLISH_PATH = "/audit/publish";

    private final ClientConfig config;
    private final HttpClient httpClient;
    private final URI publishUri;

    DefaultAuditFlowClient(ClientConfig config) {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(config.connectTimeout());
        if (config.executor() != null) {
            builder.executor(config.executor());
        }
        this.httpClient = builder.build();
        String base = config.baseUrl().toString().replaceAll("/+$", "");
        this.publishUri = URI.create(base + PUBLISH_PATH);
    }

    @Override
    public PublishResult publish(AuditEvent event) {
        AuditEvent prepared = prepare(event);
        HttpRequest request = buildRequest(prepared);
        RetryPolicy retry = config.retryPolicy();

        AuditFlowException lastRetryable = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            sleep(retry.backoffBeforeAttempt(attempt));
            HttpResponse<String> response = send(request);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return PublishResult.from(status, response.headers());
            }
            AuditFlowException mapped = mapError(status, response.body());
            if (retry.isRetryableStatus(status)) {
                lastRetryable = mapped;
                continue;
            }
            throw mapped;
        }
        throw lastRetryable;
    }

    @Override
    public CompletableFuture<PublishResult> publishAsync(AuditEvent event) {
        return CompletableFuture.supplyAsync(() -> publish(event),
                config.executor() != null ? config.executor() : Runnable::run);
    }

    @Override
    public void fireAndForget(AuditEvent event) {
        publishAsync(event).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                config.errorHandler().accept(event, cause);
            }
        });
    }

    private AuditEvent prepare(AuditEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        if (event.getSourceSystem() == null && config.defaultSourceSystem() != null) {
            event.setSourceSystem(config.defaultSourceSystem());
        }
        if (event.getCorrelationId() == null) {
            event.setCorrelationId(UUID.randomUUID().toString());
        }
        return event;
    }

    private HttpRequest buildRequest(AuditEvent event) {
        String json = serialize(event);
        HttpRequest.Builder builder = HttpRequest.newBuilder(publishUri)
                .timeout(config.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Correlation-ID", event.getCorrelationId())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (config.tokenProvider() != null) {
            String token = config.tokenProvider().token();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        return builder.build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AuditFlowTransportException("Failed to send audit event", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditFlowTransportException("Interrupted while sending audit event", e);
        }
    }

    private String serialize(AuditEvent event) {
        try {
            ObjectMapper mapper = config.objectMapper();
            return mapper.writeValueAsString(event);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AuditFlowTransportException("Failed to serialize audit event", e);
        }
    }

    private AuditFlowException mapError(int status, String body) {
        ErrorResponse error = parseError(body);
        String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : "AuditFlow request failed with HTTP " + status;
        return switch (status) {
            case 400 -> new ValidationException(message, status, error);
            case 401 -> new UnauthorizedException(message, status, error);
            case 503 -> new PublishFailedException(message, status, error);
            default -> new AuditFlowServerException(message, status, error);
        };
    }

    private ErrorResponse parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return config.objectMapper().readValue(body, ErrorResponse.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditFlowTransportException("Interrupted during retry backoff", e);
        }
    }
}
