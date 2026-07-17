package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantChangeTest {

    @Test
    void upsertFactoryCarriesConfig() {
        TenantConfig cfg = new TenantConfig("acme", true, TenantConfig.Quota.DEFAULT, List.of());
        TenantChange c = TenantChange.upsert(cfg);
        assertEquals(TenantChange.Type.UPSERT, c.type());
        assertEquals("acme", c.tenantId());
        assertSame(cfg, c.config());
    }

    @Test
    void deleteFactoryHasNoConfig() {
        TenantChange c = TenantChange.delete("acme");
        assertEquals(TenantChange.Type.DELETE, c.type());
        assertEquals("acme", c.tenantId());
        assertNull(c.config());
    }
}
