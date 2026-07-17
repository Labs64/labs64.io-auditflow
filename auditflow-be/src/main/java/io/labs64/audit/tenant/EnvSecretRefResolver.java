package io.labs64.audit.tenant;

import io.labs64.audit.exception.RetryableDeliveryException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;

/** Built-in default resolver: reads AUDITFLOW_TENANT_<envName(ID)>_<KEY> from the environment. */
@Component
@ConditionalOnProperty(name = "secretRef.resolver", havingValue = "env", matchIfMissing = true)
public class EnvSecretRefResolver implements SecretRefResolver {

    private final Function<String, String> env;

    public EnvSecretRefResolver() {
        this(System::getenv);
    }

    EnvSecretRefResolver(Function<String, String> env) {
        this.env = env;
    }

    @Override
    public Map<String, String> resolve(String tenantId, Map<String, String> properties) {
        if (properties == null) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : properties.entrySet()) {
            Matcher m = SECRET_REF.matcher(e.getValue() == null ? "" : e.getValue());
            if (m.matches()) {
                String key = m.group(1);
                String envName = "AUDITFLOW_TENANT_" + TenantIds.envName(tenantId) + "_" + key.toUpperCase();
                String value = env.apply(envName);
                if (value == null) {
                    throw new RetryableDeliveryException("secretRef '" + key
                            + "' not found for tenant '" + tenantId + "'; failing delivery");
                }
                resolved.put(e.getKey(), value);
            } else {
                resolved.put(e.getKey(), e.getValue());
            }
        }
        return resolved;
    }
}
