package io.labs64.audit.telemetry;

/** No-op implementation — selected when business telemetry is explicitly disabled. */
public class NoopBusinessTelemetry implements BusinessTelemetry {

    @Override
    public void auditEventReceived(String eventId, String eventType) {
        // intentionally empty
    }

    @Override
    public void pipelineCompleted(String pipelineName, String outcome) {
        // intentionally empty
    }

    @Override
    public void tenantEvent(String tenantId, String provider, String outcome) {
        // intentionally empty
    }
}
