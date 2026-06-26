package io.labs64.auditflow.client;

import java.time.Duration;

/** Controls how the client retries failed publish attempts. */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final double multiplier;
    private final boolean retryEnabled;

    private RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, boolean retryEnabled) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.multiplier = multiplier;
        this.retryEnabled = retryEnabled;
    }

    /** Exponential backoff starting at 200ms, doubling each attempt. */
    public static RetryPolicy exponential(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(200), 2.0, true);
    }

    /** A single attempt with no retries. */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, false);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Backoff to wait before the given 1-based attempt. Attempt 1 waits nothing. */
    public Duration backoffBeforeAttempt(int attemptIndex) {
        if (attemptIndex <= 1) {
            return Duration.ZERO;
        }
        double factor = Math.pow(multiplier, attemptIndex - 2);
        long millis = Math.round(initialBackoff.toMillis() * factor);
        return Duration.ofMillis(millis);
    }

    /** Only HTTP 503 (broker unavailable) is retryable; safe because eventId makes publishes idempotent. */
    public boolean isRetryableStatus(int statusCode) {
        return retryEnabled && statusCode == 503;
    }
}
