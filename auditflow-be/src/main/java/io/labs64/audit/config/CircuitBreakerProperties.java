package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Circuit breaker configuration for outbound HTTP calls to transformer and sink services.
 * Bound from the {@code auditflow.circuitbreaker} prefix.
 */
@ConfigurationProperties(prefix = "auditflow.circuitbreaker")
public class CircuitBreakerProperties {

    /** Whether circuit breaker is enabled. */
    private boolean enabled = true;

    /** Sliding window size for failure rate calculation. */
    private int slidingWindowSize = 20;

    /** Minimum number of calls before circuit breaker can trip. */
    private int minimumNumberOfCalls = 10;

    /** Failure rate threshold percentage to trip the circuit breaker. */
    private float failureRateThreshold = 50.0f;

    /** Duration to wait in open state before transitioning to half-open. */
    private Duration waitDurationInOpenState = Duration.ofSeconds(15);

    /** Number of permitted calls in half-open state. */
    private int permittedNumberOfCallsInHalfOpenState = 3;

    /** Slow call rate threshold percentage. */
    private float slowCallRateThreshold = 100.0f;

    /** Slow call duration threshold. */
    private Duration slowCallDurationThreshold = Duration.ofSeconds(2);

    /** Timeout for the time limiter (should be higher than WebClient timeout). */
    private Duration timeLimiterTimeout = Duration.ofSeconds(15);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public Duration getWaitDurationInOpenState() {
        return waitDurationInOpenState;
    }

    public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
        this.waitDurationInOpenState = waitDurationInOpenState;
    }

    public int getPermittedNumberOfCallsInHalfOpenState() {
        return permittedNumberOfCallsInHalfOpenState;
    }

    public void setPermittedNumberOfCallsInHalfOpenState(int permittedNumberOfCallsInHalfOpenState) {
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }

    public float getSlowCallRateThreshold() {
        return slowCallRateThreshold;
    }

    public void setSlowCallRateThreshold(float slowCallRateThreshold) {
        this.slowCallRateThreshold = slowCallRateThreshold;
    }

    public Duration getSlowCallDurationThreshold() {
        return slowCallDurationThreshold;
    }

    public void setSlowCallDurationThreshold(Duration slowCallDurationThreshold) {
        this.slowCallDurationThreshold = slowCallDurationThreshold;
    }

    public Duration getTimeLimiterTimeout() {
        return timeLimiterTimeout;
    }

    public void setTimeLimiterTimeout(Duration timeLimiterTimeout) {
        this.timeLimiterTimeout = timeLimiterTimeout;
    }
}
