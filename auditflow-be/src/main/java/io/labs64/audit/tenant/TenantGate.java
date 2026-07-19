package io.labs64.audit.tenant;

import io.labs64.audit.exception.TenantDisabledException;
import io.labs64.audit.exception.TenantNotProvisionedException;
import io.labs64.audit.exception.TenantRateLimitedException;
import io.labs64.audit.telemetry.BusinessTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Ordered ingest gate at {@code publishEvent}: (1) authz runs earlier via {@code @Authorize};
 * (2) provisioning — tenant must be PROVISIONED; (3) quota — per-tenant token bucket. Uses the same
 * registry as routing so the ingest gate and the delivery boundary can never disagree.
 */
@Component
public class TenantGate {

    private static final long RETRY_AFTER_SECONDS = 1;

    private final TenantPipelineRegistry registry;
    private final TenantRateLimiter rateLimiter;
    private final BusinessTelemetry businessTelemetry;
    private final MeterRegistry meterRegistry;

    public TenantGate(TenantPipelineRegistry registry, TenantRateLimiter rateLimiter,
                      BusinessTelemetry businessTelemetry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.rateLimiter = rateLimiter;
        this.businessTelemetry = businessTelemetry;
        this.meterRegistry = meterRegistry;
    }

    public void check(String rawTenantId) {
        String tenantId = TenantIds.resolve(rawTenantId);
        var set = registry.pipelinesFor(tenantId);
        if (set.isEmpty()) {
            switch (registry.stateFor(tenantId)) {
                case DISABLED -> {
                    emit(tenantId, "rejected:disabled");
                    throw new TenantDisabledException("Tenant '" + tenantId + "' is disabled");
                }
                default -> {
                    emit(tenantId, "rejected:not_provisioned");
                    throw new TenantNotProvisionedException("Tenant '" + tenantId + "' is not provisioned");
                }
            }
        }
        TenantConfig.Quota quota = set.get().quota();
        if (!rateLimiter.tryAcquire(tenantId, quota.rateLimitPerSec(), quota.burst())) {
            emit(tenantId, "rejected:rate_limited");
            throw new TenantRateLimitedException(
                    "Tenant '" + tenantId + "' exceeded its ingest rate limit", RETRY_AFTER_SECONDS);
        }
    }

    private void emit(String tenantId, String outcome) {
        String provider = String.valueOf(registry.providerFor(tenantId));
        businessTelemetry.tenantEvent(tenantId, provider, outcome);
        meterRegistry.counter("auditflow.tenant.events",
                "tenant", tenantId, "provider", provider, "outcome", outcome).increment();
    }
}
