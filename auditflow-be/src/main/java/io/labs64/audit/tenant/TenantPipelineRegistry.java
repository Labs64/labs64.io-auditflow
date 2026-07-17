package io.labs64.audit.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The delivery-isolation boundary. Holds an immutable {@code tenantId → TenantConfig} map and
 * answers exactly one routing question: {@link #pipelinesFor(String)}. There is NO global pipeline
 * list and NO default/fallback set other than the explicitly named {@code _platform} tenant.
 *
 * <p>Each entry is an immutable {@link TenantConfig}; {@code upsert}/{@code remove} on the backing
 * {@link ConcurrentHashMap} swap a single tenant atomically without affecting any other tenant.</p>
 */
@Component
public class TenantPipelineRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TenantPipelineRegistry.class);

    private record Entry(TenantConfig config, String providerId) {
    }

    private final ConcurrentHashMap<String, Entry> tenants = new ConcurrentHashMap<>();

    /** Replace the whole snapshot for one provider (initial load). Tenants owned by this provider
     *  but absent from {@code configs} are removed; other providers' tenants are untouched. */
    public void replaceAll(Collection<TenantConfig> configs, String providerId) {
        tenants.entrySet().removeIf(e -> providerId.equals(e.getValue().providerId()));
        for (TenantConfig cfg : configs) {
            upsert(cfg, providerId);
        }
        logger.info("Tenant registry snapshot from provider '{}': {} tenant(s)", providerId, configs.size());
    }

    public void upsert(TenantConfig config, String providerId) {
        String canonical = TenantIds.resolve(config.tenantId());
        // §4.1 collision rejection: k8sName is lossy; two canonical ids sharing a sanitized name
        // would share Secret/ConfigMap names — a guardrail #10 breach. First claimant wins.
        String sanitized = TenantIds.k8sName(canonical);
        boolean collides = tenants.keySet().stream()
                .anyMatch(existing -> !existing.equals(canonical) && sanitized.equals(TenantIds.k8sName(existing)));
        if (collides) {
            logger.error("REJECTED tenant '{}' (provider '{}'): k8sName '{}' collides with an existing tenant",
                    canonical, providerId, sanitized);
            return;
        }
        tenants.put(canonical, new Entry(config, providerId));
        logger.info("Tenant '{}' provisioned via '{}' (enabled={}, pipelines={})",
                config.tenantId(), providerId, config.enabled(), config.pipelines().size());
    }

    public void remove(String tenantId) {
        Entry removed = tenants.remove(TenantIds.resolve(tenantId));
        if (removed != null) {
            logger.info("Tenant '{}' offboarded (config removed)", tenantId);
        }
    }

    public TenantState stateFor(String tenantId) {
        Entry entry = tenants.get(TenantIds.resolve(tenantId));
        if (entry == null) {
            return TenantState.UNPROVISIONED;
        }
        return entry.config().enabled() ? TenantState.PROVISIONED : TenantState.DISABLED;
    }

    /** The tenant's pipeline set — present ONLY when PROVISIONED. Unknown or disabled → empty. */
    public Optional<PipelineSet> pipelinesFor(String tenantId) {
        Entry entry = tenants.get(TenantIds.resolve(tenantId));
        if (entry == null || !entry.config().enabled()) {
            return Optional.empty();
        }
        TenantConfig cfg = entry.config();
        return Optional.of(new PipelineSet(cfg.tenantId(), cfg.quota(), cfg.pipelines()));
    }

    /** The provider that currently owns this tenant (for provenance), or {@code null}. */
    public String providerFor(String tenantId) {
        Entry entry = tenants.get(TenantIds.resolve(tenantId));
        return entry == null ? null : entry.providerId();
    }
}
