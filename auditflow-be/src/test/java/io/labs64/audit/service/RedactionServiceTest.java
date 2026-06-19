package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.RedactionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode node(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RedactionService service(boolean enabled, RedactionProperties.Rule... rules) {
        RedactionProperties props = new RedactionProperties();
        props.setEnabled(enabled);
        props.setRules(List.of(rules));
        return new RedactionService(props);
    }

    private RedactionProperties.Rule rule(String field, RedactionProperties.Action action) {
        RedactionProperties.Rule r = new RedactionProperties.Rule();
        r.setField(field);
        r.setAction(action);
        return r;
    }

    @Test
    @DisplayName("Disabled redaction is a no-op")
    void disabledIsNoOp() {
        JsonNode event = node("{\"actor\":\"alice\"}");
        service(false, rule("actor", RedactionProperties.Action.MASK)).redact(event);
        assertEquals("alice", event.path("actor").asText());
    }

    @Test
    @DisplayName("MASK replaces a top-level field with the mask string")
    void maskTopLevel() {
        JsonNode event = node("{\"actor\":\"alice\",\"eventType\":\"api.call\"}");
        service(true, rule("actor", RedactionProperties.Action.MASK)).redact(event);
        assertEquals("***", event.path("actor").asText());
        assertEquals("api.call", event.path("eventType").asText()); // untouched
    }

    @Test
    @DisplayName("HASH replaces a nested field with its SHA-256 hex")
    void hashNested() throws Exception {
        JsonNode event = node("{\"extra\":{\"userEmail\":\"a@b.com\"}}");
        service(true, rule("extra.userEmail", RedactionProperties.Action.HASH)).redact(event);

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest("a@b.com".getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder();
        for (byte b : h) {
            expected.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        assertEquals(expected.toString(), event.path("extra").path("userEmail").asText());
    }

    @Test
    @DisplayName("DROP removes the field entirely")
    void dropField() {
        JsonNode event = node("{\"extra\":{\"ssn\":\"123-45-6789\",\"keep\":\"yes\"}}");
        service(true, rule("extra.ssn", RedactionProperties.Action.DROP)).redact(event);
        assertFalse(event.path("extra").has("ssn"));
        assertTrue(event.path("extra").has("keep"));
    }

    @Test
    @DisplayName("Array index paths are supported")
    void arrayIndexPath() {
        JsonNode event = node("{\"items\":[{\"card\":\"4111\"},{\"card\":\"4222\"}]}");
        service(true, rule("items[0].card", RedactionProperties.Action.MASK)).redact(event);
        assertEquals("***", event.path("items").get(0).path("card").asText());
        assertEquals("4222", event.path("items").get(1).path("card").asText()); // untouched
    }

    @Test
    @DisplayName("A path that does not exist is a safe no-op")
    void missingPathNoOp() {
        JsonNode event = node("{\"actor\":\"alice\"}");
        service(true, rule("extra.userEmail", RedactionProperties.Action.MASK)).redact(event);
        assertEquals("alice", event.path("actor").asText());
    }
}
