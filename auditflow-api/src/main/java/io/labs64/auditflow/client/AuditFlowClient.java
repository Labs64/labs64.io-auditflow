package io.labs64.auditflow.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.labs64.auditflow.client.auth.TokenProvider;
import io.labs64.auditflow.model.AuditEvent;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Client for publishing audit events to the Labs64.IO AuditFlow API. */
public interface AuditFlowClient {

    /** Publishes synchronously, throwing a typed {@link io.labs64.auditflow.client.exception.AuditFlowException} on failure. */
    PublishResult publish(AuditEvent event);

    /** Publishes without blocking; the future completes exceptionally on failure. */
    CompletableFuture<PublishResult> publishAsync(AuditEvent event);

    /** Publishes without blocking and never throws into the caller; failures go to the configured error handler. */
    void fireAndForget(AuditEvent event);

    static Builder builder() {
        return new Builder();
    }

    /** Creates a client configured entirely from standard environment variables (AUDITFLOW_URL, AUDITFLOW_TOKEN, AUDITFLOW_DEFAULT_SOURCE). */
    static AuditFlowClient fromEnv() {
        String url = System.getenv("AUDITFLOW_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("AUDITFLOW_URL environment variable is required");
        }
        Builder builder = builder().baseUrl(url);
        
        String token = System.getenv("AUDITFLOW_TOKEN");
        if (token != null && !token.isBlank()) {
            builder.token(token);
        }
        
        String source = System.getenv("AUDITFLOW_DEFAULT_SOURCE");
        if (source != null && !source.isBlank()) {
            builder.defaultSourceSystem(source);
        }
        
        return builder.build();
    }

    /** Fluent builder for {@link AuditFlowClient}. */
    final class Builder {

        private static final Logger LOGGER = System.getLogger(AuditFlowClient.class.getName());

        private String baseUrl;
        private TokenProvider tokenProvider;
        private String defaultSourceSystem;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private RetryPolicy retryPolicy = RetryPolicy.exponential(3);
        private ObjectMapper objectMapper;
        private BiConsumer<AuditEvent, Throwable> errorHandler;
        private java.util.function.Supplier<String> correlationIdProvider;
        private java.net.http.HttpClient httpClient;

        private Builder() {
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder token(String token) {
            this.tokenProvider = TokenProvider.fixed(token);
            return this;
        }

        public Builder tokenProvider(TokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
            return this;
        }

        public Builder defaultSourceSystem(String defaultSourceSystem) {
            this.defaultSourceSystem = defaultSourceSystem;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder retry(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }



        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder errorHandler(BiConsumer<AuditEvent, Throwable> errorHandler) {
            this.errorHandler = errorHandler;
            return this;
        }

        public Builder correlationIdProvider(java.util.function.Supplier<String> correlationIdProvider) {
            this.correlationIdProvider = correlationIdProvider;
            return this;
        }

        public Builder httpClient(java.net.http.HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /** Resolves all settings into an immutable config, applying defaults and validating. */
        ClientConfig toConfig() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("baseUrl is required");
            }
            ObjectMapper mapper = objectMapper != null ? objectMapper : defaultMapper();
            BiConsumer<AuditEvent, Throwable> handler = errorHandler != null
                    ? errorHandler
                    : (event, error) -> LOGGER.log(Level.WARNING, "AuditFlow fire-and-forget publish failed", error);
            return new ClientConfig(
                    URI.create(baseUrl),
                    tokenProvider,
                    defaultSourceSystem,
                    connectTimeout,
                    requestTimeout,
                    retryPolicy,
                    mapper,
                    handler,
                    correlationIdProvider,
                    httpClient);
        }

        public AuditFlowClient build() {
            return new DefaultAuditFlowClient(toConfig());
        }

        private static ObjectMapper defaultMapper() {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        }
    }
}
