package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Retry/backoff configuration for the outbound HTTP calls to the transformer and sink services.
 * Bound from the {@code auditflow.http.retry} prefix and shared by both services.
 */
@ConfigurationProperties(prefix = "auditflow.http.retry")
public class HttpRetryProperties {

    /** Whether transient-failure retries are applied at all. */
    private boolean enabled = true;

    /** Number of retry attempts after the initial try (total attempts = 1 + maxAttempts). */
    private int maxAttempts = 3;

    /** Initial backoff before the first retry; subsequent retries back off exponentially with jitter. */
    private Duration minBackoff = Duration.ofMillis(200);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getMinBackoff() {
        return minBackoff;
    }

    public void setMinBackoff(Duration minBackoff) {
        this.minBackoff = minBackoff;
    }
}
