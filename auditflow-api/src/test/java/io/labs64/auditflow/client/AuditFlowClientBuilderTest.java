package io.labs64.auditflow.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AuditFlowClientBuilderTest {

    @Test
    void baseUrlIsRequired() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> AuditFlowClient.builder().toConfig());
        assertTrue(ex.getMessage().toLowerCase().contains("baseurl"));
    }

    @Test
    void appliesSensibleDefaults() {
        ClientConfig config = AuditFlowClient.builder()
                .baseUrl("https://audit.labs64.io/api/v1")
                .toConfig();

        assertEquals("https://audit.labs64.io/api/v1", config.baseUrl().toString());
        assertEquals(Duration.ofSeconds(5), config.connectTimeout());
        assertEquals(Duration.ofSeconds(10), config.requestTimeout());
        assertEquals(3, config.retryPolicy().maxAttempts());
        assertNotNull(config.objectMapper());
        assertNotNull(config.errorHandler());
        assertNull(config.tokenProvider());
    }

    @Test
    void tokenShortcutWrapsAsFixedProvider() {
        ClientConfig config = AuditFlowClient.builder()
                .baseUrl("https://audit.labs64.io/api/v1")
                .token("jwt-123")
                .toConfig();

        assertEquals("jwt-123", config.tokenProvider().token());
    }

    @Test
    void supportsByoHttpClient() {
        java.net.http.HttpClient customClient = java.net.http.HttpClient.newHttpClient();
        ClientConfig config = AuditFlowClient.builder()
                .baseUrl("https://audit.labs64.io/api/v1")
                .httpClient(customClient)
                .toConfig();

        assertSame(customClient, config.httpClient());
    }

    @Test
    void fromEnvThrowsWhenUrlMissing() {
        assertThrows(IllegalStateException.class, AuditFlowClient::fromEnv);
    }
}
