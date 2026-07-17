package io.labs64.audit.tenant;

/** A single tenant add/change/delete emitted by a provider. For DELETE, {@code config} is null. */
public record TenantChange(Type type, String tenantId, TenantConfig config) {

    public enum Type { UPSERT, DELETE }

    public static TenantChange upsert(TenantConfig config) {
        return new TenantChange(Type.UPSERT, config.tenantId(), config);
    }

    public static TenantChange delete(String tenantId) {
        return new TenantChange(Type.DELETE, tenantId, null);
    }
}
