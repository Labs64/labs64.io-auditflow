package io.labs64.audit.tenant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Layer-2 fairness: a per-tenant in-flight cap so no single tenant monopolizes consumer threads
 *  even within its ingest rate budget.
 *
 *  <p>A consumer thread over the cap waits up to {@code tenants.consumer.max-wait-millis} for a slot.
 *  Rejecting at once made the caller throw a retryable error, so every event above the cap burned
 *  its redelivery attempts within seconds and was dead-lettered while the tenant's own in-flight
 *  events were about to finish (load test: 147 events in the DLQ, no pipeline failure). The wait
 *  is bounded so a waiting thread is handed back to other tenants. */
@Component
public class TenantConcurrencyLimiter {

    private final int maxInFlight;
    private final long maxWaitMillis;
    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

    @Autowired
    public TenantConcurrencyLimiter(
            @Value("${tenants.consumer.max-in-flight-per-tenant:4}") int maxInFlight,
            @Value("${tenants.consumer.max-wait-millis:2000}") long maxWaitMillis) {
        this.maxInFlight = Math.max(1, maxInFlight);
        this.maxWaitMillis = Math.max(0, maxWaitMillis);
    }

    /** A limiter that never waits: over the cap, {@link #tryAcquire} answers false at once. */
    public TenantConcurrencyLimiter(int maxInFlight) {
        this(maxInFlight, 0);
    }

    /** True once the tenant has a free slot, waiting at most {@code max-wait-millis} for one. */
    public boolean tryAcquire(String tenantId) {
        Semaphore semaphore = permits.computeIfAbsent(tenantId, k -> new Semaphore(maxInFlight));
        if (semaphore.tryAcquire()) {
            return true;
        }
        if (maxWaitMillis == 0) {
            return false;
        }
        try {
            return semaphore.tryAcquire(maxWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release(String tenantId) {
        Semaphore s = permits.get(tenantId);
        if (s != null) {
            s.release();
        }
    }
}
