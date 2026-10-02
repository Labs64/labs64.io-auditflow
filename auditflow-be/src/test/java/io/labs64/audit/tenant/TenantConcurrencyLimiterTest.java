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

    @Test
    void overTheCapWaitsForASlotInsteadOfFailing() throws Exception {
        var limiter = new TenantConcurrencyLimiter(1, 2000);
        assertTrue(limiter.tryAcquire("acme"));
        var releaser = new Thread(() -> {
            sleep(100);
            limiter.release("acme");
        });
        releaser.start();
        long started = System.nanoTime();
        assertTrue(limiter.tryAcquire("acme"), "the waiting acquire gets the released slot");
        assertTrue(System.nanoTime() - started < 1_500_000_000L);
        releaser.join();
    }

    @Test
    void waitIsBounded() {
        var limiter = new TenantConcurrencyLimiter(1, 100);
        assertTrue(limiter.tryAcquire("acme"));
        long started = System.nanoTime();
        assertFalse(limiter.tryAcquire("acme"), "no slot within the wait");
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(waitedMillis >= 90 && waitedMillis < 1000, "waited " + waitedMillis + " ms");
    }

    @Test
    void waitingForOneTenantDoesNotBlockAnother() {
        var limiter = new TenantConcurrencyLimiter(1, 5000);
        assertTrue(limiter.tryAcquire("acme"));
        long started = System.nanoTime();
        assertTrue(limiter.tryAcquire("globex"));
        assertTrue(System.nanoTime() - started < 100_000_000L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
