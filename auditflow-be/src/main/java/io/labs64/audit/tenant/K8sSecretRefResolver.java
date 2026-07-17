package io.labs64.audit.tenant;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.labs64.audit.exception.RetryableDeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;

/** K8s resolver (set by the Helm chart): reads the tenant's own Secret
 *  {@code auditflow-tenant-<k8sName(id)>-creds}. */
@Component
@ConditionalOnProperty(name = "secretRef.resolver", havingValue = "k8s-secret")
public class K8sSecretRefResolver implements SecretRefResolver {

    private final KubernetesClient client;

    public K8sSecretRefResolver(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public Map<String, String> resolve(String tenantId, Map<String, String> properties) {
        if (properties == null || properties.values().stream().noneMatch(this::isRef)) {
            return properties;
        }
        Secret secret = client.secrets()
                .withName("auditflow-tenant-" + TenantIds.k8sName(tenantId) + "-creds").get();
        if (secret == null || secret.getData() == null) {
            throw new RetryableDeliveryException(
                    "No credential Secret for tenant '" + tenantId + "'; failing delivery");
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            Matcher m = SECRET_REF.matcher(e.getValue() == null ? "" : e.getValue());
            if (m.matches()) {
                String key = m.group(1);
                String b64 = secret.getData().get(key);
                if (b64 == null) {
                    throw new RetryableDeliveryException("secretRef '" + key
                            + "' absent from tenant '" + tenantId + "' Secret; failing delivery");
                }
                resolved.put(e.getKey(),
                        new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
            } else {
                resolved.put(e.getKey(), e.getValue());
            }
        }
        return resolved;
    }

    private boolean isRef(String value) {
        return value != null && SECRET_REF.matcher(value).matches();
    }
}
