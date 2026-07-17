package io.labs64.audit.tenant;

import java.util.Map;
import java.util.regex.Pattern;

/** Resolves {@code ${secretRef:<key>}} placeholders in sink properties from the tenant's own secret
 *  store at delivery time. Guardrails #2 (no inline creds) and #10 (tenant reaches only its own creds). */
public interface SecretRefResolver {

    Pattern SECRET_REF = Pattern.compile("^\\$\\{secretRef:([a-zA-Z0-9_]+)\\}$");

    /** @return a copy of {@code properties} with every {@code ${secretRef:k}} value replaced.
     *  @throws io.labs64.audit.exception.RetryableDeliveryException if a referenced key is absent. */
    Map<String, String> resolve(String tenantId, Map<String, String> properties);
}
