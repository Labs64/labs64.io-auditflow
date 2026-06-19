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
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        HttpRetryProperties retryProperties = new HttpRetryProperties();
        retryProperties.setMinBackoff(Duration.ofMillis(1)); // keep retry tests fast

        // Pass-through circuit breaker: run the supplied Mono unchanged (breaker behaviour itself
        // is Resilience4j's; classification of an open circuit is covered by DeliveryErrorsTest).
        ReactiveCircuitBreakerFactory cbFactory = mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker cb = mock(ReactiveCircuitBreaker.class);
        lenient().when(cbFactory.create(anyString())).thenReturn(cb);
        lenient().when(cb.run(any(Mono.class), any())).thenAnswer(inv -> inv.getArgument(0));

        sinkService = new SinkService(
                sinkDiscovery, WebClient.builder(), cbFactory, retryProperties, new SimpleMeterRegistry());
    }

    private com.fasterxml.jackson.databind.JsonNode node(String json) {
        try { return objectMapper.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    // -------------------------------------------------------------------------
    // URL validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() throws IllegalStateException when sink URL is null")
    void shouldThrowWhenSinkUrlIsNull() {
        when(sinkDiscovery.getSinkUrl()).thenReturn(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Sink service URL is empty or null"));
    }

    @Test
    @DisplayName("sendToSink() throws IllegalStateException when sink URL is empty")
    void shouldThrowWhenSinkUrlIsEmpty() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of()));

        assertTrue(ex.getMessage().contains("Sink service URL is empty or null"));
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
        StepVerifier.create(sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", null))
                .expectNext("{\"status\":\"ok\"}")
                .verifyComplete();
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

        StepVerifier.create(sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of("prop", "val")))
                .expectNext("{\"status\":\"ok\"}")
                .verifyComplete();
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

        StepVerifier.create(sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of()))
                .expectErrorMatches(e -> e instanceof RuntimeException
                        && e.getMessage().contains("Failed to send event to sink"))
                .verify();
    }

    // -------------------------------------------------------------------------
    // Retry / backoff
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sendToSink() retries transient 5xx failures then succeeds")
    void shouldRetryOn5xxThenSucceed() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        AtomicInteger attempts = new AtomicInteger();
        WebClient mockWebClient = buildMockWebClientReturning(Mono.defer(() ->
                attempts.incrementAndGet() < 3
                        ? Mono.error(new WebClientResponseException(503, "Service Unavailable", null, null, null))
                        : Mono.just("{\"status\":\"ok\"}")));
        sinkService.getWebClientCache().put("http://localhost:8082", mockWebClient);

        StepVerifier.create(sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of()))
                .expectNext("{\"status\":\"ok\"}")
                .verifyComplete();

        assertEquals(3, attempts.get(), "should retry twice (3rd attempt succeeds)");
    }

    @Test
    @DisplayName("sendToSink() does not retry on 4xx")
    void shouldNotRetryOn4xx() {
        when(sinkDiscovery.getSinkUrl()).thenReturn("http://localhost:8082");

        AtomicInteger attempts = new AtomicInteger();
        WebClient mockWebClient = buildMockWebClientReturning(Mono.defer(() -> {
            attempts.incrementAndGet();
            return Mono.error(new WebClientResponseException(400, "Bad Request", null, null, null));
        }));
        sinkService.getWebClientCache().put("http://localhost:8082", mockWebClient);

        StepVerifier.create(sinkService.sendToSink(node("{\"key\":\"value\"}"), "my_sink", Map.of()))
                .expectError()
                .verify();

        assertEquals(1, attempts.get(), "4xx must not be retried");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private WebClient buildMockWebClientReturning(String responseBody) {
        return buildMockWebClientReturning(Mono.just(responseBody));
    }

    @SuppressWarnings("unchecked")
    private WebClient buildMockWebClientReturning(Mono<String> body) {
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
}
