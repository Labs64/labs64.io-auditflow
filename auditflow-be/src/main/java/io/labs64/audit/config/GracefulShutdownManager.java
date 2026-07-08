package io.labs64.audit.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Manages graceful shutdown of the audit consumer.
 * Drains in-flight events before allowing the application to stop.
 */
@Component
public class GracefulShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private static final long DRAIN_TIMEOUT_SECONDS = 25; // Leave buffer for Spring's 30s timeout

    private final ConsumerHealthIndicator consumerHealthIndicator;

    public GracefulShutdownManager(ConsumerHealthIndicator consumerHealthIndicator) {
        this.consumerHealthIndicator = consumerHealthIndicator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("Application ready. Graceful shutdown manager active (drain timeout: {}s).", DRAIN_TIMEOUT_SECONDS);
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Shutdown requested. Draining in-flight events (timeout: {}s)...", DRAIN_TIMEOUT_SECONDS);
        consumerHealthIndicator.drainInFlightEvents(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        logger.info("Graceful shutdown complete.");
    }
}
