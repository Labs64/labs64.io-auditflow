package io.labs64.audit.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Health indicator that exposes consumer lag metrics, processing rate, and in-flight event tracking.
 * Supports graceful shutdown by tracking in-flight events and providing a drain mechanism.
 */
@Component
public class ConsumerHealthIndicator implements HealthIndicator {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerHealthIndicator.class);

    private final MeterRegistry meterRegistry;
    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong eventsFailed = new AtomicLong(0);
    private final AtomicLong eventsInFlight = new AtomicLong(0);
    private final AtomicLong lastEventTimestamp = new AtomicLong(0);

    private volatile boolean shutdownRequested = false;

    public ConsumerHealthIndicator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        registerMetrics();
    }

    private void registerMetrics() {
        Gauge.builder("auditflow.consumer.events.processed", eventsProcessed, AtomicLong::doubleValue)
                .description("Total number of events processed by the consumer")
                .register(meterRegistry);

        Gauge.builder("auditflow.consumer.events.failed", eventsFailed, AtomicLong::doubleValue)
                .description("Total number of events that failed processing")
                .register(meterRegistry);

        Gauge.builder("auditflow.consumer.events.inflight", eventsInFlight, AtomicLong::doubleValue)
                .description("Number of events currently being processed")
                .register(meterRegistry);

        Gauge.builder("auditflow.consumer.last.event.timestamp", lastEventTimestamp, AtomicLong::doubleValue)
                .description("Timestamp of the last processed event")
                .register(meterRegistry);
    }

    /**
     * Mark an event as starting processing. Must be paired with recordEventProcessed or recordEventFailed.
     */
    public void recordEventStarted() {
        eventsInFlight.incrementAndGet();
    }

    public void recordEventProcessed() {
        eventsInFlight.decrementAndGet();
        eventsProcessed.incrementAndGet();
        lastEventTimestamp.set(System.currentTimeMillis());
    }

    public void recordEventFailed() {
        eventsInFlight.decrementAndGet();
        eventsFailed.incrementAndGet();
    }

    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    public long getEventsFailed() {
        return eventsFailed.get();
    }

    public long getEventsInFlight() {
        return eventsInFlight.get();
    }

    public long getLastEventTimestamp() {
        return lastEventTimestamp.get();
    }

    /**
     * Request shutdown and wait for in-flight events to complete.
     *
     * @param timeout maximum time to wait for drain
     * @return true if all in-flight events completed, false if timeout occurred
     */
    public boolean drainInFlightEvents(long timeout, TimeUnit unit) {
        shutdownRequested = true;
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while (eventsInFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        if (eventsInFlight.get() > 0) {
            logger.warn("Drain timeout reached with {} events still in flight", eventsInFlight.get());
            return false;
        }

        logger.info("All in-flight events drained successfully");
        return true;
    }

    public boolean isShutdownRequested() {
        return shutdownRequested;
    }

    @Override
    public Health health() {
        long lastEvent = lastEventTimestamp.get();
        long currentTime = System.currentTimeMillis();
        long timeSinceLastEvent = currentTime - lastEvent;

        Health.Builder builder = Health.up();
        builder.withDetail("eventsProcessed", eventsProcessed.get());
        builder.withDetail("eventsFailed", eventsFailed.get());
        builder.withDetail("eventsInFlight", eventsInFlight.get());
        builder.withDetail("lastEventTimestamp", lastEvent);
        builder.withDetail("shutdownRequested", shutdownRequested);

        // Consider unhealthy if no events processed in last 5 minutes (when events are expected)
        if (lastEvent > 0 && timeSinceLastEvent > 300000) {
            builder.down();
            builder.withDetail("timeSinceLastEventMs", timeSinceLastEvent);
        }

        // During shutdown, report down if events are still in flight
        if (shutdownRequested && eventsInFlight.get() > 0) {
            builder.down();
        }

        return builder.build();
    }
}
