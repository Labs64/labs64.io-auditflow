package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Service for evaluating pipeline conditions against incoming audit events.
 *
 * <p>A condition is a list of rules combined with {@code match: all} (AND, default) or
 * {@code match: any} (OR). A rule is either a comparison ({@code field}, {@code operator},
 * {@code value}) or a nested group ({@code match} + {@code rules}), so conditions such as
 * "A and (B or C)" need no extra pipelines. Nesting is limited to {@value #MAX_DEPTH} levels.</p>
 */
@Service
public class ConditionEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(ConditionEvaluator.class);

    /** Deepest allowed group nesting; deeper groups never match (fail closed). */
    public static final int MAX_DEPTH = 8;

    /** Every operator this evaluator understands (lower-case, including aliases). */
    public static final Set<String> OPERATORS = Set.of(
            "eq", "equals", "neq", "not_equals", "notequals", "contains", "not_contains", "notcontains",
            "starts_with", "startswith", "ends_with", "endswith", "in", "not_in", "notin", "regex", "matches",
            "gt", "gte", "ge", "lt", "lte", "le", "eq_ignore_case", "eqignorecase", "exists", "notexists",
            "not_exists", "cidr", "notcidr", "not_cidr", "wildcard", "notwildcard", "not_wildcard");

    /** Outcome of a condition with a short explanation (used by the pipeline dry run). */
    public record Explanation(boolean matched, String reason) {
    }

    /**
     * Evaluate if an already-parsed event matches the given condition.
     *
     * @param eventJson the parsed JSON event (never null — parsing happens upstream)
     * @param condition the condition configuration (may be null)
     * @return true if the event matches the condition or if no condition is specified
     */
    public boolean evaluate(JsonNode eventJson, ConditionProperties condition) {
        if (condition == null || condition.getRules() == null || condition.getRules().isEmpty()) {
            return true;
        }
        return evaluateGroup(eventJson, condition.getMatch(), condition.getRules(), 1, null);
    }

    /** Like {@link #evaluate}, plus which rule decided it. */
    public Explanation explain(JsonNode eventJson, ConditionProperties condition) {
        if (condition == null || condition.getRules() == null || condition.getRules().isEmpty()) {
            return new Explanation(true, "no condition");
        }
        List<String> trace = new ArrayList<>();
        boolean matched = evaluateGroup(eventJson, condition.getMatch(), condition.getRules(), 1, trace);
        return new Explanation(matched, String.join("; ", trace));
    }

    /** Operators used in the condition that this evaluator does not know (they never match). */
    public List<String> unknownOperators(ConditionProperties condition) {
        List<String> unknown = new ArrayList<>();
        if (condition != null) {
            collectUnknown(condition.getRules(), unknown);
        }
        return unknown;
    }

    private void collectUnknown(List<ConditionRule> rules, List<String> unknown) {
        if (rules == null) {
            return;
        }
        for (ConditionRule rule : rules) {
            if (rule.isGroup()) {
                collectUnknown(rule.getRules(), unknown);
            } else if (rule.getOperator() == null || !OPERATORS.contains(rule.getOperator().toLowerCase())) {
                unknown.add(String.valueOf(rule.getOperator()));
            }
        }
    }

    private boolean evaluateGroup(JsonNode eventJson, String match, List<ConditionRule> rules, int depth,
                                  List<String> trace) {
        if (depth > MAX_DEPTH) {
            logger.warn("Condition nested deeper than {} levels; treating as not matched", MAX_DEPTH);
            note(trace, "nested deeper than " + MAX_DEPTH + " levels");
            return false;
        }
        boolean any = match != null && "any".equalsIgnoreCase(match);
        for (ConditionRule rule : rules) {
            boolean result = rule.isGroup()
                    ? evaluateGroup(eventJson, rule.getMatch(), rule.getRules(), depth + 1, trace)
                    : evaluateRule(eventJson, rule);
            if (any && result) {
                note(trace, "matched " + describe(rule));
                return true;
            }
            if (!any && !result) {
                note(trace, "not matched: " + describe(rule));
                return false;
            }
        }
        note(trace, any ? "no rule of an 'any' group matched" : "all rules matched");
        return !any;
    }

    private static void note(List<String> trace, String text) {
        if (trace != null) {
            trace.add(text);
        }
    }

    private static String describe(ConditionRule rule) {
        if (rule.isGroup()) {
            return "group(match=" + (rule.getMatch() == null ? "all" : rule.getMatch()) + ", "
                    + rule.getRules().size() + " rule(s))";
        }
        return rule.getField() + " " + rule.getOperator() + (rule.getValue() == null ? "" : " '" + rule.getValue() + "'");
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
            // An unparseable address or network never matches, so notCidr is false for it too.
            case "cidr" -> inAnyCidr(actualValue, expectedValue) == Boolean.TRUE;
            case "notcidr", "not_cidr" -> inAnyCidr(actualValue, expectedValue) == Boolean.FALSE;
            case "wildcard" -> anyWildcard(actualValue, expectedValue);
            case "notwildcard", "not_wildcard" -> !anyWildcard(actualValue, expectedValue);
            default -> {
                logger.warn("Unknown operator '{}', treating as false", operator);
                yield false;
            }
        };
    }

    /**
     * Navigate JSON using dot-notation path (e.g., "extra.actionName")
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

    // --- cidr ---------------------------------------------------------------------------------

    /**
     * TRUE if the address is in one of the comma-separated networks ({@code 10.0.0.0/8,
     * 2001:db8::/32}; a bare address is a /32 or /128), FALSE if it is in none, null if the address
     * or every network is unparseable. Literals only: nothing here can trigger a DNS lookup.
     */
    static Boolean inAnyCidr(String address, String networks) {
        byte[] ip = parseIpLiteral(address);
        if (ip == null || networks == null) {
            return null;
        }
        boolean anyValid = false;
        for (String raw : networks.split(",")) {
            String network = raw.trim();
            if (network.isEmpty()) {
                continue;
            }
            int slash = network.indexOf('/');
            byte[] base = parseIpLiteral(slash < 0 ? network : network.substring(0, slash));
            if (base == null) {
                continue;
            }
            int bits = base.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(network.substring(slash + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (bits < 0 || bits > base.length * 8) {
                    continue;
                }
            }
            anyValid = true;
            if (base.length == ip.length && samePrefix(ip, base, bits)) {
                return Boolean.TRUE;
            }
        }
        return anyValid ? Boolean.FALSE : null;
    }

    private static boolean samePrefix(byte[] a, byte[] b, int bits) {
        BigInteger mask = bits == 0 ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE).shiftLeft(a.length * 8 - bits);
        return new BigInteger(1, a).and(mask).equals(new BigInteger(1, b).and(mask));
    }

    /** IPv4 dotted quad or IPv6 literal (incl. {@code ::} and an embedded IPv4 tail); null otherwise. */
    static byte[] parseIpLiteral(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        int zone = v.indexOf('%');
        if (zone >= 0) {
            v = v.substring(0, zone);
        }
        return v.contains(":") ? parseIpv6(v) : parseIpv4(v);
    }

    private static byte[] parseIpv4(String v) {
        String[] parts = v.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].chars().allMatch(Character::isDigit)) {
                return null;
            }
            int n = Integer.parseInt(parts[i]);
            if (n > 255) {
                return null;
            }
            out[i] = (byte) n;
        }
        return out;
    }

    private static byte[] parseIpv6(String v) {
        int doubleColon = v.indexOf("::");
        if (doubleColon >= 0 && v.indexOf("::", doubleColon + 1) >= 0) {
            return null;
        }
        List<Integer> head = new ArrayList<>();
        List<Integer> tail = new ArrayList<>();
        String left = doubleColon >= 0 ? v.substring(0, doubleColon) : v;
        String right = doubleColon >= 0 ? v.substring(doubleColon + 2) : "";
        if (!groups(left, head) || !groups(right, tail)) {
            return null;
        }
        int total = head.size() + tail.size();
        if ((doubleColon < 0 && total != 8) || (doubleColon >= 0 && total > 7)) {
            return null;
        }
        byte[] out = new byte[16];
        int i = 0;
        for (int g : head) {
            out[i++] = (byte) (g >> 8);
            out[i++] = (byte) g;
        }
        i = 16 - tail.size() * 2;
        for (int g : tail) {
            out[i++] = (byte) (g >> 8);
            out[i++] = (byte) g;
        }
        return out;
    }

    /** Parse colon-separated hex groups; a trailing dotted quad counts as two groups. */
    private static boolean groups(String s, List<Integer> out) {
        if (s.isEmpty()) {
            return true;
        }
        String[] parts = s.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (i == parts.length - 1 && p.contains(".")) {
                byte[] v4 = parseIpv4(p);
                if (v4 == null) {
                    return false;
                }
                out.add(((v4[0] & 0xff) << 8) | (v4[1] & 0xff));
                out.add(((v4[2] & 0xff) << 8) | (v4[3] & 0xff));
                continue;
            }
            if (p.isEmpty() || p.length() > 4 || !p.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return false;
            }
            out.add(Integer.parseInt(p, 16));
        }
        return true;
    }

    // --- wildcard -----------------------------------------------------------------------------

    /** True if the value matches one of the comma-separated glob patterns ({@code *}, {@code ?}). */
    static boolean anyWildcard(String value, String patterns) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns.split(",")) {
            if (globMatches(value, pattern.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Glob match in O(value x pattern) with no backtracking explosion: {@code *} is any run of
     * characters (also empty), {@code ?} exactly one. Case-sensitive.
     */
    static boolean globMatches(String value, String pattern) {
        int v = 0;
        int p = 0;
        int star = -1;
        int mark = 0;
        while (v < value.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                v++;
                p++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                mark = v;
            } else if (star >= 0) {
                p = star + 1;
                v = ++mark;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
