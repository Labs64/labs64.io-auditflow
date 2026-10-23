package io.labs64.audit.broker;

import java.time.Duration;
import java.util.Objects;

/**
 * Where a message goes, in the application's terms. Each adapter maps a destination to its own
 * objects (RabbitMQ: exchange + routing key; SQS: queue + delivery delay).
 *
 * <pre>
 * INGEST      accepted events, consumed by the router
 * INGEST_DLQ  ingest messages the router could not handle after its retries (the legacy shared DLQ)
 * DELIVERY    one message per event and pipeline, consumed by the delivery worker
 * DELAY       back to DELIVERY after {@link #delay()} (retry, backpressure)
 * TENANT_DLQ  one tenant's dead-lettered deliveries
 * QUARANTINE  events that cannot be evaluated (unparseable, tenant not routable)
 * </pre>
 */
public record Destination(Kind kind, Duration delay, String tenantId) {

    public enum Kind {
        INGEST, INGEST_DLQ, DELIVERY, DELAY, TENANT_DLQ, QUARANTINE
    }

    public Destination {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.DELAY && (delay == null || delay.isNegative())) {
            throw new IllegalArgumentException("DELAY needs a non-negative delay");
        }
        if (kind == Kind.TENANT_DLQ && (tenantId == null || tenantId.isBlank())) {
            throw new IllegalArgumentException("TENANT_DLQ needs a tenant id");
        }
    }

    private static final Destination INGEST = new Destination(Kind.INGEST, null, null);
    private static final Destination INGEST_DLQ = new Destination(Kind.INGEST_DLQ, null, null);
    private static final Destination DELIVERY = new Destination(Kind.DELIVERY, null, null);
    private static final Destination QUARANTINE = new Destination(Kind.QUARANTINE, null, null);

    public static Destination ingest() {
        return INGEST;
    }

    public static Destination ingestDlq() {
        return INGEST_DLQ;
    }

    public static Destination delivery() {
        return DELIVERY;
    }

    public static Destination delay(Duration delay) {
        return new Destination(Kind.DELAY, delay, null);
    }

    public static Destination tenantDlq(String tenantId) {
        return new Destination(Kind.TENANT_DLQ, null, tenantId);
    }

    public static Destination quarantine() {
        return QUARANTINE;
    }
}
