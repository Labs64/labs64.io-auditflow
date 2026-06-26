package io.labs64.auditflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.auditflow.client.auth.TokenProvider;
import io.labs64.auditflow.model.AuditEvent;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/** Immutable, fully-resolved client configuration. */
record ClientConfig(
        URI baseUrl,
        TokenProvider tokenProvider,
        String defaultSourceSystem,
        Duration connectTimeout,
        Duration requestTimeout,
        RetryPolicy retryPolicy,
        Executor executor,
        ObjectMapper objectMapper,
        BiConsumer<AuditEvent, Throwable> errorHandler) {
}
