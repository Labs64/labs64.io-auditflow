package io.labs64.audit.broker.sqs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.labs64.audit.broker.Destination;

class SqsQueueNamesTest {

    private final SqsQueueNames names = new SqsQueueNames("acme-prod-auditflow-");

    @Test
    void everyStageHasItsQueueAndDelaysUseTheDeliveryQueue() {
        assertEquals("acme-prod-auditflow-ingest", names.name(Destination.ingest()));
        assertEquals("acme-prod-auditflow-ingest-dlq", names.name(Destination.ingestDlq()));
        assertEquals("acme-prod-auditflow-delivery", names.name(Destination.delivery()));
        assertEquals("acme-prod-auditflow-delivery", names.name(Destination.delay(Duration.ofHours(3))));
        assertEquals("acme-prod-auditflow-quarantine", names.name(Destination.quarantine()));
    }

    @Test
    void aPlainTenantIdIsUsedAsIs() {
        assertEquals("acme-prod-auditflow-dlq-acme", names.name(Destination.tenantDlq("acme")));
        assertEquals("acme-prod-auditflow-dlq-_platform", names.name(Destination.tenantDlq("_platform")));
    }

    @Test
    void aTenantIdSqsCannotCarryGetsASanitizedNameWithAHashSoTenantsNeverShareAQueue() {
        String dotted = names.name(Destination.tenantDlq("acme.eu"));
        String underscored = names.name(Destination.tenantDlq("acme_eu"));

        // Pinned: infrastructure code that alarms per tenant DLQ derives the same name.
        assertEquals("acme-prod-auditflow-dlq-acme_eu-6a5e7a0e", dotted);
        assertEquals("acme-prod-auditflow-dlq-acme_eu", underscored);
        assertNotEquals(dotted, underscored);
    }

    @Test
    void aLongTenantIdIsShortenedToTheSqsLimit() {
        String id = "t".repeat(64);
        String name = names.name(Destination.tenantDlq(id));

        assertEquals(SqsQueueNames.MAX_LENGTH, name.length());
        assertTrue(name.matches("[A-Za-z0-9_-]+"));
        assertNotEquals(name, names.name(Destination.tenantDlq("t".repeat(63) + "u")));
    }

    @Test
    void anInvalidPrefixFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new SqsQueueNames("audit.flow."));
    }
}
