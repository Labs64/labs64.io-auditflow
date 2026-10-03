package io.labs64.audit.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.RetryProperties;

class RetryPolicyTest {

    private static final RetryPolicy DEFAULTS = new RetryPolicy(20, Duration.ofHours(24));

    @Test
    void withoutRetryBlockThePipelineUsesTheDefaults() {
        assertEquals(DEFAULTS, RetryPolicy.of(new PipelineProperties(), DEFAULTS));
    }

    @Test
    void setFieldsOverrideAndUnsetFieldsInherit() {
        PipelineProperties p = new PipelineProperties();
        RetryProperties retry = new RetryProperties();
        retry.setMaxAge("2h");
        p.setRetry(retry);
        assertEquals(new RetryPolicy(20, Duration.ofHours(2)), RetryPolicy.of(p, DEFAULTS));
    }

    @ParameterizedTest
    @CsvSource({"1,5", "2,30", "3,120", "4,600", "5,1800", "6,3600", "7,10800", "50,10800"})
    void delaysGrowFromSecondsToHoursAndThenStay(int failed, long seconds) {
        assertEquals(Duration.ofSeconds(seconds), RetryPolicy.delayAfter(failed));
    }

    @Test
    void ageIsJudgedOnWhenTheNextAttemptWouldRun() {
        long t0 = 0;
        long now = Duration.ofHours(23).toMillis();
        assertFalse(DEFAULTS.ageExceeded(t0, now, Duration.ofMinutes(30)));
        assertTrue(DEFAULTS.ageExceeded(t0, now, Duration.ofHours(3)));
    }

    @ParameterizedTest
    @CsvSource({"PT24H,86400", "pt30m,1800", "90s,90", "30m,1800", "24h,86400", "2d,172800"})
    void maxAgeAcceptsIsoAndShortForms(String value, long seconds) {
        RetryProperties retry = new RetryProperties();
        retry.setMaxAge(value);
        assertEquals(Duration.ofSeconds(seconds), retry.maxAgeDuration());
    }

    @ParameterizedTest
    @ValueSource(strings = {"soon", "-5m", "0s", "PT0S", "10x"})
    void invalidMaxAgeIsRejectedAtConfigLoad(String value) {
        RetryProperties retry = new RetryProperties();
        assertThrows(IllegalArgumentException.class, () -> retry.setMaxAge(value));
    }

    @Test
    void maxAttemptsMustBePositive() {
        RetryProperties retry = new RetryProperties();
        assertThrows(IllegalArgumentException.class, () -> retry.setMaxAttempts(0));
    }
}
