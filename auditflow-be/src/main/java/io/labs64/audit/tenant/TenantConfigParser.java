package io.labs64.audit.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses the canonical {@code tenant.yaml} encoding into a {@link TenantConfig}. SnakeYAML (already
 * on the classpath via Spring Boot) parses to a {@code Map}; Jackson binds it to the record and the
 * existing {@code PipelineProperties} beans — so the tenant schema is exactly {@code auditflow.pipelines}.
 */
@Component
public class TenantConfigParser {

    /** Spec §4.1 canonical tenant-id pattern (mirrors the OpenAPI tenantId pattern). */
    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private final ObjectMapper objectMapper;

    public TenantConfigParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TenantConfig parse(String tenantYaml) {
        final Map<String, Object> raw;
        try {
            raw = new Yaml().load(tenantYaml);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed tenant.yaml: " + e.getMessage(), e);
        }
        if (raw == null || !(raw.get("tenantId") instanceof String id) || !StringUtils.hasText(id)) {
            throw new IllegalArgumentException("tenant.yaml missing required non-blank 'tenantId'");
        }
        if (!TenantIds.PLATFORM.equals(id) && !TENANT_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("tenantId '" + id + "' violates pattern "
                    + TENANT_ID.pattern() + " (only the reserved '" + TenantIds.PLATFORM + "' is exempt)");
        }
        try {
            return objectMapper.convertValue(raw, TenantConfig.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid tenant.yaml for '" + id + "': " + e.getMessage(), e);
        }
    }
}
