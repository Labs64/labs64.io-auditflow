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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransformationService.
 *
 * WebClient is mocked via a spy on the service's internal cache to avoid
 * needing a real HTTP server.
 */
@ExtendWith(MockitoExtension.class)
class TransformationServiceTest {

    @Mock
    private TransformerDiscovery transformerDiscovery;

    private TransformationService transformationService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        transformationService = new TransformationService(transformerDiscovery, objectMapper);
    }

    // -------------------------------------------------------------------------
    // URL validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("transform() throws IllegalStateException when transformer URL is null")
    void shouldThrowWhenTransformerUrlIsNull() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transformationService.transform("{\"key\":\"value\"}", "my_transformer"));

        assertTrue(ex.getMessage().contains("Transformer URL is empty or null"));
    }

    @Test
    @DisplayName("transform() throws IllegalStateException when transformer URL is empty")
    void shouldThrowWhenTransformerUrlIsEmpty() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transformationService.transform("{\"key\":\"value\"}", "my_transformer"));

        assertTrue(ex.getMessage().contains("Transformer URL is empty or null"));
    }

    // -------------------------------------------------------------------------
    // Invalid JSON input
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("transform() throws RuntimeException wrapping IllegalArgumentException for invalid JSON")
    void shouldThrowForInvalidJson() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:8081");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> transformationService.transform("not-valid-json", "my_transformer"));

        // The service wraps the IllegalArgumentException in a RuntimeException
        assertTrue(ex.getMessage().contains("Transformation failed") ||
                        ex.getCause() instanceof IllegalArgumentException,
                "Expected RuntimeException wrapping an IllegalArgumentException for invalid JSON");
    }

    // -------------------------------------------------------------------------
    // WebClient interaction — using a mock WebClient injected via the cache
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("transform() returns response string from WebClient on success")
    @SuppressWarnings("unchecked")
    void shouldReturnTransformedResponseOnSuccess() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:8081");

        // Build a mock WebClient chain
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
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("{\"transformed\":true}"));

        // Inject the mock WebClient into the cache
        transformationService.getWebClientCache().put("http://localhost:8081", mockWebClient);

        String result = transformationService.transform("{\"key\":\"value\"}", "my_transformer");

        assertEquals("{\"transformed\":true}", result);
        verify(requestBodyUriSpec).uri("/transform/my_transformer");
    }

    @Test
    @DisplayName("transform() wraps WebClient RuntimeException with 'Transformation failed' message")
    @SuppressWarnings("unchecked")
    void shouldWrapWebClientExceptionWithTransformationFailedMessage() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:8081");

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

        transformationService.getWebClientCache().put("http://localhost:8081", mockWebClient);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> transformationService.transform("{\"key\":\"value\"}", "my_transformer"));

        assertTrue(ex.getMessage().contains("Transformation failed"),
                "Exception message should contain 'Transformation failed'");
    }
}
