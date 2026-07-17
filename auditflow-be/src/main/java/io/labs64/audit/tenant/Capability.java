package io.labs64.audit.tenant;

/** What a {@link TenantConfigProvider} can do. Core only ever requires read capabilities. */
public enum Capability {
    /** Pushes changes (informer / dir-watch). */
    WATCH,
    /** Emits changes by polling on an interval (DB / directory poll). */
    POLL,
    /** Supports mutation — lives ONLY in a separate control-plane deployment, never in Core. */
    WRITABLE
}
