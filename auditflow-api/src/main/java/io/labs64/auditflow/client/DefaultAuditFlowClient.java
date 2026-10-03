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
    private static final String BATCH_PATH = "/audit/publish/batch";
    /** Server limit of events per batch request. */
    static final int MAX_BATCH = 100;
    private static final java.util.Set<String> RETRYABLE_ENTRY_CODES = java.util.Set.of("TENANT_RATE_LIMITED", "PUBLISH_FAILED");

    private final ClientConfig config;
    private final HttpClient httpClient;
    private final URI publishUri;
    private final URI batchUri;

    DefaultAuditFlowClient(ClientConfig config) {
        this.config = config;
        if (config.httpClient() != null) {
            this.httpClient = config.httpClient();
        } else {
            this.httpClient = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).build();
        }
        String base = config.baseUrl().toString().replaceAll("/+$", "");
        this.publishUri = URI.create(base + PUBLISH_PATH);
        this.batchUri = URI.create(base + BATCH_PATH);
    }

    @Override
    public PublishResult publish(AuditEvent event) {
        return publish(event, AuditFlowRequestOptions.empty());
    }

    @Override
    public PublishResult publish(AuditEvent event, AuditFlowRequestOptions options) {
        try {
            return publishAsync(event, options).join();
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
        return publishAsync(event, AuditFlowRequestOptions.empty());
    }

    @Override
    public CompletableFuture<PublishResult> publishAsync(AuditEvent event, AuditFlowRequestOptions options) {
        AuditEvent prepared = prepare(event);
        HttpRequest request = buildRequest(prepared, options != null ? options : AuditFlowRequestOptions.empty());
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
    public BatchResult publishBatch(java.util.List<AuditEvent> events, AuditFlowRequestOptions options) {
        AuditFlowRequestOptions opts = options != null ? options : AuditFlowRequestOptions.empty();
        java.util.List<AuditEvent> prepared = events.stream().map(this::prepare).toList();
        BatchResult.Entry[] out = new BatchResult.Entry[prepared.size()];
        for (int from = 0; from < prepared.size(); from += MAX_BATCH) {
            java.util.List<Integer> pending = new java.util.ArrayList<>();
            for (int i = from; i < Math.min(prepared.size(), from + MAX_BATCH); i++) {
                pending.add(i);
            }
            sendChunk(prepared, pending, out, opts);
        }
        return new BatchResult(java.util.List.of(out));
    }

    /** Send one chunk until every entry is settled or the retry policy is used up. */
    private void sendChunk(java.util.List<AuditEvent> events, java.util.List<Integer> pending, BatchResult.Entry[] out,
                           AuditFlowRequestOptions options) {
        int maxAttempts = config.retryPolicy().maxAttempts();
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(buildBatchRequest(events, pending, options), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    pause(config.retryPolicy().backoffBeforeAttempt(attempt + 1), 0);
                    continue;
                }
                throw new AuditFlowTransportException("Failed to send audit event batch", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuditFlowTransportException("Interrupted while sending audit event batch", e);
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                AuditFlowException mapped = mapError(status, response.body());
                if (config.retryPolicy().isRetryableStatus(status) && attempt < maxAttempts) {
                    pause(config.retryPolicy().backoffBeforeAttempt(attempt + 1), retryAfter(response));
                    continue;
                }
                throw mapped;
            }
            java.util.List<Integer> retry = settle(response.body(), events, pending, out, attempt < maxAttempts);
            if (retry.isEmpty()) {
                return;
            }
            pause(config.retryPolicy().backoffBeforeAttempt(attempt + 1), retryAfter(response));
            pending = retry;
        }
    }

    /** Record the server's per-entry results; return the indexes worth sending again. */
    private java.util.List<Integer> settle(String body, java.util.List<AuditEvent> events, java.util.List<Integer> pending,
                                           BatchResult.Entry[] out, boolean mayRetry) {
        io.labs64.auditflow.model.BatchPublishResult result;
        try {
            result = config.objectMapper().readValue(body, io.labs64.auditflow.model.BatchPublishResult.class);
        } catch (IOException e) {
            throw new AuditFlowTransportException("Unreadable batch response", e);
        }
        if (result.getResults() == null || result.getResults().size() != pending.size()) {
            throw new AuditFlowTransportException("Batch response has " + (result.getResults() == null ? 0
                    : result.getResults().size()) + " result(s) for " + pending.size() + " event(s)", null);
        }
        java.util.List<Integer> retry = new java.util.ArrayList<>();
        for (io.labs64.auditflow.model.BatchPublishEntryResult r : result.getResults()) {
            int index = pending.get(r.getIndex());
            UUID eventId = events.get(index).getEventId();
            if (r.getStatus() == io.labs64.auditflow.model.BatchPublishEntryResult.StatusEnum.ACCEPTED) {
                out[index] = new BatchResult.Entry(eventId, true, null, null);
                continue;
            }
            String code = r.getError() != null && r.getError().getCode() != null ? r.getError().getCode().getValue() : null;
            String message = r.getError() != null ? r.getError().getMessage() : null;
            out[index] = new BatchResult.Entry(eventId, false, code, message);
            if (mayRetry && code != null && RETRYABLE_ENTRY_CODES.contains(code)) {
                retry.add(index);
            }
        }
        return retry;
    }

    private HttpRequest buildBatchRequest(java.util.List<AuditEvent> events, java.util.List<Integer> pending,
                                          AuditFlowRequestOptions options) {
        ObjectMapper mapper = config.objectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body = mapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode array = body.putArray("events");
        for (int index : pending) {
            array.add(mapper.valueToTree(events.get(index)));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(batchUri)
                .timeout(config.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        options.headers().forEach(builder::setHeader);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", "application/json");
        builder.setHeader("X-Correlation-ID", events.get(pending.get(0)).getCorrelationId());
        if (config.tokenProvider() != null) {
            String token = config.tokenProvider().token();
            if (token != null && !token.isBlank()) {
                builder.setHeader("Authorization", "Bearer " + token);
            }
        }
        return builder.build();
    }

    private static long retryAfter(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    /** Sleep for the longer of the backoff and the server's Retry-After (seconds). */
    private static void pause(Duration backoff, long retryAfterSeconds) {
        long millis = Math.max(backoff.toMillis(), retryAfterSeconds * 1000);
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuditFlowTransportException("Interrupted while waiting to retry", e);
        }
    }

    @Override
    public void fireAndForget(AuditEvent event) {
        fireAndForget(event, AuditFlowRequestOptions.empty());
    }

    @Override
    public void fireAndForget(AuditEvent event, AuditFlowRequestOptions options) {
        publishAsync(event, options).whenComplete((ignoredResult, error) -> {
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

    private HttpRequest buildRequest(AuditEvent event, AuditFlowRequestOptions options) {
        String json = serialize(event);
        HttpRequest.Builder builder = HttpRequest.newBuilder(publishUri)
                .timeout(config.requestTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(json));
        options.headers().forEach(builder::setHeader);
        builder.setHeader("Content-Type", "application/json");
        builder.setHeader("Accept", "application/json");
        builder.setHeader("X-Correlation-ID", event.getCorrelationId());
        if (config.tokenProvider() != null) {
            String token = config.tokenProvider().token();
            if (token != null && !token.isBlank()) {
                builder.setHeader("Authorization", "Bearer " + token);
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
