package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.labs64.audit.config.RedactionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies the configured {@link RedactionProperties} rules to an event tree in place, masking,
 * hashing, or dropping PII fields <em>before publish</em> so raw PII never enters the broker.
 *
 * <p>Field paths use dot notation with array indices, matching the condition engine
 * (e.g. {@code extra.userEmail}, {@code items[0].card}). A path that does not exist is a no-op.</p>
 */
@Service
public class RedactionService {

    private static final Logger logger = LoggerFactory.getLogger(RedactionService.class);

    private final RedactionProperties properties;

    public RedactionService(RedactionProperties properties) {
        this.properties = properties;
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
                case HASH -> object.put(leaf, sha256(textOf(object.get(leaf))));
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
                case HASH -> array.set(index, array.textNode(sha256(textOf(array.get(index)))));
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
