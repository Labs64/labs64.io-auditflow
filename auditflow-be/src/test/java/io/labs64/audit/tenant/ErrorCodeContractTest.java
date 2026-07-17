package io.labs64.audit.tenant;

import io.labs64.auditflow.model.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ErrorCodeContractTest {
    @Test
    void tenantErrorCodesAreGenerated() {
        assertNotNull(ErrorCode.valueOf("TENANT_NOT_PROVISIONED"));
        assertNotNull(ErrorCode.valueOf("TENANT_DISABLED"));
        assertNotNull(ErrorCode.valueOf("TENANT_RATE_LIMITED"));
    }
}
