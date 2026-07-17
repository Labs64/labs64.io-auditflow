package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantConcurrencyLimiterTest {

    @Test
    void capsInFlightPerTenantAndReleaseRestores() {
        var limiter = new TenantConcurrencyLimiter(2);
        assertTrue(limiter.tryAcquire("acme"));
        assertTrue(limiter.tryAcquire("acme"));
        assertFalse(limiter.tryAcquire("acme"), "third concurrent acquire exceeds cap");
        limiter.release("acme");
        assertTrue(limiter.tryAcquire("acme"), "release frees a permit");
    }

    @Test
    void tenantsAreIndependent() {
        var limiter = new TenantConcurrencyLimiter(1);
        assertTrue(limiter.tryAcquire("acme"));
        assertTrue(limiter.tryAcquire("globex"));
    }

    @Test
    void nonPositiveCapIsClampedToOne() {
        var limiter = new TenantConcurrencyLimiter(0);
        assertTrue(limiter.tryAcquire("acme"), "cap is clamped to at least 1");
        assertFalse(limiter.tryAcquire("acme"));
    }
}
