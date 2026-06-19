package io.labs64.audit.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.labs64.audit.exception.PoisonDeliveryException;
import io.labs64.audit.exception.RetryableDeliveryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class DeliveryErrorsTest {

    @Test
    @DisplayName("4xx client errors classify as poison (never retried)")
    void shouldClassify4xxAsPoison() {
        WebClientResponseException ex = new WebClientResponseException(400, "Bad Request", null, null, null);
        assertInstanceOf(PoisonDeliveryException.class, DeliveryErrors.classify("ctx", ex));
    }

    @Test
    @DisplayName("5xx server errors classify as retryable")
    void shouldClassify5xxAsRetryable() {
        WebClientResponseException ex = new WebClientResponseException(503, "Service Unavailable", null, null, null);
        assertInstanceOf(RetryableDeliveryException.class, DeliveryErrors.classify("ctx", ex));
    }

    @Test
    @DisplayName("An open circuit breaker classifies as retryable (preserve -> DLQ)")
    void shouldClassifyOpenCircuitAsRetryable() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("test");
        breaker.transitionToOpenState();
        CallNotPermittedException ex = CallNotPermittedException.createCallNotPermittedException(breaker);
        assertInstanceOf(RetryableDeliveryException.class, DeliveryErrors.classify("ctx", ex));
    }

    @Test
    @DisplayName("Unclassified errors default to retryable (fail safe toward redelivery)")
    void shouldClassifyGenericAsRetryable() {
        assertInstanceOf(RetryableDeliveryException.class,
                DeliveryErrors.classify("ctx", new RuntimeException("boom")));
    }

    @Test
    @DisplayName("Already-classified errors pass through unchanged")
    void shouldPassThroughAlreadyClassified() {
        PoisonDeliveryException poison = new PoisonDeliveryException("already poison");
        assertSame(poison, DeliveryErrors.classify("ctx", poison));
    }
}
