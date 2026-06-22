package io.labs64.audit.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages rate limiters for audit pipelines.
 * Creates and caches rate limiters per pipeline with configurable limits.
 */
@Component
public class PipelineRateLimiterRegistry {

    private static final Logger logger = LoggerFactory.getLogger(PipelineRateLimiterRegistry.class);

    private final RateLimitProperties rateLimitProperties;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, RateLimiter> pipelineRateLimiters = new ConcurrentHashMap<>();

    public PipelineRateLimiterRegistry(RateLimitProperties rateLimitProperties,
                                       RateLimiterRegistry rateLimiterRegistry,
                                       MeterRegistry meterRegistry) {
        this.rateLimitProperties = rateLimitProperties;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        if (!rateLimitProperties.isEnabled()) {
            logger.info("Pipeline rate limiting is disabled");
            return;
        }

        // Register rate limiters for configured pipelines
        rateLimitProperties.getPipelines().forEach((pipelineName, config) -> {
            RateLimiter rateLimiter = createRateLimiter(pipelineName, config);
            pipelineRateLimiters.put(pipelineName, rateLimiter);
            registerMetrics(pipelineName, rateLimiter);
            logger.info("Created rate limiter for pipeline '{}': {} events/{}",
                    pipelineName, config.getLimitForPeriod(), config.getLimitRefreshPeriod());
        });

        logger.info("Pipeline rate limiting initialized with {} configured pipelines",
                pipelineRateLimiters.size());
    }

    /**
     * Get or create a rate limiter for a pipeline.
     * Uses default limits if no pipeline-specific config exists.
     */
    public RateLimiter getOrCreateRateLimiter(String pipelineName) {
        if (!rateLimitProperties.isEnabled()) {
            return null;
        }

        return pipelineRateLimiters.computeIfAbsent(pipelineName, name -> {
            RateLimiter rateLimiter = createDefaultRateLimiter(name);
            registerMetrics(name, rateLimiter);
            logger.debug("Created default rate limiter for pipeline '{}'", name);
            return rateLimiter;
        });
    }

    /**
     * Check if a pipeline event should be allowed.
     * Returns true if allowed, false if rate limit exceeded.
     */
    public boolean tryAcquirePermission(String pipelineName) {
        RateLimiter rateLimiter = getOrCreateRateLimiter(pipelineName);
        if (rateLimiter == null) {
            return true; // Rate limiting disabled
        }

        boolean permitted = rateLimiter.acquirePermission();
        if (!permitted) {
            logger.warn("Rate limit exceeded for pipeline '{}'", pipelineName);
        }
        return permitted;
    }

    private RateLimiter createRateLimiter(String pipelineName, RateLimitProperties.PipelineRateLimit config) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(config.getLimitForPeriod())
                .limitRefreshPeriod(config.getLimitRefreshPeriod())
                .timeoutDuration(config.getTimeoutDuration())
                .build();

        return rateLimiterRegistry.rateLimiter(pipelineName, rateLimiterConfig);
    }

    private RateLimiter createDefaultRateLimiter(String pipelineName) {
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitForPeriod(rateLimitProperties.getDefaultLimitForPeriod())
                .limitRefreshPeriod(rateLimitProperties.getDefaultLimitRefreshPeriod())
                .timeoutDuration(rateLimitProperties.getDefaultTimeoutDuration())
                .build();

        return rateLimiterRegistry.rateLimiter(pipelineName, rateLimiterConfig);
    }

    private void registerMetrics(String pipelineName, RateLimiter rateLimiter) {
        Gauge.builder("auditflow.ratelimiter.available.permissions", rateLimiter,
                        rl -> rl.getMetrics().getAvailablePermissions())
                .description("Available permissions for the rate limiter")
                .tag("pipeline", pipelineName)
                .register(meterRegistry);

        Gauge.builder("auditflow.ratelimiter.waiting.threads", rateLimiter,
                        rl -> rl.getMetrics().getNumberOfWaitingThreads())
                .description("Number of waiting threads for the rate limiter")
                .tag("pipeline", pipelineName)
                .register(meterRegistry);
    }
}
