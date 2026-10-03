package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.RedactionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static final String KEY = "0123456789abcdef0123456789abcdef";          // 32 characters
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";

    private RedactionService service(boolean enabled, RedactionProperties.Rule... rules) {
        return serviceWithKey(enabled, KEY, rules);
    }

    private RedactionService serviceWithKey(boolean enabled, String hashKey, RedactionProperties.Rule... rules) {
        RedactionProperties props = new RedactionProperties();
        props.setEnabled(enabled);
        props.setHashKey(hashKey);
        props.setRules(List.of(rules));
        return new RedactionService(props);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String hashed(String key, String json, String field) {
        JsonNode event = node(json);
        serviceWithKey(true, key, rule(field, RedactionProperties.Action.HASH)).redact(event);
        JsonNode value = event;
        for (String part : field.split("\\.")) {
            value = value.path(part);
        }
        return value.asText();
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
    @DisplayName("HASH replaces a nested field with its HMAC-SHA256 under the configured key")
    void hashNestedIsAKeyedHmac() throws Exception {
        JsonNode event = node("{\"extra\":{\"userEmail\":\"a@b.com\",\"keep\":\"yes\"}}");
        service(true, rule("extra.userEmail", RedactionProperties.Action.HASH)).redact(event);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal("a@b.com".getBytes(StandardCharsets.UTF_8)));
        String actual = event.path("extra").path("userEmail").asText();
        assertEquals(expected, actual);
        assertEquals(64, actual.length());
        assertEquals("yes", event.path("extra").path("keep").asText());
    }

    @Test
    @DisplayName("HASH equals the value another tool computes with the same key")
    void hashMatchesAnIndependentReference() {
        // printf '%s' 'a@b.com' | openssl dgst -sha256 -hmac '0123456789abcdef0123456789abcdef'
        assertEquals("3821f27dc439d8b848d3d6096a2af682aebcf5e1892988a34e19ba25d1d0119e",
                hashed(KEY, "{\"extra\":{\"userEmail\":\"a@b.com\"}}", "extra.userEmail"));
    }

    @Test
    @DisplayName("HASH is not the plain SHA-256 of the value: it cannot be reproduced without the key")
    void hashIsNotThePlainDigest() throws Exception {
        String hashed = hashed(KEY, "{\"extra\":{\"userEmail\":\"a@b.com\"}}", "extra.userEmail");
        assertNotEquals(sha256("a@b.com"), hashed);
        assertNotEquals(hashed, hashed(OTHER_KEY, "{\"extra\":{\"userEmail\":\"a@b.com\"}}", "extra.userEmail"));
    }

    @Test
    @DisplayName("HASH gives equal values equal results and different values different results")
    void hashKeepsValuesCorrelatable() {
        String first = hashed(KEY, "{\"extra\":{\"userEmail\":\"a@b.com\"}}", "extra.userEmail");
        String again = hashed(KEY, "{\"extra\":{\"userEmail\":\"a@b.com\"}}", "extra.userEmail");
        String other = hashed(KEY, "{\"extra\":{\"userEmail\":\"c@d.com\"}}", "extra.userEmail");
        assertEquals(first, again);
        assertNotEquals(first, other);
    }

    @Test
    @DisplayName("HASH works on array elements and on values that are not strings")
    void hashArrayElementsAndNonStrings() {
        JsonNode event = node("{\"ips\":[\"10.0.0.1\",\"10.0.0.2\"],\"extra\":{\"accountNo\":42,\"address\":{\"zip\":\"80331\"}}}");
        service(true, rule("ips[1]", RedactionProperties.Action.HASH), rule("extra.accountNo", RedactionProperties.Action.HASH),
                rule("extra.address", RedactionProperties.Action.HASH)).redact(event);
        assertEquals("10.0.0.1", event.path("ips").get(0).asText());
        assertTrue(event.path("ips").get(1).asText().matches("[0-9a-f]{64}"));
        assertTrue(event.path("extra").path("accountNo").asText().matches("[0-9a-f]{64}"));
        assertTrue(event.path("extra").path("address").isTextual());       // the whole object became one hash
        assertTrue(event.path("extra").path("address").asText().matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("An enabled HASH rule without a key stops the application at startup")
    void hashRuleWithoutAKeyFailsFast() {
        for (String missing : new String[] {null, "", "   "}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> serviceWithKey(true, missing, rule("extra.userEmail", RedactionProperties.Action.HASH)));
            assertTrue(e.getMessage().contains("AUDITFLOW_REDACTION_HASH_KEY"), e.getMessage());
        }
    }

    @Test
    @DisplayName("A key shorter than 32 characters is refused, and the message does not contain it")
    void aShortKeyIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> serviceWithKey(true, "short-secret", rule("extra.userEmail", RedactionProperties.Action.HASH)));
        assertTrue(e.getMessage().contains("too short"), e.getMessage());
        assertFalse(e.getMessage().contains("short-secret"));
    }

    @Test
    @DisplayName("No key is needed while no enabled rule hashes")
    void noKeyNeededWithoutAnEnabledHashRule() {
        JsonNode event = node("{\"actor\":\"alice\",\"extra\":{\"userEmail\":\"a@b.com\"}}");
        // mask and drop only
        serviceWithKey(true, null, rule("actor", RedactionProperties.Action.MASK)).redact(event);
        assertEquals("***", event.path("actor").asText());
        // a hash rule, but redaction is switched off: nothing is hashed and nothing is required
        serviceWithKey(false, null, rule("extra.userEmail", RedactionProperties.Action.HASH)).redact(event);
        assertEquals("a@b.com", event.path("extra").path("userEmail").asText());
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
