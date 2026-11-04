package io.labs64.audit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.auditflow.model.AuditEvent;

/** The reserved {@code context} is read from a publish (so it can be refused) but never written into an event. */
class JacksonConfigTest {

    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void aPublishedEventNeverCarriesAContextKeyNotEvenAnEmptyOne() throws Exception {
        String empty = mapper.writeValueAsString(new AuditEvent().eventType("t").sourceSystem("s"));
        String set = mapper.writeValueAsString(new AuditEvent().eventType("t").sourceSystem("s")
                .context(Map.of("original", Map.of("key", "k"))));

        assertFalse(mapper.readTree(empty).has("context"), empty);
        assertFalse(mapper.readTree(set).has("context"), set);
        assertFalse(mapper.valueToTree(new AuditEvent().eventType("t")).has("context"));
    }

    @Test
    void aContextInAPublishIsStillReadSoIngestCanRefuseIt() throws Exception {
        AuditEvent event = mapper.readValue(
                "{\"eventType\":\"t\",\"sourceSystem\":\"s\",\"context\":{\"original\":{\"key\":\"k\"}}}", AuditEvent.class);

        assertEquals(Map.of("original", Map.of("key", "k")), event.getContext());
    }
}
