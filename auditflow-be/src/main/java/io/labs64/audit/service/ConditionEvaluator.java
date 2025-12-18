package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for evaluating pipeline conditions against incoming audit events.
 * Supports various operators for flexible rule matching.
 */
@Service
public class ConditionEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(ConditionEvaluator.class);

    private final ObjectMapper objectMapper;

    public ConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate if an event matches the given condition.
     *
     * @param message   The JSON event message
     * @param condition The condition configuration (may be null)
     * @return true if the event matches the condition or if no condition is specified
     */
    public boolean evaluate(String message, ConditionProperties condition) {
        // No condition means always match
        if (condition == null || condition.getRules() == null || condition.getRules().isEmpty()) {
            return true;
        }

        try {
            JsonNode eventJson = objectMapper.readTree(message);
            return evaluateCondition(eventJson, condition);
        } catch (Exception e) {
            logger.error("Failed to parse event JSON for condition evaluation: {}", e.getMessage());
            // On parse error, we might want to still process (fail open) or reject (fail closed)
            // Default to fail open - process the event
            return true;
        }
    }

    private boolean evaluateCondition(JsonNode eventJson, ConditionProperties condition) {
        List<ConditionRule> rules = condition.getRules();
        String matchMode = condition.getMatch() != null ? condition.getMatch().toLowerCase() : "all";

        if ("any".equals(matchMode)) {
            // OR logic - at least one rule must match
            for (ConditionRule rule : rules) {
                if (evaluateRule(eventJson, rule)) {
                    logger.debug("Condition matched (any): rule field='{}' operator='{}' value='{}'",
                            rule.getField(), rule.getOperator(), rule.getValue());
                    return true;
                }
            }
            logger.debug("No rules matched for 'any' condition");
            return false;
        } else {
            // AND logic (default "all") - all rules must match
            for (ConditionRule rule : rules) {
                if (!evaluateRule(eventJson, rule)) {
                    logger.debug("Condition not matched (all): rule field='{}' operator='{}' value='{}'",
                            rule.getField(), rule.getOperator(), rule.getValue());
                    return false;
                }
            }
            logger.debug("All rules matched for 'all' condition");
            return true;
        }
    }

    private boolean evaluateRule(JsonNode eventJson, ConditionRule rule) {
        if (rule.getField() == null || rule.getOperator() == null) {
            logger.warn("Invalid rule: field or operator is null");
            return false;
        }

        JsonNode fieldNode = getFieldValue(eventJson, rule.getField());
        String operator = rule.getOperator().toLowerCase();
        String expectedValue = rule.getValue();

        // Handle 'exists' operator separately as it doesn't need a value
        if ("exists".equals(operator)) {
            boolean exists = fieldNode != null && !fieldNode.isMissingNode() && !fieldNode.isNull();
            logger.trace("Rule 'exists' for field '{}': {}", rule.getField(), exists);
            return exists;
        }

        if ("notexists".equals(operator) || "not_exists".equals(operator)) {
            boolean notExists = fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull();
            logger.trace("Rule 'notExists' for field '{}': {}", rule.getField(), notExists);
            return notExists;
        }

        // For all other operators, we need the field value as string
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            logger.trace("Field '{}' not found or null, rule returns false", rule.getField());
            return false;
        }

        String actualValue = fieldNode.isTextual() ? fieldNode.asText() : fieldNode.toString();

        return switch (operator) {
            case "eq", "equals" -> actualValue.equals(expectedValue);
            case "neq", "not_equals", "notequals" -> !actualValue.equals(expectedValue);
            case "contains" -> actualValue.contains(expectedValue);
            case "not_contains", "notcontains" -> !actualValue.contains(expectedValue);
            case "starts_with", "startswith" -> actualValue.startsWith(expectedValue);
            case "ends_with", "endswith" -> actualValue.endsWith(expectedValue);
            case "in" -> evaluateIn(actualValue, expectedValue);
            case "not_in", "notin" -> !evaluateIn(actualValue, expectedValue);
            case "regex", "matches" -> evaluateRegex(actualValue, expectedValue);
            case "gt" -> compareNumbers(actualValue, expectedValue) > 0;
            case "gte", "ge" -> compareNumbers(actualValue, expectedValue) >= 0;
            case "lt" -> compareNumbers(actualValue, expectedValue) < 0;
            case "lte", "le" -> compareNumbers(actualValue, expectedValue) <= 0;
            case "eq_ignore_case", "eqignorecase" -> actualValue.equalsIgnoreCase(expectedValue);
            default -> {
                logger.warn("Unknown operator '{}', treating as false", operator);
                yield false;
            }
        };
    }

    /**
     * Navigate JSON using dot-notation path (e.g., "extra.action_name")
     */
    private JsonNode getFieldValue(JsonNode root, String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            return null;
        }

        String[] pathParts = fieldPath.split("\\.");
        JsonNode current = root;

        for (String part : pathParts) {
            if (current == null || current.isMissingNode()) {
                return null;
            }

            // Handle array access like "items[0]"
            if (part.contains("[")) {
                int bracketStart = part.indexOf('[');
                int bracketEnd = part.indexOf(']');
                if (bracketEnd > bracketStart) {
                    String fieldName = part.substring(0, bracketStart);
                    String indexStr = part.substring(bracketStart + 1, bracketEnd);

                    current = current.get(fieldName);
                    if (current != null && current.isArray()) {
                        try {
                            int index = Integer.parseInt(indexStr);
                            current = current.get(index);
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    } else {
                        return null;
                    }
                    continue;
                }
            }

            current = current.get(part);
        }

        return current;
    }

    /**
     * Check if actualValue is in a comma-separated list of expected values
     */
    private boolean evaluateIn(String actualValue, String expectedValues) {
        if (expectedValues == null) {
            return false;
        }
        List<String> values = Arrays.asList(expectedValues.split(","));
        return values.stream()
                .map(String::trim)
                .anyMatch(v -> v.equals(actualValue));
    }

    /**
     * Evaluate regex pattern match
     */
    private boolean evaluateRegex(String actualValue, String pattern) {
        if (pattern == null) {
            return false;
        }
        try {
            return Pattern.matches(pattern, actualValue);
        } catch (PatternSyntaxException e) {
            logger.warn("Invalid regex pattern '{}': {}", pattern, e.getMessage());
            return false;
        }
    }

    /**
     * Compare two values as numbers
     */
    private int compareNumbers(String actual, String expected) {
        try {
            double actualNum = Double.parseDouble(actual);
            double expectedNum = Double.parseDouble(expected);
            return Double.compare(actualNum, expectedNum);
        } catch (NumberFormatException e) {
            // Fall back to string comparison
            return actual.compareTo(expected);
        }
    }
}

