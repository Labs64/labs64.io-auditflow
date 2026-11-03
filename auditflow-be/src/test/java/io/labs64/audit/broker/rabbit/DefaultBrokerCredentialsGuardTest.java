package io.labs64.audit.broker.rabbit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DefaultBrokerCredentialsGuardTest {

    @Test
    void refusesGuestUnlessALocalStackAllowsIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DefaultBrokerCredentialsGuard("guest", "guest", false));
        assertTrue(e.getMessage().contains("AUDITFLOW_BROKER_ALLOW_DEFAULT_CREDENTIALS"), e.getMessage());

        assertDoesNotThrow(() -> new DefaultBrokerCredentialsGuard("guest", "guest", true));
    }

    @Test
    void otherCredentialsPass() {
        assertDoesNotThrow(() -> new DefaultBrokerCredentialsGuard("guest", "s3cret", false));
        assertDoesNotThrow(() -> new DefaultBrokerCredentialsGuard("audit", "guest", false));
        assertDoesNotThrow(() -> new DefaultBrokerCredentialsGuard("", "", false));
    }
}
