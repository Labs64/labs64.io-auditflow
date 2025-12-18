package io.labs64.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;

    private static final String SAMPLE_EVENT = """
            {
                "timestamp": "2025-12-15T18:59:16.572533965Z",
                "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
                "eventType": "api.call",
                "sourceSystem": "netlicensing/core",
                "tenantId": "V12345678",
                "geolocation": {
                    "lat": 48.1264019,
                    "lon": 11.5407647,
                    "countryCode": "DE",
                    "country": "Germany",
                    "region": "Bavaria",
                    "city": "Munich"
                },
                "extra": {
                    "userId": "customer123",
                    "userBU": "BU12345678",
                    "action_name": "login",
                    "action_status": "SUCCESS",
                    "action_message": "User logged in successfully",
                    "count": 42
                },
                "items": [
                    {"name": "item1", "value": 100},
                    {"name": "item2", "value": 200}
                ]
            }
            """;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        evaluator = new ConditionEvaluator(objectMapper);
    }

    @Test
    @DisplayName("Should return true when no condition is specified")
    void shouldReturnTrueWhenNoCondition() {
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, null));
    }

    @Test
    @DisplayName("Should return true when condition has no rules")
    void shouldReturnTrueWhenNoRules() {
        ConditionProperties condition = new ConditionProperties();
        condition.setRules(Collections.emptyList());
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'eq' operator")
    void shouldMatchWithEqOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "eq", "api.call"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should not match with 'eq' operator when value differs")
    void shouldNotMatchWithEqOperatorWhenValueDiffers() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "eq", "user.created"));
        assertFalse(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'neq' operator")
    void shouldMatchWithNeqOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "neq", "user.created"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'contains' operator")
    void shouldMatchWithContainsOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("sourceSystem", "contains", "licensing"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'startsWith' operator")
    void shouldMatchWithStartsWithOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("sourceSystem", "startsWith", "netlicensing"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'endsWith' operator")
    void shouldMatchWithEndsWithOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("sourceSystem", "endsWith", "core"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'in' operator")
    void shouldMatchWithInOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "in", "user.created,api.call,user.deleted"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should not match with 'in' operator when value not in list")
    void shouldNotMatchWithInOperatorWhenValueNotInList() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "in", "user.created,user.deleted"));
        assertFalse(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'notIn' operator")
    void shouldMatchWithNotInOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "not_in", "user.created,user.deleted"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'exists' operator")
    void shouldMatchWithExistsOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("tenantId", "exists", null));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should not match with 'exists' operator for non-existent field")
    void shouldNotMatchWithExistsOperatorForNonExistentField() {
        ConditionProperties condition = createCondition("all",
                createRule("nonExistentField", "exists", null));
        assertFalse(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'notExists' operator")
    void shouldMatchWithNotExistsOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("nonExistentField", "notExists", null));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with 'regex' operator")
    void shouldMatchWithRegexOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("tenantId", "regex", "V\\d+"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should navigate nested fields with dot notation")
    void shouldNavigateNestedFieldsWithDotNotation() {
        ConditionProperties condition = createCondition("all",
                createRule("geolocation.countryCode", "eq", "DE"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should navigate deeply nested fields")
    void shouldNavigateDeeplyNestedFields() {
        ConditionProperties condition = createCondition("all",
                createRule("extra.action_status", "eq", "SUCCESS"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should access array elements")
    void shouldAccessArrayElements() {
        ConditionProperties condition = createCondition("all",
                createRule("items[0].name", "eq", "item1"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match all rules with 'all' match mode")
    void shouldMatchAllRulesWithAllMatchMode() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "eq", "api.call"),
                createRule("sourceSystem", "startsWith", "netlicensing"),
                createRule("geolocation.countryCode", "eq", "DE"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should not match when any rule fails with 'all' match mode")
    void shouldNotMatchWhenAnyRuleFailsWithAllMatchMode() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "eq", "api.call"),
                createRule("sourceSystem", "eq", "wrong-system"));
        assertFalse(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match when any rule passes with 'any' match mode")
    void shouldMatchWhenAnyRulePassesWithAnyMatchMode() {
        ConditionProperties condition = createCondition("any",
                createRule("eventType", "eq", "wrong.type"),
                createRule("sourceSystem", "startsWith", "netlicensing"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should not match when no rule passes with 'any' match mode")
    void shouldNotMatchWhenNoRulePassesWithAnyMatchMode() {
        ConditionProperties condition = createCondition("any",
                createRule("eventType", "eq", "wrong.type"),
                createRule("sourceSystem", "eq", "wrong-system"));
        assertFalse(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should match with case-insensitive equals")
    void shouldMatchWithCaseInsensitiveEquals() {
        ConditionProperties condition = createCondition("all",
                createRule("extra.action_status", "eqIgnoreCase", "success"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should handle numeric comparison with gt operator")
    void shouldHandleNumericComparisonWithGtOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("extra.count", "gt", "40"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should handle numeric comparison with lt operator")
    void shouldHandleNumericComparisonWithLtOperator() {
        ConditionProperties condition = createCondition("all",
                createRule("extra.count", "lt", "50"));
        assertTrue(evaluator.evaluate(SAMPLE_EVENT, condition));
    }

    @Test
    @DisplayName("Should return true on invalid JSON (fail-open behavior)")
    void shouldReturnTrueOnInvalidJson() {
        ConditionProperties condition = createCondition("all",
                createRule("eventType", "eq", "api.call"));
        assertTrue(evaluator.evaluate("invalid json", condition));
    }

    private ConditionProperties createCondition(String match, ConditionRule... rules) {
        ConditionProperties condition = new ConditionProperties();
        condition.setMatch(match);
        condition.setRules(Arrays.asList(rules));
        return condition;
    }

    private ConditionRule createRule(String field, String operator, String value) {
        ConditionRule rule = new ConditionRule();
        rule.setField(field);
        rule.setOperator(operator);
        rule.setValue(value);
        return rule;
    }
}

