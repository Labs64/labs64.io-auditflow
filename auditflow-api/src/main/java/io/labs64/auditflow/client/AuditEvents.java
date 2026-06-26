package io.labs64.auditflow.client;

import io.labs64.auditflow.model.AuditEvent;

/** Ergonomic builder over the generated {@link AuditEvent} model. */
public final class AuditEvents {

    private AuditEvents() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final AuditEvent event = new AuditEvent();
        private boolean hasExtra;

        public Builder eventType(String eventType) {
            event.setEventType(eventType);
            return this;
        }

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
