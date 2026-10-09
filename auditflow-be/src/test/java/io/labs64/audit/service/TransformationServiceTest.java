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

        transformationService = new TransformationService(
                transformerDiscovery, WebClient.builder(), cbFactory, retryProperties,
                new io.labs64.audit.config.HttpClientProperties(), new SimpleMeterRegistry());
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
    // Response size — against a real HTTP server (the limit lives in the WebClient codecs)
    // -------------------------------------------------------------------------

    private reactor.netty.DisposableServer transformerAnswering(String body) {
        return reactor.netty.http.server.HttpServer.create().port(0)
                .route(routes -> routes.post("/transform/{name}", (request, response) -> response
                        .header("Content-Type", "application/json")
                        .sendString(Mono.just(body))))
                .bindNow();
    }

    @Test
    @DisplayName("A transformer response above WebClient's 256 KB default is read (events up to the ingest limit)")
    void shouldReadAResponseLargerThanTheDefaultBuffer() {
        String event = "{\"eventId\":\"e-1\",\"extra\":{\"p\":\"" + "a".repeat(300_000) + "\"}}";
        reactor.netty.DisposableServer server = transformerAnswering(event);
        try {
            when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:" + server.port());

            StepVerifier.create(transformationService.transform(node("{\"eventId\":\"e-1\"}"), "zero"))
                    .expectNext(event)
                    .verifyComplete();
        } finally {
            server.disposeNow();
        }
    }

    @Test
    @DisplayName("A response above auditflow.http.max-response-size is poison, not retried")
    void shouldClassifyAResponseAboveTheLimitAsPoison() {
        io.labs64.audit.config.HttpClientProperties small = new io.labs64.audit.config.HttpClientProperties();
        small.setMaxResponseSize(org.springframework.util.unit.DataSize.ofKilobytes(1));
        HttpRetryProperties retryProperties = new HttpRetryProperties();
        retryProperties.setMinBackoff(Duration.ofMillis(1));
        ReactiveCircuitBreakerFactory<?, ?> cbFactory = mock(ReactiveCircuitBreakerFactory.class);
        ReactiveCircuitBreaker cb = mock(ReactiveCircuitBreaker.class);
        when(cbFactory.create(anyString())).thenReturn(cb);
        when(cb.run(any(Mono.class), any())).thenAnswer(inv -> inv.getArgument(0));
        TransformationService limited = new TransformationService(
                transformerDiscovery, WebClient.builder(), cbFactory, retryProperties, small, new SimpleMeterRegistry());

        reactor.netty.DisposableServer server = transformerAnswering("{\"p\":\"" + "a".repeat(4_000) + "\"}");
        try {
            when(transformerDiscovery.getTransformerUrl()).thenReturn("http://localhost:" + server.port());

            StepVerifier.create(limited.transform(node("{\"eventId\":\"e-1\"}"), "zero"))
                    .expectError(io.labs64.audit.exception.PoisonDeliveryException.class)
                    .verify(Duration.ofSeconds(10));
        } finally {
            server.disposeNow();
        }
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
    // Retry / backoff
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
