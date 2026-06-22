package io.labs64.audit.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Exports Resilience4j circuit breaker states as Prometheus metrics.
 * Provides visibility into circuit breaker health for monitoring and alerting.
 */
@Component
public class CircuitBreakerMetricsExporter {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerMetricsExporter.class);

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final MeterRegistry meterRegistry;

    public CircuitBreakerMetricsExporter(CircuitBreakerRegistry circuitBreakerRegistry,
                                         MeterRegistry meterRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerMetrics() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
            String name = circuitBreaker.getName();

            // State gauge: CLOSED=0, OPEN=1, HALF_OPEN=2, DISABLED=3, FORCED_OPEN=4, METRICS_ONLY=5
            Gauge.builder("auditflow.circuitbreaker.state", circuitBreaker, cb -> {
                        return switch (cb.getState()) {
                            case CLOSED -> 0.0;
                            case OPEN -> 1.0;
                            case HALF_OPEN -> 2.0;
                            case DISABLED -> 3.0;
                            case FORCED_OPEN -> 4.0;
                            case METRICS_ONLY -> 5.0;
                        };
                    })
                    .description("Circuit breaker state (0=closed, 1=open, 2=half-open)")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            // Failure rate gauge
            Gauge.builder("auditflow.circuitbreaker.failure.rate", circuitBreaker,
                            cb -> cb.getMetrics().getFailureRate())
                    .description("Current failure rate percentage")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            // Slow call rate gauge
            Gauge.builder("auditflow.circuitbreaker.slow.call.rate", circuitBreaker,
                            cb -> cb.getMetrics().getSlowCallRate())
                    .description("Current slow call rate percentage")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            // Successful calls counter
            Gauge.builder("auditflow.circuitbreaker.successful.calls", circuitBreaker,
                            cb -> cb.getMetrics().getNumberOfSuccessfulCalls())
                    .description("Number of successful calls")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            // Failed calls counter
            Gauge.builder("auditflow.circuitbreaker.failed.calls", circuitBreaker,
                            cb -> cb.getMetrics().getNumberOfFailedCalls())
                    .description("Number of failed calls")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            // Not permitted calls counter (when circuit is open)
            Gauge.builder("auditflow.circuitbreaker.not.permitted.calls", circuitBreaker,
                            cb -> cb.getMetrics().getNumberOfNotPermittedCalls())
                    .description("Number of calls rejected by the circuit breaker")
                    .tag("circuitbreaker", name)
                    .register(meterRegistry);

            logger.debug("Registered circuit breaker metrics for '{}'", name);
        });

        logger.info("Circuit breaker metrics registered for {} circuit breakers",
                circuitBreakerRegistry.getAllCircuitBreakers().size());
    }
}
