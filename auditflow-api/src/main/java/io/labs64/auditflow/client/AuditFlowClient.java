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

    /** Fluent builder for {@link AuditFlowClient}. */
    final class Builder {

        private static final Logger LOGGER = System.getLogger(AuditFlowClient.class.getName());

        private String baseUrl;
        private TokenProvider tokenProvider;
        private String defaultSourceSystem;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private RetryPolicy retryPolicy = RetryPolicy.exponential(3);
        private Executor executor;
        private ObjectMapper objectMapper;
        private BiConsumer<AuditEvent, Throwable> errorHandler;

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

        public Builder executor(Executor executor) {
            this.executor = executor;
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
                    executor,
                    mapper,
                    handler);
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
