package io.labs64.audit.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class BusinessTelemetryTenantTest {

    @Test
    void noopTenantEventDoesNotThrow() {
        BusinessTelemetry t = new NoopBusinessTelemetry();
        assertDoesNotThrow(() -> t.tenantEvent("acme", "gitops-configmap", "routed"));
    }

    @Test
    void otelTenantEventDoesNotThrowWithoutAgent() {
        BusinessTelemetry t = new OtelBusinessTelemetry();
        assertDoesNotThrow(() -> t.tenantEvent("acme", "gitops-configmap", "rejected:disabled"));
    }
}
