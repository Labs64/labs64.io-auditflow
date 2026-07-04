package io.labs64.audit.telemetry;

/**
 * Thin business-observability abstraction. Business code depends only on this interface —
 * never on OpenTelemetry APIs. Infrastructure telemetry (HTTP, AMQP, JDBC, JVM) is produced
 * by runtime auto-instrumentation and is intentionally NOT mirrored here; this interface
 * carries only domain signals that auto-instrumentation cannot derive.
 */
public interface BusinessTelemetry {

    /** An audit event was accepted for pipeline processing. */
    void auditEventReceived(String eventId, String eventType);

    /** A pipeline reached a terminal outcome (SUCCESS, SKIPPED, POISON, RETRYABLE_FAILURE). */
    void pipelineCompleted(String pipelineName, String outcome);
}
