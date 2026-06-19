package io.labs64.audit.service;

import io.labs64.audit.config.HttpRetryProperties;
import io.micrometer.core.instrument.Counter;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.util.concurrent.TimeoutException;

/**
 * Builds the bounded exponential-backoff retry spec applied to the outbound HTTP calls to the
 * transformer and sink services. Kept here so both services stay symmetric.
 *
 * <p>Only <em>transient</em> failures are retried: 5xx responses and transport-level failures
 * (connection refused/reset, connect/read timeouts). Client errors (4xx) are treated as poison
 * and never retried. When retries are exhausted the original failure is propagated unchanged so
 * the caller's error mapping and downstream classification still see the real cause.</p>
 */
final class HttpRetrySupport {

    private HttpRetrySupport() {
    }

    static Retry spec(HttpRetryProperties props, Counter retriesCounter) {
        return Retry.backoff(props.getMaxAttempts(), props.getMinBackoff())
                .jitter(0.5)
                .filter(HttpRetrySupport::isRetryable)
                .doBeforeRetry(signal -> retriesCounter.increment())
                .onRetryExhaustedThrow((retrySpec, signal) -> signal.failure());
    }

    /** Transient = worth retrying; everything else (notably 4xx) is not. */
    static boolean isRetryable(Throwable t) {
        if (t instanceof WebClientResponseException responseException) {
            return responseException.getStatusCode().is5xxServerError();
        }
        // No HTTP response received: connection refused/reset, connect/read timeout.
        return t instanceof WebClientRequestException || t instanceof TimeoutException;
    }
}
