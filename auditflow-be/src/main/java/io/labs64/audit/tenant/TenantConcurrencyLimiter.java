package io.labs64.audit.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Layer-2 fairness: a per-tenant in-flight cap so no single tenant monopolizes consumer threads
 *  even within its ingest rate budget. */
@Component
public class TenantConcurrencyLimiter {

    private final int maxInFlight;
    private final ConcurrentHashMap<String, Semaphore> permits = new ConcurrentHashMap<>();

    public TenantConcurrencyLimiter(
            @Value("${tenants.consumer.max-in-flight-per-tenant:4}") int maxInFlight) {
        this.maxInFlight = Math.max(1, maxInFlight);
    }

    public boolean tryAcquire(String tenantId) {
        return permits.computeIfAbsent(tenantId, k -> new Semaphore(maxInFlight)).tryAcquire();
    }

    public void release(String tenantId) {
        Semaphore s = permits.get(tenantId);
        if (s != null) {
            s.release();
        }
    }
}
