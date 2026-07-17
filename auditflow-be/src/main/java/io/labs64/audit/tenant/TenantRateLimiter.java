package io.labs64.audit.tenant;

/** Ingest-time per-tenant fairness: a token bucket rejecting floods before the shared broker. */
public interface TenantRateLimiter {
    /** @return true if a token was available for {@code tenantId}; false if over its budget. */
    boolean tryAcquire(String tenantId, int ratePerSec, int burst);
}
