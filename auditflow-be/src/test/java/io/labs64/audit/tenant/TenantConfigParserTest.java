package io.labs64.audit.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantConfigParserTest {

    private final TenantConfigParser parser = new TenantConfigParser(new ObjectMapper());

    private static final String VALID = """
            tenantId: acme
            enabled: true
            quota:
              rateLimitPerSec: 200
              burst: 400
            pipelines:
              - name: acme-security-opensearch
                enabled: true
                condition:
                  match: all
                  rules:
                    - field: eventType
                      operator: startsWith
                      value: "security."
                transformer:
                  name: audit_opensearch
                sink:
                  name: opensearch_sink
                  properties:
                    endpoint: https://acme.os.internal:9200
                    index: acme-audit
            """;

    @Test
    void parsesCanonicalTenantYaml() {
        TenantConfig cfg = parser.parse(VALID);
        assertEquals("acme", cfg.tenantId());
        assertTrue(cfg.enabled());
        assertEquals(200, cfg.quota().rateLimitPerSec());
        assertEquals(400, cfg.quota().burst());
        assertEquals(1, cfg.pipelines().size());
        assertEquals("acme-security-opensearch", cfg.pipelines().get(0).getName());
        assertEquals("opensearch_sink", cfg.pipelines().get(0).getSink().getName());
        assertEquals("acme-audit", cfg.pipelines().get(0).getSink().getProperties().get("index"));
    }

    @Test
    void missingTenantIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("enabled: true\n"));
    }

    @Test
    void malformedYamlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("tenantId: acme\n  : : bad"));
    }

    @Test
    void quotaDefaultsWhenOmitted() {
        TenantConfig cfg = parser.parse("tenantId: acme\nenabled: true\n");
        assertEquals(TenantConfig.Quota.DEFAULT, cfg.quota());
    }

    @Test
    void invalidTenantIdPatternIsRejectedButPlatformIsAllowed() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("tenantId: \"bad tenant!\"\nenabled: true\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("tenantId: \"_sneaky\"\nenabled: true\n"));
        assertEquals("_platform", parser.parse("tenantId: _platform\nenabled: true\n").tenantId());
    }
}
