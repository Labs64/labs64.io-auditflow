package io.labs64.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.HttpRetryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

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
        HttpRetryProperties retryProperties = new HttpRetryProperties();
        retryProperties.setMinBackoff(Duration.ofMillis(1)); // keep retry tests fast
        transformationService = new TransformationService(
                transformerDiscovery, WebClient.builder(), retryProperties, new SimpleMeterRegistry());
    }

    private com.fasterxml.jackson.databind.JsonNode node(String json) {
        try { return objectMapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private WebClient mockWebClientReturning(Mono<String> body) {
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
        when(responseSpec.bodyToMono(String.class)).thenReturn(body);
        return mockWebClient;
    }

    // -------------------------------------------------------------------------
    // URL validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("transform() throws IllegalStateException when transformer URL is null")
    void shouldThrowWhenTransformerUrlIsNull() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"));

        assertTrue(ex.getMessage().contains("Transformer URL is empty or null"));
    }

    @Test
    @DisplayName("transform() throws IllegalStateException when transformer URL is empty")
    void shouldThrowWhenTransformerUrlIsEmpty() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"));

        assertTrue(ex.getMessage().contains("Transformer URL is empty or null"));
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

        StepVerifier.create(transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"))
                .expectNext("{\"transformed\":true}")
                .verifyComplete();

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

        StepVerifier.create(transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("Transformation failed"))
                .verify();
    }

    // -------------------------------------------------------------------------
    // Retry / backoff (P0-3)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("transform() retries transient 5xx failures then succeeds")
    void shouldRetryOn5xxThenSucceed() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:8081");

        AtomicInteger attempts = new AtomicInteger();
        WebClient mockWebClient = mockWebClientReturning(Mono.defer(() ->
                attempts.incrementAndGet() < 3
                        ? Mono.error(new WebClientResponseException(503, "Service Unavailable", null, null, null))
                        : Mono.just("{\"transformed\":true}")));
        transformationService.getWebClientCache().put("http://localhost:8081", mockWebClient);

        StepVerifier.create(transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"))
                .expectNext("{\"transformed\":true}")
                .verifyComplete();

        assertEquals(3, attempts.get(), "should retry twice (3rd attempt succeeds)");
    }

    @Test
    @DisplayName("transform() does not retry on 4xx")
    void shouldNotRetryOn4xx() {
        when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:8081");

        AtomicInteger attempts = new AtomicInteger();
        WebClient mockWebClient = mockWebClientReturning(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new WebClientResponseException(400, "Bad Request", null, null, null));
        }));
        transformationService.getWebClientCache().put("http://localhost:8081", mockWebClient);

        StepVerifier.create(transformationService.transform(node("{\"key\":\"value\"}"), "my_transformer"))
                .expectError()
                .verify();

        assertEquals(1, attempts.get(), "4xx must not be retried");
    }
}
