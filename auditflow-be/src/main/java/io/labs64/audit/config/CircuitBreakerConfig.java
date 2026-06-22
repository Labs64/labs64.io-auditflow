package io.labs64.audit.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the reactive circuit breakers guarding the transformer/sink calls.
 *
 * <p>The time-limiter timeout is set well above the WebClient response timeout (10s) on purpose:
 * Spring Cloud CircuitBreaker wraps every call in a {@code TimeLimiter} whose default is 1s, which
 * would otherwise cancel healthy in-flight calls before they complete.</p>
 */
@Configuration
public class CircuitBreakerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "auditflow.circuitbreaker", name = "enabled", havingValue = "true", matchIfMissing = true)
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer(
            CircuitBreakerProperties properties) {
        return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                        .slidingWindowType(SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(properties.getSlidingWindowSize())
                        .minimumNumberOfCalls(properties.getMinimumNumberOfCalls())
                        .failureRateThreshold(properties.getFailureRateThreshold())
                        .waitDurationInOpenState(properties.getWaitDurationInOpenState())
                        .permittedNumberOfCallsInHalfOpenState(properties.getPermittedNumberOfCallsInHalfOpenState())
                        .slowCallRateThreshold(properties.getSlowCallRateThreshold())
                        .slowCallDurationThreshold(properties.getSlowCallDurationThreshold())
                        .build())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(properties.getTimeLimiterTimeout())
                        .build())
                .build());
    }
}
