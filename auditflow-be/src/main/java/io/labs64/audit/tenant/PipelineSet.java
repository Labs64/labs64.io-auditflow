package io.labs64.audit.tenant;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;

import java.util.List;

/** The immutable pipeline set owned by exactly one tenant, returned by
 *  {@link TenantPipelineRegistry#pipelinesFor(String)} only when the tenant is PROVISIONED. */
public record PipelineSet(
        String tenantId,
        TenantConfig.Quota quota,
        List<PipelineProperties> pipelines) {
}
