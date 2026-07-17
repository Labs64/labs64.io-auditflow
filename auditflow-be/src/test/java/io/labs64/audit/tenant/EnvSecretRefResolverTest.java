package io.labs64.audit.tenant;

import io.labs64.audit.exception.RetryableDeliveryException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnvSecretRefResolverTest {

    @Test
    void resolvesSecretRefFromTenantEnv() {
        var resolver = new EnvSecretRefResolver(Map.of(
                "AUDITFLOW_TENANT_ACME_OSPASSWORD", "s3cr3t")::get);
        Map<String, String> out = resolver.resolve("acme", Map.of(
                "index", "acme-audit",
                "password", "${secretRef:osPassword}"));
        assertEquals("acme-audit", out.get("index"));
        assertEquals("s3cr3t", out.get("password"));
    }

    @Test
    void missingKeyFailsDeliveryNeverBlank() {
        var resolver = new EnvSecretRefResolver(((java.util.function.Function<String, String>) k -> null)::apply);
        assertThrows(RetryableDeliveryException.class, () ->
                resolver.resolve("acme", Map.of("password", "${secretRef:absent}")));
    }

    @Test
    void nullPropertiesAreReturnedAsIs() {
        var resolver = new EnvSecretRefResolver(((java.util.function.Function<String, String>) k -> null)::apply);
        assertNull(resolver.resolve("acme", null));
    }

    @Test
    void platformTenantResolvesViaSanitizedEnvName() {
        var resolver = new EnvSecretRefResolver(Map.of(
                "AUDITFLOW_TENANT__PLATFORM_TOKEN", "tok")::get);
        Map<String, String> out = resolver.resolve(TenantIds.PLATFORM,
                Map.of("token", "${secretRef:token}"));
        assertEquals("tok", out.get("token"));
    }
}
