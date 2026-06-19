package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Rate limiting configuration for audit pipelines.
 * Each pipeline can have its own rate limit to prevent event storms.
 */
@ConfigurationProperties(prefix = "auditflow.ratelimit")
public class RateLimitProperties {

    /** Whether rate limiting is enabled globally. */
    private boolean enabled = false;

    /** Default rate limit for pipelines that don't have explicit limits. */
    private int defaultLimitForPeriod = 1000;

    /** Default period for the rate limit. */
    private Duration defaultLimitRefreshPeriod = Duration.ofSeconds(1);

    /** Default timeout when rate limit is exceeded (0 = reject immediately). */
    private Duration defaultTimeoutDuration = Duration.ofMillis(100);

    /** Per-pipeline rate limit overrides. */
    private Map<String, PipelineRateLimit> pipelines = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultLimitForPeriod() {
        return defaultLimitForPeriod;
    }

    public void setDefaultLimitForPeriod(int defaultLimitForPeriod) {
        this.defaultLimitForPeriod = defaultLimitForPeriod;
    }

    public Duration getDefaultLimitRefreshPeriod() {
        return defaultLimitRefreshPeriod;
    }

    public void setDefaultLimitRefreshPeriod(Duration defaultLimitRefreshPeriod) {
        this.defaultLimitRefreshPeriod = defaultLimitRefreshPeriod;
    }

    public Duration getDefaultTimeoutDuration() {
        return defaultTimeoutDuration;
    }

    public void setDefaultTimeoutDuration(Duration defaultTimeoutDuration) {
        this.defaultTimeoutDuration = defaultTimeoutDuration;
    }

    public Map<String, PipelineRateLimit> getPipelines() {
        return pipelines;
    }

    public void setPipelines(Map<String, PipelineRateLimit> pipelines) {
        this.pipelines = pipelines;
    }

    /**
     * Rate limit configuration for a specific pipeline.
     */
    public static class PipelineRateLimit {
        /** Maximum number of events allowed per period. */
        private int limitForPeriod = 1000;

        /** Time period for the limit. */
        private Duration limitRefreshPeriod = Duration.ofSeconds(1);

        /** Timeout when rate limit is exceeded. */
        private Duration timeoutDuration = Duration.ofMillis(100);

        public int getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(int limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public Duration getLimitRefreshPeriod() {
            return limitRefreshPeriod;
        }

        public void setLimitRefreshPeriod(Duration limitRefreshPeriod) {
            this.limitRefreshPeriod = limitRefreshPeriod;
        }

        public Duration getTimeoutDuration() {
            return timeoutDuration;
        }

        public void setTimeoutDuration(Duration timeoutDuration) {
            this.timeoutDuration = timeoutDuration;
        }
    }
}
