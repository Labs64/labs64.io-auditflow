package io.labs64.audit.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigMapMapperTest {

    private final TenantConfigMapMapper mapper =
            new TenantConfigMapMapper(new TenantConfigParser(new ObjectMapper()));

    private ConfigMap cm(Map<String, String> data) {
        return new ConfigMapBuilder()
                .withNewMetadata().withName("auditflow-tenant-acme")
                .addToLabels("auditflow.io/tenant", "acme").endMetadata()
                .withData(data).build();
    }

    @Test
    void mapsTenantYamlKeyToTenantConfig() {
        TenantConfig cfg = mapper.fromConfigMap(cm(Map.of("tenant.yaml",
                "tenantId: acme\nenabled: true\n")));
        assertEquals("acme", cfg.tenantId());
        assertTrue(cfg.enabled());
    }

    @Test
    void missingTenantYamlKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.fromConfigMap(cm(Map.of("other.txt", "x"))));
    }
}
