package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration.ConditionProperties;
import io.labs64.audit.config.AuditFlowConfiguration.ConditionRule;

/** cidr / wildcard operators and nested rule groups. */
class ConditionEvaluatorExtensionsTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode event(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static ConditionRule rule(String field, String operator, String value) {
        ConditionRule r = new ConditionRule();
        r.setField(field);
        r.setOperator(operator);
        r.setValue(value);
        return r;
    }

    private static ConditionRule group(String match, ConditionRule... rules) {
        ConditionRule g = new ConditionRule();
        g.setMatch(match);
        g.setRules(List.of(rules));
        return g;
    }

    private static ConditionProperties condition(String match, ConditionRule... rules) {
        ConditionProperties c = new ConditionProperties();
        c.setMatch(match);
        c.setRules(new ArrayList<>(List.of(rules)));
        return c;
    }

    // --- cidr ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "10.1.2.3|10.0.0.0/8|true",
            "11.1.2.3|10.0.0.0/8|false",
            "192.168.1.7|10.0.0.0/8, 192.168.1.0/24|true",
            "192.168.1.7|192.168.1.7|true",
            "0.0.0.1|0.0.0.0/0|true",
            "2001:db8::1|2001:db8::/32|true",
            "2001:db9::1|2001:db8::/32|false",
            "::ffff:10.0.0.1|::ffff:10.0.0.0/104|true",
            "[2001:db8::1]|2001:db8::/32|true",
            "10.0.0.1|2001:db8::/32|false"})
    void cidrMatchesIpv4AndIpv6(String address, String networks, boolean expected) {
        assertEquals(expected, ConditionEvaluator.inAnyCidr(address, networks));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "example.com|10.0.0.0/8",      // never resolved: hostnames are not addresses
            "999.1.1.1|0.0.0.0/0",
            "10.0.0.1|not-a-network",
            "1.2.3|1.0.0.0/8",
            "2001:db8:::1|::/0"})
    void unparseableAddressesOrNetworksDecideNothing(String address, String networks) {
        assertNull(ConditionEvaluator.inAnyCidr(address, networks));
    }

    @Test
    void notCidrIsFalseForAnUnparseableAddress() throws Exception {
        JsonNode e = event("{\"extra\":{\"ip\":\"garbage\"}}");
        assertFalse(evaluator.evaluate(e, condition("all", rule("extra.ip", "cidr", "10.0.0.0/8"))));
        assertFalse(evaluator.evaluate(e, condition("all", rule("extra.ip", "notCidr", "10.0.0.0/8"))));
        JsonNode outside = event("{\"extra\":{\"ip\":\"8.8.8.8\"}}");
        assertTrue(evaluator.evaluate(outside, condition("all", rule("extra.ip", "notCidr", "10.0.0.0/8"))));
    }

    // --- wildcard ---

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "licensee/validate|licensee/*|true",
            "licensee/validate|*/validate|true",
            "licensee/validate|lic?nsee/*|true",
            "licensee/validate|product/*|false",
            "abc|*|true",
            "|*|true",
            "abc|a*b*c|true",
            "abc|a*b*d|false",
            "Licensee/x|licensee/*|false"})
    void globMatchesStarAndQuestionMark(String value, String pattern, boolean expected) {
        assertEquals(expected, ConditionEvaluator.globMatches(value == null ? "" : value, pattern));
    }

    @Test
    void wildcardTakesSeveralPatternsAndHostilePatternsStayFast() throws Exception {
        JsonNode e = event("{\"extra\":{\"actionName\":\"product/create\"}}");
        assertTrue(evaluator.evaluate(e, condition("all", rule("extra.actionName", "wildcard", "licensee/*, product/*"))));
        assertTrue(evaluator.evaluate(e, condition("all", rule("extra.actionName", "notWildcard", "userinterface/*"))));
        String value = "a".repeat(5000);
        String pattern = "*a*a*a*a*a*a*a*a*a*a*a*b";
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertFalse(ConditionEvaluator.globMatches(value, pattern)));
    }

    // --- nested groups ---

    @Test
    void aNestedGroupExpressesAAndBOrC() throws Exception {
        // eventType = api.call AND (status >= 400 OR actionStatus = FAILURE)
        ConditionProperties c = condition("all",
                rule("eventType", "eq", "api.call"),
                group("any",
                        rule("extra.responseStatus", "gte", "400"),
                        rule("extra.actionStatus", "eq", "FAILURE")));
        assertTrue(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"responseStatus\":500}}"), c));
        assertTrue(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"responseStatus\":200,\"actionStatus\":\"FAILURE\"}}"), c));
        assertFalse(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"responseStatus\":200}}"), c));
        assertFalse(evaluator.evaluate(event("{\"eventType\":\"other\",\"extra\":{\"responseStatus\":500}}"), c));
    }

    @Test
    void groupsNestDeeperThanTheLimitNeverMatch() throws Exception {
        ConditionRule inner = rule("eventType", "exists", null);
        for (int i = 0; i < ConditionEvaluator.MAX_DEPTH; i++) {
            inner = group("all", inner);
        }
        assertFalse(evaluator.evaluate(event("{\"eventType\":\"x\"}"), condition("all", inner)));
    }

    @Test
    void explainNamesTheDecidingRule() throws Exception {
        ConditionProperties c = condition("all", rule("eventType", "eq", "api.call"), rule("extra.responseStatus", "gte", "400"));
        ConditionEvaluator.Explanation e = evaluator.explain(event("{\"eventType\":\"api.call\",\"extra\":{\"responseStatus\":200}}"), c);
        assertFalse(e.matched());
        assertTrue(e.reason().contains("not matched: extra.responseStatus gte '400'"), e.reason());
    }

    @Test
    void unknownOperatorsAreReportedAlsoInsideGroups() {
        ConditionProperties c = condition("all", rule("a", "eq", "1"), group("any", rule("b", "startWith", "x")));
        assertEquals(List.of("startWith"), evaluator.unknownOperators(c));
    }

    @Test
    void groupsParseFromATenantFile() throws Exception {
        String yaml = """
                tenantId: acme
                pipelines:
                  - name: errors
                    enabled: true
                    condition:
                      match: all
                      rules:
                        - field: eventType
                          operator: eq
                          value: api.call
                        - match: any
                          rules:
                            - field: extra.ip
                              operator: cidr
                              value: 10.0.0.0/8
                            - field: extra.actionName
                              operator: wildcard
                              value: "admin/*"
                    sink:
                      name: logging_sink
                """;
        var cfg = new io.labs64.audit.tenant.TenantConfigParser(new ObjectMapper()).parse(yaml);
        ConditionProperties c = cfg.pipelines().get(0).getCondition();
        assertTrue(c.getRules().get(1).isGroup());
        assertTrue(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"ip\":\"10.9.9.9\"}}"), c));
        assertTrue(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"actionName\":\"admin/users\"}}"), c));
        assertFalse(evaluator.evaluate(event("{\"eventType\":\"api.call\",\"extra\":{\"ip\":\"8.8.8.8\"}}"), c));
    }
}
