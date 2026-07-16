package io.labs64.audit.tenant;

/** Tenant-id normalization. A tenantless service principal (X-Auth-Tenant "-" / blank) maps to
 *  the reserved {@code _platform} tenant — a named tenant, never a global fall-through. */
public final class TenantIds {

    /** Reserved pseudo-tenant for tenantless service-principal (platform) events. */
    public static final String PLATFORM = "_platform";

    private TenantIds() {
    }

    public static String resolve(String rawTenantId) {
        if (rawTenantId == null) {
            return PLATFORM;
        }
        String trimmed = rawTenantId.trim();
        if (trimmed.isEmpty() || "-".equals(trimmed)) {
            return PLATFORM;
        }
        return trimmed;
    }

    /**
     * K8s resource-name / label-value encoding of a canonical tenant id (spec §4.1): lowercase,
     * {@code [^a-z0-9-]} → {@code -}, dashes trimmed; the reserved {@code _platform} maps to
     * {@code platform}. Lossy — collisions are rejected by the registry.
     */
    public static String k8sName(String tenantId) {
        if (PLATFORM.equals(tenantId)) {
            return "platform";
        }
        String name = tenantId.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        name = name.replaceAll("^-+", "").replaceAll("-+$", "");
        return name;
    }

    /** Env-var encoding of a canonical tenant id: uppercase, {@code [^A-Z0-9]} → {@code _}. */
    public static String envName(String tenantId) {
        return tenantId.toUpperCase().replaceAll("[^A-Z0-9]", "_");
    }
}
