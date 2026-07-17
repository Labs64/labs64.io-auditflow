package io.labs64.audit.tenant;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.SinkProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantPipelineRegistryTest {

    private static TenantConfig cfg(String id, boolean enabled) {
        PipelineProperties p = new PipelineProperties();
        p.setName(id + "-pipe");
        p.setEnabled(true);
        SinkProperties s = new SinkProperties();
        s.setName("logging_sink");
        p.setSink(s);
        return new TenantConfig(id, enabled, TenantConfig.Quota.DEFAULT, List.of(p));
    }

    @Test
    void unknownTenantIsUnprovisionedAndAbsent() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        assertEquals(TenantState.UNPROVISIONED, reg.stateFor("nope"));
        assertTrue(reg.pipelinesFor("nope").isEmpty());
    }

    @Test
    void provisionedTenantExposesOnlyItsPipelines() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(cfg("acme", true), "local-dir");
        assertEquals(TenantState.PROVISIONED, reg.stateFor("acme"));
        PipelineSet set = reg.pipelinesFor("acme").orElseThrow();
        assertEquals("acme", set.tenantId());
        assertEquals(1, set.pipelines().size());
        assertEquals("acme-pipe", set.pipelines().get(0).getName());
        assertEquals("local-dir", reg.providerFor("acme"));
    }

    @Test
    void disabledTenantIsDisabledAndNotRoutable() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(cfg("acme", false), "local-dir");
        assertEquals(TenantState.DISABLED, reg.stateFor("acme"));
        assertTrue(reg.pipelinesFor("acme").isEmpty(), "disabled tenant must not be routable");
    }

    @Test
    void removeReturnsTenantToUnprovisioned() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(cfg("acme", true), "local-dir");
        reg.remove("acme");
        assertEquals(TenantState.UNPROVISIONED, reg.stateFor("acme"));
    }

    @Test
    void lookupNormalizesBlankTenantToPlatform() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(cfg("_platform", true), "local-dir");
        assertEquals(TenantState.PROVISIONED, reg.stateFor(""));
        assertTrue(reg.pipelinesFor(null).isPresent());
    }

    @Test
    void k8sNameCollisionIsRejectedKeepingExistingTenant() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        reg.upsert(cfg("Acme", true), "local-dir");
        reg.upsert(cfg("acme", true), "local-dir"); // k8sName("Acme") == k8sName("acme") == "acme"
        assertEquals(TenantState.PROVISIONED, reg.stateFor("Acme"), "first claimant retained");
        assertEquals(TenantState.UNPROVISIONED, reg.stateFor("acme"), "collider rejected");
    }
}
