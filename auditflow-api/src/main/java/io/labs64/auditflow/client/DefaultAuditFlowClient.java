package io.labs64.auditflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.auditflow.client.exception.AuditFlowException;
import io.labs64.auditflow.client.exception.AuditFlowTransportException;
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
        if (config.httpClient() != null) {
            this.httpClient = config.httpClient();
        } else {
            this.httpClient = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        }
        String base = config.baseUrl().toString().replaceAll("/+$", "");
        this.publishUri = URI.create(base + PUBLISH_PATH);
    }

    @Override
    public PublishResult publish(AuditEvent event) {
        try {
            return publishAsync(event).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AuditFlowException) {
                throw (AuditFlowException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AuditFlowException("Unexpected error during publish", cause);
        }
    }

    @Override
    public CompletableFuture<PublishResult> publishAsync(AuditEvent event) {
        AuditEvent prepared = prepare(event);
        HttpRequest request = buildRequest(prepared);
        return sendAsyncWithRetry(request, 1);
    }

    private CompletableFuture<PublishResult> sendAsyncWithRetry(HttpRequest request, int attempt) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                        if (cause instanceof IOException && attempt < config.retryPolicy().maxAttempts()) {
                            return retryAsync(request, attempt + 1, cause);
                        }
                        if (cause instanceof AuditFlowException) {
                            throw (AuditFlowException) cause;
                        }
                        throw new AuditFlowTransportException("Failed to send audit event", cause);
                    }

                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        return CompletableFuture.completedFuture(PublishResult.from(status, response.headers()));
                    }

                    AuditFlowException mapped = mapError(status, response.body());
                    if (config.retryPolicy().isRetryableStatus(status) && attempt < config.retryPolicy().maxAttempts()) {
                        return retryAsync(request, attempt + 1, mapped);
                    }
                    throw mapped;
                }).thenCompose(f -> f);
    }

    private CompletableFuture<PublishResult> retryAsync(HttpRequest request, int nextAttempt, Throwable lastError) {
        Duration backoff = config.retryPolicy().backoffBeforeAttempt(nextAttempt);
        if (backoff.isZero() || backoff.isNegative()) {
            return sendAsyncWithRetry(request, nextAttempt);
        }
        return CompletableFuture.supplyAsync(() -> null,
                CompletableFuture.delayedExecutor(backoff.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                .thenCompose(v -> sendAsyncWithRetry(request, nextAttempt));
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
            String correlationId = null;
            if (config.correlationIdProvider() != null) {
                correlationId = config.correlationIdProvider().get();
            }
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
            }
            event.setCorrelationId(correlationId);
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
        return new AuditFlowException(message, status, error);
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


}
