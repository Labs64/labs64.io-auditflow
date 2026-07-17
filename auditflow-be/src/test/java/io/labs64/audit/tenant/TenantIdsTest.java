package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantIdsTest {

    @Test
    void nullBlankAndDashResolveToPlatform() {
        assertEquals("_platform", TenantIds.resolve(null));
        assertEquals("_platform", TenantIds.resolve(""));
        assertEquals("_platform", TenantIds.resolve("   "));
        assertEquals("_platform", TenantIds.resolve("-"));
    }

    @Test
    void realTenantIsReturnedTrimmed() {
        assertEquals("acme", TenantIds.resolve("acme"));
        assertEquals("acme", TenantIds.resolve("  acme  "));
    }

    @Test
    void k8sNameSanitizesForResourceNamesAndLabels() {
        assertEquals("platform", TenantIds.k8sName("_platform"));   // '_' illegal in K8s names/labels
        assertEquals("acme", TenantIds.k8sName("acme"));
        assertEquals("v12345678", TenantIds.k8sName("V12345678"));  // lowercase
        assertEquals("acme-corp", TenantIds.k8sName("Acme.Corp"));  // non [a-z0-9-] -> '-'
    }

    @Test
    void envNameSanitizesForEnvVars() {
        assertEquals("ACME", TenantIds.envName("acme"));
        assertEquals("ACME_CORP", TenantIds.envName("acme-corp"));  // '-' illegal in env names
        assertEquals("_PLATFORM", TenantIds.envName("_platform"));
    }
}
