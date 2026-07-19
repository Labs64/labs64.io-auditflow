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
final class ClientConfig {
    private final URI baseUrl;
    private final TokenProvider tokenProvider;
    private final String defaultSourceSystem;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;
    private final BiConsumer<AuditEvent, Throwable> errorHandler;
    private final Supplier<String> correlationIdProvider;
    private final HttpClient httpClient;

    ClientConfig(URI baseUrl, TokenProvider tokenProvider, String defaultSourceSystem, Duration connectTimeout, Duration requestTimeout, RetryPolicy retryPolicy, ObjectMapper objectMapper, BiConsumer<AuditEvent, Throwable> errorHandler, Supplier<String> correlationIdProvider, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.tokenProvider = tokenProvider;
        this.defaultSourceSystem = defaultSourceSystem;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.retryPolicy = retryPolicy;
        this.objectMapper = objectMapper;
        this.errorHandler = errorHandler;
        this.correlationIdProvider = correlationIdProvider;
        this.httpClient = httpClient;
    }

    public URI baseUrl() { return baseUrl; }
    public TokenProvider tokenProvider() { return tokenProvider; }
    public String defaultSourceSystem() { return defaultSourceSystem; }
    public Duration connectTimeout() { return connectTimeout; }
    public Duration requestTimeout() { return requestTimeout; }
    public RetryPolicy retryPolicy() { return retryPolicy; }
    public ObjectMapper objectMapper() { return objectMapper; }
    public BiConsumer<AuditEvent, Throwable> errorHandler() { return errorHandler; }
    public Supplier<String> correlationIdProvider() { return correlationIdProvider; }
    public HttpClient httpClient() { return httpClient; }
}
