package io.labs64.audit.tenant;

import io.fabric8.kubernetes.api.model.ConfigMap;
import org.springframework.stereotype.Component;

/** Pure mapping ConfigMap -> TenantConfig. Testable without a cluster. */
@Component
public class TenantConfigMapMapper {

    /** Selected by PRESENCE; value = TenantIds.k8sName(id), informational only. */
    static final String TENANT_LABEL = "auditflow.io/tenant";
    static final String TENANT_YAML_KEY = "tenant.yaml";

    private final TenantConfigParser parser;

    public TenantConfigMapMapper(TenantConfigParser parser) {
        this.parser = parser;
    }

    public TenantConfig fromConfigMap(ConfigMap cm) {
        if (cm.getData() == null || !cm.getData().containsKey(TENANT_YAML_KEY)) {
            throw new IllegalArgumentException(
                    "ConfigMap '" + name(cm) + "' has no '" + TENANT_YAML_KEY + "' key");
        }
        return parser.parse(cm.getData().get(TENANT_YAML_KEY));
    }

    private static String name(ConfigMap cm) {
        return cm.getMetadata() == null ? "<unknown>" : cm.getMetadata().getName();
    }
}
