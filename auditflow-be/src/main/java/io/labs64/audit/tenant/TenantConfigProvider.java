package io.labs64.audit.tenant;

import java.io.Closeable;
import java.util.Collection;
import java.util.Set;
import java.util.function.Consumer;

/**
 * READ port for tenant configuration — the single seam behind which the config SOURCE is pluggable
 * (gitops-configmap, local-dir, database, crd). This is ALL Core ever holds: no writes, no store
 * ownership. The atomic-swap + last-good-on-malformed reload contract is a property of this port and
 * is inherited identically by every adapter.
 */
public interface TenantConfigProvider {

    /** Stable id, e.g. "gitops-configmap" | "local-dir" | "database". Used as provenance. */
    String id();

    Set<Capability> capabilities();

    /** Initial snapshot. Malformed individual tenants are skipped (logged + metric), never fatal. */
    Collection<TenantConfig> loadAll();

    /** Begin streaming changes to {@code onChange}. Returns a handle to stop watching. */
    Closeable subscribe(Consumer<TenantChange> onChange);
}
