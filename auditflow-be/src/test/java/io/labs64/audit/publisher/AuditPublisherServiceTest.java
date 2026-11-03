package io.labs64.audit.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * The golden test of the promise "raw PII never enters the broker". Everything after ingest (delivery, retries, DLQ)
     * derives from this message body, so what is not here can't reach them.
     */
    @Test
    void plantedSecretsReachNeitherTheBrokerNorTheLogs() {
        RedactionProperties properties = new RedactionProperties();
        properties.setEnabled(true);
        properties.setHashKey("0123456789abcdef0123456789abcdef");
        RedactionProperties.Rule mask = new RedactionProperties.Rule();
        mask.setField("extra.mailbox");
        mask.setAction(RedactionProperties.Action.MASK);
        RedactionProperties.Rule hash = new RedactionProperties.Rule();
        hash.setField("extra.account");
        hash.setAction(RedactionProperties.Action.HASH);
        RedactionProperties.Rule drop = new RedactionProperties.Rule();
        drop.setField("extra.token");
        drop.setAction(RedactionProperties.Action.DROP);
        properties.setRules(List.of(mask, hash, drop));
        AuditPublisherService redacting = new AuditPublisherService(broker, MAPPER, new RedactionService(properties));

        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.labs64.audit");
        ch.qos.logback.classic.Level level = root.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        root.addAppender(logs);
        root.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            AuditEvent event = new AuditEvent().eventId(UUID.randomUUID()).eventType("user.login").sourceSystem("auth")
                    .extra(java.util.Map.of("mailbox", "PLANTED-MAIL", "account", "PLANTED-ACCOUNT", "token", "PLANTED-TOKEN", "kept", "visible"));

            assertNull(redacting.publishMessage(event));
        } finally {
            root.setLevel(level);
            root.detachAppender(logs);
        }

        String body = new String(broker.published().get(0).message().body(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("visible"), body);
        assertFalse(body.contains("PLANTED"), body);
        assertFalse(logs.list.stream().anyMatch(line -> line.getFormattedMessage().contains("PLANTED")));
    }

    @Test
    void aRedactionFailureFailsTheWholeCallAndPublishesNothing() {
        RedactionService failing = org.mockito.Mockito.mock(RedactionService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("rule failed")).when(failing).redact(org.mockito.ArgumentMatchers.any());
        AuditPublisherService publisher = new AuditPublisherService(broker, MAPPER, failing);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> publisher.publishMessages(List.of(event(0), event(0))));

        assertTrue(broker.published().isEmpty());
    }
}
