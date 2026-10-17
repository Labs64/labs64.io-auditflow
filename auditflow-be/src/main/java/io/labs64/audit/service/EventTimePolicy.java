package io.labs64.audit.service;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import io.labs64.audit.config.EventTimeProperties;
import io.labs64.auditflow.model.AuditEvent;

/**
 * The one place that decides what an event's two times are at ingest.
 *
 * <ul>
 *   <li>{@code timestamp} is the <b>receipt time</b>: AuditFlow's own clock, never the client's.</li>
 *   <li>{@code eventTime} is the <b>event time</b>: when the action happened, as the publisher says. It is
 *       kept as sent, but must be plausible ({@link EventTimeProperties}); when the publisher omits it
 *       it becomes the receipt time, so every consumer downstream finds it set and needs no fallback.</li>
 * </ul>
 */
@Component
public class EventTimePolicy {

    private final EventTimeProperties properties;

    public EventTimePolicy(EventTimeProperties properties) {
        this.properties = properties;
    }

    /**
     * Why the event's {@code eventTime} is not acceptable, or {@code null} when it is acceptable or absent.
     * Call before the event spends quota: an invalid event costs the tenant nothing.
     */
    public String violation(AuditEvent event, OffsetDateTime receivedAt) {
        OffsetDateTime eventTime = event.getEventTime();
        if (eventTime == null) {
            return null;
        }
        if (eventTime.isAfter(receivedAt.plus(properties.getMaxFuture()))) {
            return "eventTime " + eventTime + " is in the future: more than " + properties.getMaxFuture()
                    + " after the receipt time " + receivedAt + ". eventTime is when the action happened;"
                    + " check the publisher's clock, or omit eventTime to use the receipt time";
        }
        if (eventTime.isBefore(receivedAt.minus(properties.getMaxAge()))) {
            return "eventTime " + eventTime + " is too old: more than " + properties.getMaxAge()
                    + " before the receipt time " + receivedAt + ", the oldest event time accepted";
        }
        return null;
    }

    /** Stamp the receipt time, and give an event without {@code eventTime} the receipt time as its event time. */
    public void stamp(AuditEvent event, OffsetDateTime receivedAt) {
        event.setTimestamp(receivedAt);
        if (event.getEventTime() == null) {
            event.setEventTime(receivedAt);
        }
    }
}
