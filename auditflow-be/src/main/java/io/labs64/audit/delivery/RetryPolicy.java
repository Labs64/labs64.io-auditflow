package io.labs64.audit.delivery;

import java.time.Duration;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.RetryProperties;

/** Effective retry policy of one pipeline: its own {@code retry} settings over the global defaults. */
public record RetryPolicy(int maxAttempts, Duration maxAge) {

    public static RetryPolicy of(PipelineProperties pipeline, RetryPolicy defaults) {
        RetryProperties retry = pipeline == null ? null : pipeline.getRetry();
        if (retry == null) {
            return defaults;
        }
        int attempts = retry.getMaxAttempts() != null ? retry.getMaxAttempts() : defaults.maxAttempts();
        Duration age = retry.maxAgeDuration() != null ? retry.maxAgeDuration() : defaults.maxAge();
        return new RetryPolicy(attempts, age);
    }

    /** Delay before the next attempt after {@code failedAttempts} failures (1-based): 5s, 30s, 2m … 3h. */
    public static Duration delayAfter(int failedAttempts) {
        int index = Math.max(0, Math.min(failedAttempts - 1, BrokerTopology.TIERS.size() - 1));
        return BrokerTopology.TIERS.get(index);
    }

    /** Delay used when backpressure defers a delivery (no attempt spent). */
    public static Duration throttleDelay() {
        return BrokerTopology.TIERS.get(0);
    }

    /** True when the next attempt would come after the age limit, so retrying is pointless. */
    public boolean ageExceeded(long firstEnqueuedAtMillis, long nowMillis, Duration nextDelay) {
        return nowMillis + nextDelay.toMillis() - firstEnqueuedAtMillis > maxAge.toMillis();
    }
}
