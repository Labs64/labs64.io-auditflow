package io.labs64.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SinkService.
 */
@ExtendWith(MockitoExtension.class)
class SinkServiceTest {

    @Mock
    private SinkDiscovery sinkDiscovery;

    private SinkService sinkService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        sinkService = new SinkService(sinkDiscovery, objectMapper);
    }

    // -------------------------------------------------------------------------
    // URL validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() throws IllegalStateException when sink URL is null")
    void shouldThrowWhenSinkUrlIsNull() {
        when(sinkDiscovery.getSinkUrl()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sinkService.sendToSink("{\"key\":\"value\"}", "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Sink service URL is empty or null"));
    }

    @Test
    @DisplayName("sendToSink() throws IllegalStateException when sink URL is empty")
    void shouldThrowWhenSinkUrlIsEmpty() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sinkService.sendToSink("{\"key\":\"value\"}", "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Sink service URL is empty or null"));
    }

    // -------------------------------------------------------------------------
    // Invalid JSON input
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() throws RuntimeException wrapping IllegalArgumentException for invalid JSON")
    void shouldThrowForInvalidJson() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sinkService.sendToSink("not-valid-json", "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Failed to send event to sink") ||
                        ex.getCause() instanceof IllegalArgumentException,
                "Expected RuntimeException wrapping an IllegalArgumentException for invalid JSON");
    }

    // -------------------------------------------------------------------------
    // Null properties map → no NPE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() with null properties uses empty map without NPE")
    @SuppressWarnings("unchecked")
    void shouldHandleNullPropertiesWithoutNpe() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        WebClient mockWebClient = buildMockWebClientReturning("{\"status\":\"ok\"}");
        sinkService.getWebClientCache().put("http://localhost:8082", mockWebClient);

        // Should not throw NullPointerException
        assertDoesNotThrow(() -> sinkService.sendToSink("{\"key\":\"value\"}", "my_sink", null));
    }

    // -------------------------------------------------------------------------
    // WebClient interaction
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() returns response string from WebClient on success")
    @SuppressWarnings("unchecked")
    void shouldReturnSinkResponseOnSuccess() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        WebClient mockWebClient = buildMockWebClientReturning("{\"status\":\"ok\"}");
        sinkService.getWebClientCache().put("http://localhost:8082", mockWebClient);

        String result = sinkService.sendToSink("{\"key\":\"value\"}", "my_sink", Map.of("prop", "val"));

        assertEquals("{\"status\":\"ok\"}", result);
    }

    @Test
    @DisplayName("sendToSink() wraps WebClient RuntimeException with 'Failed to send event to sink' message")
    @SuppressWarnings("unchecked")
    void shouldWrapWebClientExceptionWithFailedToSendMessage() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        sinkService.getWebClientCache().put("http://localhost:8082", mockWebClient);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> sinkService.sendToSink("{\"key\":\"value\"}", "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Failed to send event to sink"),
                "Exception message should contain 'Failed to send event to sink'");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private WebClient buildMockWebClientReturning(String responseBody) {
        WebClient mockWebClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec requestBodySpec = mock(WebClient.RequestBodySpec.class);
        WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);

        when(mockWebClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any(MediaType.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(responseBody));

        return mockWebClient;
    }
}
