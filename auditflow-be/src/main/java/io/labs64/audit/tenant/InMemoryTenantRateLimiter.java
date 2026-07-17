package io.labs64.audit.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Single-replica token bucket — the BUILT-IN DEFAULT backend, so Core boots without Redis
 * (the Helm chart pins {@code redis} for multi-replica correctness). Deterministic under
 * test via an injectable nano clock.
 */
@Component
@ConditionalOnProperty(name = "tenants.ratelimit.backend", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryTenantRateLimiter implements TenantRateLimiter {

    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryTenantRateLimiter() {
        this(System::nanoTime);
    }

    InMemoryTenantRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    @Override
    public boolean tryAcquire(String tenantId, int ratePerSec, int burst) {
        if (ratePerSec <= 0) {
            return true; // no limit configured
        }
        Bucket b = buckets.computeIfAbsent(tenantId, k -> {
            Bucket nb = new Bucket();
            nb.tokens = burst;
            nb.lastNanos = nanoClock.getAsLong();
            return nb;
        });
        synchronized (b) {
            long now = nanoClock.getAsLong();
            double elapsedSec = (now - b.lastNanos) / 1_000_000_000.0;
            b.lastNanos = now;
            b.tokens = Math.min(burst, b.tokens + elapsedSec * ratePerSec);
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
