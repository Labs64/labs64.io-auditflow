package io.labs64.audit.tenant;

/** A tenant's state, derived entirely from its config — never stored in Core. */
public enum TenantState {
    /** Config present, enabled: true. */
    PROVISIONED,
    /** Config present, enabled: false. */
    DISABLED,
    /** No config for the tenant. */
    UNPROVISIONED
}
