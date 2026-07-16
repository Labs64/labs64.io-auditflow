package io.labs64.audit.tenant;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;

import java.util.List;

/** Canonical, storage-agnostic tenant configuration. A ConfigMap value, a DB row and an API body
 *  are just encodings of this same model. {@code pipelines} is the existing auditflow.pipelines schema. */
public record TenantConfig(
        String tenantId,
        boolean enabled,
        Quota quota,
        List<PipelineProperties> pipelines) {

    public TenantConfig {
        if (quota == null) {
            quota = Quota.DEFAULT;
        }
        if (pipelines == null) {
            pipelines = List.of();
        }
    }

    /** Per-tenant fairness budget applied at ingest (§6). */
    public record Quota(int rateLimitPerSec, int burst) {
        public static final Quota DEFAULT = new Quota(200, 400);
    }
}
