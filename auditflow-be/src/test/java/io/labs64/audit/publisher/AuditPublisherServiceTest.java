package io.labs64.audit.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.InMemoryBroker;
import io.labs64.audit.config.RedactionProperties;
import io.labs64.audit.service.RedactionService;
import io.labs64.auditflow.model.AuditEvent;

class AuditPublisherServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private final InMemoryBroker broker = new InMemoryBroker();
    private final AuditPublisherService service =
            new AuditPublisherService(broker, MAPPER, new RedactionService(new RedactionProperties()));

    private static AuditEvent event(int extraChars) {
        AuditEvent event = new AuditEvent().eventId(UUID.randomUUID()).eventType("user.login").sourceSystem("auth");
        if (extraChars > 0) {
            event.extra(java.util.Map.of("blob", "x".repeat(extraChars)));
        }
        return event;
    }

    @Test
    void anAcceptedEventGoesToTheIngestDestinationWithItsIdAsMessageId() {
        AuditEvent event = event(0);

        assertNull(service.publishMessage(event));

        var published = broker.published();
        assertEquals(Destination.ingest(), published.get(0).destination());
        assertEquals(event.getEventId().toString(), published.get(0).message().messageId());
    }

    @Test
    void anEventLargerThanTheBrokerAcceptsIsRejectedUpFrontAndTheOthersStillGo() {
        broker.maxMessageBytes(64 * 1024);

        List<String> results = service.publishMessages(List.of(event(0), event(60 * 1024)));

        assertNull(results.get(0));
        assertTrue(results.get(1).startsWith(AuditPublisherService.TOO_LARGE), results.get(1));
        assertEquals(1, broker.published().size());
    }

    @Test
    void roomIsKeptForTheDeliveryHeaders() {
        broker.maxMessageBytes(64 * 1024);

        assertNull(service.tooLarge(64 * 1024 - AuditPublisherService.HEADER_HEADROOM_BYTES));
        assertTrue(service.tooLarge(64 * 1024 - AuditPublisherService.HEADER_HEADROOM_BYTES + 1) != null);
    }

    @Test
    void aBrokerFailureIsReportedPerEvent() {
        broker.failWith("no broker confirm within timeout");

        assertEquals("no broker confirm within timeout", service.publishMessage(event(0)));
    }
}
