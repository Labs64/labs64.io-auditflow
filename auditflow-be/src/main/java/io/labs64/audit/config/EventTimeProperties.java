package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The window an event's {@code eventTime} (when the audited action happened, claimed by the publisher)
 * must fall into, measured against the receipt time AuditFlow assigns ({@code timestamp}). An
 * {@code eventTime} outside it is rejected at ingest with 400, so a publisher with a wrong clock cannot
 * create stray partitions in a sink or rows that expire the moment they are written.
 */
@ConfigurationProperties(prefix = "auditflow.event-time")
public class EventTimeProperties {

    /** How far ahead of the receipt time an {@code eventTime} may be: clock skew between publisher and server. */
    private Duration maxFuture = Duration.ofMinutes(5);

    /**
     * How far behind the receipt time an {@code eventTime} may be: the oldest backfill accepted. The
     * default (1095 days) is the shipped ClickHouse retention; lower it to the shortest retention among
     * the sinks of your tenants.
     */
    private Duration maxAge = Duration.ofDays(1095);

    public Duration getMaxFuture() {
        return maxFuture;
    }

    public void setMaxFuture(Duration maxFuture) {
        this.maxFuture = maxFuture;
    }

    public Duration getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Duration maxAge) {
        this.maxAge = maxAge;
    }
}
