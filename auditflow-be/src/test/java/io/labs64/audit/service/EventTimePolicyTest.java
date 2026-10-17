package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.labs64.audit.config.EventTimeProperties;
import io.labs64.auditflow.model.AuditEvent;

/** The two times of an event: receipt time (timestamp, ours) and event time (eventTime, the publisher's claim). */
class EventTimePolicyTest {

    private static final OffsetDateTime RECEIVED = OffsetDateTime.of(2026, 10, 17, 12, 0, 0, 0, ZoneOffset.UTC);

    private final EventTimeProperties properties = new EventTimeProperties();
    private final EventTimePolicy policy = new EventTimePolicy(properties);

    private static AuditEvent event(OffsetDateTime eventTime) {
        return new AuditEvent().eventType("x.y").sourceSystem("test").eventTime(eventTime);
    }

    @Test
    void defaultsAreFiveMinutesAheadAndThreeYearsBack() {
        assertEquals(Duration.ofMinutes(5), properties.getMaxFuture());
        assertEquals(Duration.ofDays(1095), properties.getMaxAge());
    }

    @Test
    void anOmittedEventTimeIsAcceptedAndBecomesTheReceiptTime() {
        AuditEvent e = event(null);
        assertNull(policy.violation(e, RECEIVED));

        policy.stamp(e, RECEIVED);

        assertEquals(RECEIVED, e.getTimestamp());
        assertEquals(RECEIVED, e.getEventTime());
    }

    @Test
    void aSuppliedEventTimeIsKeptAsSentAndTheReceiptTimeIsStillOurs() {
        OffsetDateTime sent = RECEIVED.minusHours(3).withOffsetSameInstant(ZoneOffset.ofHours(2));
        AuditEvent e = event(sent).timestamp(RECEIVED.minusYears(10)); // a client-supplied timestamp counts for nothing

        policy.stamp(e, RECEIVED);

        assertEquals(sent, e.getEventTime());
        assertEquals(ZoneOffset.ofHours(2), e.getEventTime().getOffset());
        assertEquals(RECEIVED, e.getTimestamp());
    }

    @Test
    void clockSkewUpToTheLimitIsAccepted() {
        assertNull(policy.violation(event(RECEIVED.plusMinutes(5)), RECEIVED));
        assertNull(policy.violation(event(RECEIVED.plusMinutes(2)), RECEIVED));
    }

    @Test
    void anEventTimeBeyondTheSkewIsRejectedWithAMessageThatSaysWhatToDo() {
        String problem = policy.violation(event(RECEIVED.plusMinutes(5).plusSeconds(1)), RECEIVED);

        assertNotNull(problem);
        assertTrue(problem.contains("in the future"), problem);
        assertTrue(problem.contains("omit eventTime"), problem);
    }

    @Test
    void aBackfillInsideTheMaxAgeIsAcceptedAndOlderIsRejected() {
        assertNull(policy.violation(event(RECEIVED.minusDays(1095)), RECEIVED));

        String problem = policy.violation(event(RECEIVED.minusDays(1095).minusSeconds(1)), RECEIVED);
        assertNotNull(problem);
        assertTrue(problem.contains("too old"), problem);
    }

    @Test
    void theWindowFollowsTheConfiguration() {
        properties.setMaxFuture(Duration.ZERO);
        properties.setMaxAge(Duration.ofDays(30));

        assertNotNull(policy.violation(event(RECEIVED.plusSeconds(1)), RECEIVED));
        assertNull(policy.violation(event(RECEIVED.minusDays(30)), RECEIVED));
        assertNotNull(policy.violation(event(RECEIVED.minusDays(31)), RECEIVED));
    }

    @Test
    void theWindowIsMeasuredOnInstantsNotOnClockReadings() {
        // 14:04 at +02:00 is 12:04 UTC: 4 minutes ahead of the receipt time, inside the skew.
        OffsetDateTime sameInstantOtherOffset = OffsetDateTime.of(2026, 10, 17, 14, 4, 0, 0, ZoneOffset.ofHours(2));
        assertNull(policy.violation(event(sameInstantOtherOffset), RECEIVED));
    }
}
