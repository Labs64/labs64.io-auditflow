package io.labs64.auditflow.client;

import io.labs64.auditflow.model.AuditEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventsTest {

    @Test
    void buildsEventWithExtraEntries() {
        AuditEvent event = AuditEvents.builder("user.login")
                .sourceSystem("auth-service")
                .tenantId("V12345678")
                .extra("userId", "u1")
                .extra("action_status", "SUCCESS")
                .build();

        assertEquals("user.login", event.getEventType());
        assertEquals("auth-service", event.getSourceSystem());
        assertEquals("V12345678", event.getTenantId());
        assertEquals("u1", event.getExtra().get("userId"));
        assertEquals("SUCCESS", event.getExtra().get("action_status"));
    }

    @Test
    void buildsMinimalEventWithoutExtra() {
        AuditEvent event = AuditEvents.builder("api.call").build();

        assertEquals("api.call", event.getEventType());
        assertNull(event.getExtra());
    }
}
