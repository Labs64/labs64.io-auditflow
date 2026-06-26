package io.labs64.auditflow.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryPolicyTest {

    @Test
    void exponentialBackoffGrowsPerAttempt() {
        RetryPolicy policy = RetryPolicy.exponential(3);

        assertEquals(3, policy.maxAttempts());
        assertEquals(Duration.ZERO, policy.backoffBeforeAttempt(1));
        assertEquals(Duration.ofMillis(200), policy.backoffBeforeAttempt(2));
        assertEquals(Duration.ofMillis(400), policy.backoffBeforeAttempt(3));
    }

    @Test
    void onlyServiceUnavailableIsRetryable() {
        RetryPolicy policy = RetryPolicy.exponential(3);

        assertTrue(policy.isRetryableStatus(503));
        assertFalse(policy.isRetryableStatus(400));
        assertFalse(policy.isRetryableStatus(401));
        assertFalse(policy.isRetryableStatus(500));
        assertFalse(policy.isRetryableStatus(200));
    }

    @Test
    void noneDisablesRetries() {
        RetryPolicy policy = RetryPolicy.none();

        assertEquals(1, policy.maxAttempts());
        assertFalse(policy.isRetryableStatus(503));
    }
}
