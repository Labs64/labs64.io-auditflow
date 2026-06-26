package io.labs64.auditflow.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.auditflow.client.auth.TokenProvider;
import io.labs64.auditflow.model.AuditEvent;

import java.net.URI;
import java.time.Duration;

import java.net.http.HttpClient;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Immutable, fully-resolved client configuration. */
record ClientConfig(
        URI baseUrl,
        TokenProvider tokenProvider,
        String defaultSourceSystem,
        Duration connectTimeout,
        Duration requestTimeout,
        RetryPolicy retryPolicy,
        ObjectMapper objectMapper,
        BiConsumer<AuditEvent, Throwable> errorHandler,
        Supplier<String> correlationIdProvider,
        HttpClient httpClient) {
}
