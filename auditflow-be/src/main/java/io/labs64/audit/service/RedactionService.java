package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.labs64.audit.config.RedactionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Applies the configured {@link RedactionProperties} rules to an event tree in place, masking,
 * hashing, or dropping PII fields <em>before publish</em> so raw PII never enters the broker.
 * Hashing is keyed (HMAC-SHA256): see {@link RedactionProperties.Action#HASH}.
 *
 * <p>Field paths use dot notation with array indices, matching the condition engine
 * (e.g. {@code extra.userEmail}, {@code items[0].card}). A path that does not exist is a no-op.</p>
 */
@Service
public class RedactionService {

    private static final Logger logger = LoggerFactory.getLogger(RedactionService.class);

    /** Shorter keys can be guessed; 32 characters is the size of the HMAC-SHA256 output. */
    static final int MIN_HASH_KEY_LENGTH = 32;
    private static final String HMAC = "HmacSHA256";

    private final RedactionProperties properties;
    /** Key of the HASH action; null when no enabled rule hashes. */
    private final SecretKeySpec hashKey;

    public RedactionService(RedactionProperties properties) {
        this.properties = properties;
        this.hashKey = hashKey(properties);
    }

    /**
     * The HASH key, checked at startup: an enabled HASH rule without a usable key stops the
     * application instead of publishing values hashed in a way that can be reversed by guessing.
     */
    private static SecretKeySpec hashKey(RedactionProperties properties) {
        boolean hashes = properties.isEnabled() && properties.getRules().stream()
                .anyMatch(r -> r.getAction() == RedactionProperties.Action.HASH);
        String key = properties.getHashKey();
        if (!hashes) {
            return null;
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("auditflow.redaction: a rule uses the 'hash' action but no key is set. "
                    + "Set AUDITFLOW_REDACTION_HASH_KEY (auditflow.redaction.hash-key) from a secret, "
                    + "at least " + MIN_HASH_KEY_LENGTH + " characters, e.g. `openssl rand -base64 32`");
        }
        if (key.length() < MIN_HASH_KEY_LENGTH) {
            throw new IllegalStateException("auditflow.redaction.hash-key is too short: at least "
                    + MIN_HASH_KEY_LENGTH + " characters are required, e.g. `openssl rand -base64 32`");
        }
        SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC);
        try {
            Mac.getInstance(HMAC).init(spec);   // fail at startup, not on the first event
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("auditflow.redaction: " + HMAC + " is not usable: " + e.getMessage(), e);
        }
        return spec;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** Apply every configured rule to the event tree, mutating it in place. */
    public void redact(JsonNode root) {
        if (!properties.isEnabled() || root == null) {
            return;
        }
        for (RedactionProperties.Rule rule : properties.getRules()) {
            if (rule.getField() == null || rule.getField().isBlank()) {
                continue;
            }
            try {
                applyRule(root, rule);
            } catch (Exception e) {
                // Never let a redaction rule break publishing; log and continue.
                logger.warn("Redaction rule for field '{}' failed: {}", rule.getField(), e.getMessage());
            }
        }
    }

    private void applyRule(JsonNode root, RedactionProperties.Rule rule) {
        List<String> tokens = parsePath(rule.getField());
        if (tokens.isEmpty()) {
            return;
        }
        JsonNode parent = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            parent = step(parent, tokens.get(i));
            if (parent == null || parent.isMissingNode() || parent.isNull()) {
                return; // path absent — nothing to redact
            }
        }
        applyAction(parent, tokens.get(tokens.size() - 1), rule.getAction());
    }

    private JsonNode step(JsonNode node, String token) {
        if (node == null) {
            return null;
        }
        if (node.isArray() && isInteger(token)) {
            try {
                return node.get(Integer.parseInt(token));
            } catch (NumberFormatException ex) {
                logger.debug("Invalid array index token '{}' while traversing redaction path", token, ex);
                return null;
            }
        }
        if (node.isObject()) {
            return node.get(token);
        }
        return null;
    }

    private void applyAction(JsonNode parent, String leaf, RedactionProperties.Action action) {
        if (parent instanceof ObjectNode object) {
            if (!object.has(leaf)) {
                return;
            }
            switch (action) {
                case DROP -> object.remove(leaf);
                case MASK -> object.put(leaf, properties.getMask());
                case HASH -> object.put(leaf, hash(textOf(object.get(leaf))));
            }
        } else if (parent instanceof ArrayNode array && isInteger(leaf)) {
            final int index;
            try {
                index = Integer.parseInt(leaf);
            } catch (NumberFormatException ex) {
                logger.debug("Invalid array leaf index '{}' while applying redaction action", leaf, ex);
                return;
            }
            if (index < 0 || index >= array.size()) {
                return;
            }
            switch (action) {
                case DROP -> array.remove(index);
                case MASK -> array.set(index, array.textNode(properties.getMask()));
                case HASH -> array.set(index, array.textNode(hash(textOf(array.get(index)))));
            }
        }
    }

    /** Split a path like {@code items[0].name} into {@code [items, 0, name]}. */
    private List<String> parsePath(String path) {
        List<String> tokens = new ArrayList<>();
        for (String segment : path.split("\\.")) {
            if (segment.isEmpty()) {
                continue;
            }
            int bracket = segment.indexOf('[');
            if (bracket < 0) {
                tokens.add(segment);
                continue;
            }
            String name = segment.substring(0, bracket);
            if (!name.isEmpty()) {
                tokens.add(name);
            }
            // Expand each [n] index suffix.
            for (String part : segment.substring(bracket).split("\\[")) {
                String idx = part.replace("]", "").trim();
                if (!idx.isEmpty()) {
                    tokens.add(idx);
                }
            }
        }
        return tokens;
    }

    private String textOf(JsonNode node) {
        return node.isValueNode() ? node.asText() : node.toString();
    }

    private boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * HMAC-SHA256 of the value under the configured key, as lower-case hex (64 characters). If it
     * cannot be computed the value is masked: a redaction rule never lets the original through.
     */
    private String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC);   // a Mac is not thread-safe: one per call
            mac.init(hashKey);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | RuntimeException e) {
            logger.error("Redaction could not hash a value ({}); masking it instead", e.getMessage());
            return properties.getMask();
        }
    }
}
