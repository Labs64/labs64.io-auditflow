package io.labs64.audit.tenant;

import io.labs64.audit.exception.TenantDisabledException;
import io.labs64.audit.exception.TenantNotProvisionedException;
import io.labs64.audit.exception.TenantRateLimitedException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantGateTest {

    private TenantConfig enabled(String id) {
        return new TenantConfig(id, true, new TenantConfig.Quota(200, 400), List.of());
    }

    private TenantGate gate(TenantPipelineRegistry reg, TenantRateLimiter limiter) {
        return new TenantGate(reg, limiter);
    }

    @Test
    void unprovisionedTenantThrowsNotProvisioned() {
        TenantGate gate = gate(new TenantPipelineRegistry(), (t, r, b) -> true);
        assertThrows(TenantNotProvisionedException.class, () -> gate.check("acme"));
    }

    @Test
    void disabledTenantThrowsDisabled() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(new TenantConfig("acme", false, new TenantConfig.Quota(200, 400), List.of()), "t");
        TenantGate gate = gate(reg, (t, r, b) -> true);
        assertThrows(TenantDisabledException.class, () -> gate.check("acme"));
    }

    @Test
    void overQuotaThrowsRateLimited() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(enabled("acme"), "t");
        TenantGate gate = gate(reg, (t, r, b) -> false);
        TenantRateLimitedException ex =
                assertThrows(TenantRateLimitedException.class, () -> gate.check("acme"));
        assertEquals(1, ex.getRetryAfterSeconds());
    }

    @Test
    void provisionedWithinQuotaPasses() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(enabled("acme"), "t");
        TenantGate gate = gate(reg, (t, r, b) -> true);
        assertDoesNotThrow(() -> gate.check("acme"));
    }

    @Test
    void tenantlessEventResolvesToPlatform() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(enabled(TenantIds.PLATFORM), "t");
        TenantGate gate = gate(reg, (t, r, b) -> true);
        assertDoesNotThrow(() -> gate.check(null));
        assertDoesNotThrow(() -> gate.check("-"));
    }
}
