package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTenantRateLimiterTest {

    @Test
    void allowsBurstThenThrottles() {
        AtomicLong clock = new AtomicLong(0);
        var limiter = new InMemoryTenantRateLimiter(clock::get);
        // burst=3 -> first 3 pass at the same instant, 4th is rejected
        assertTrue(limiter.tryAcquire("acme", 10, 3));
        assertTrue(limiter.tryAcquire("acme", 10, 3));
        assertTrue(limiter.tryAcquire("acme", 10, 3));
        assertFalse(limiter.tryAcquire("acme", 10, 3));
    }

    @Test
    void refillsOverTime() {
        AtomicLong clock = new AtomicLong(0);
        var limiter = new InMemoryTenantRateLimiter(clock::get);
        assertTrue(limiter.tryAcquire("acme", 10, 1));
        assertFalse(limiter.tryAcquire("acme", 10, 1));
        clock.addAndGet(200_000_000L); // 0.2s at 10/s => 2 tokens (capped at burst=1)
        assertTrue(limiter.tryAcquire("acme", 10, 1));
    }

    @Test
    void tenantsAreIndependent() {
        AtomicLong clock = new AtomicLong(0);
        var limiter = new InMemoryTenantRateLimiter(clock::get);
        assertTrue(limiter.tryAcquire("acme", 1, 1));
        assertFalse(limiter.tryAcquire("acme", 1, 1));
        assertTrue(limiter.tryAcquire("globex", 1, 1), "globex bucket is separate");
    }

    @Test
    void nonPositiveRateMeansUnlimited() {
        var limiter = new InMemoryTenantRateLimiter(() -> 0L);
        assertTrue(limiter.tryAcquire("acme", 0, 0));
        assertTrue(limiter.tryAcquire("acme", 0, 0));
    }
}
