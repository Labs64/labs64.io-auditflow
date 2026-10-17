package io.labs64.auditflow.client;

import io.labs64.auditflow.model.AuditEvent;

/** Ergonomic builder over the generated {@link AuditEvent} model. */
public final class AuditEvents {

    private AuditEvents() {
    }

    public static Builder builder(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        Builder b = new Builder();
        b.event.setEventType(eventType);
        return b;
    }

    public static final class Builder {
        private final AuditEvent event = new AuditEvent();
        private boolean hasExtra;

        public Builder sourceSystem(String sourceSystem) {
            event.setSourceSystem(sourceSystem);
            return this;
        }

        public Builder tenantId(String tenantId) {
            event.setTenantId(tenantId);
            return this;
        }

        public Builder correlationId(String correlationId) {
            event.setCorrelationId(correlationId);
            return this;
        }

        public Builder eventId(java.util.UUID eventId) {
            event.setEventId(eventId);
            return this;
        }

        /**
         * The event time: when the audited action happened at the source. Optional: omit it and AuditFlow
         * uses the moment it receives the event. It must be plausible (by default at most 5 minutes ahead of
         * the receipt time and not older than 1095 days), otherwise the server answers 400. This is not the
         * receipt time ({@code timestamp}), which only AuditFlow sets; reports are grouped on the event time.
         */
        public Builder eventTime(java.time.OffsetDateTime eventTime) {
            event.setEventTime(eventTime);
            return this;
        }

        public Builder geolocation(io.labs64.auditflow.model.Geolocation geolocation) {
            event.setGeolocation(geolocation);
            return this;
        }

        public Builder extra(String key, Object value) {
            event.putExtraItem(key, value);
            hasExtra = true;
            return this;
        }

        public AuditEvent build() {
            if (!hasExtra) {
                event.setExtra(null);
            }
            return event;
        }
    }
}
