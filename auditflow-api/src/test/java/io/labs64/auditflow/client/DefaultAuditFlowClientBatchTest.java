package io.labs64.auditflow.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.auditflow.client.exception.AuditFlowException;
import io.labs64.auditflow.client.support.StubAuditServer;
import io.labs64.auditflow.client.support.StubAuditServer.CannedResponse;
import io.labs64.auditflow.model.AuditEvent;

class DefaultAuditFlowClientBatchTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private StubAuditServer server;

    @BeforeEach
    void start() throws Exception {
        server = new StubAuditServer();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private AuditFlowClient client(RetryPolicy retry) {
        return AuditFlowClient.builder().baseUrl(server.baseUrl()).token("jwt").retry(retry).build();
    }

    private static List<AuditEvent> events(int n) {
        List<AuditEvent> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(AuditEvents.builder("api.call").sourceSystem("test").extra("n", i).build());
        }
        return list;
    }

    /** A 200 batch response: one entry per status, "A" accepted, otherwise a rejection code. */
    private static CannedResponse results(String... statuses) {
        StringBuilder b = new StringBuilder("{\"acceptedCount\":0,\"rejectedCount\":0,\"results\":[");
        for (int i = 0; i < statuses.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            if ("A".equals(statuses[i])) {
                b.append("{\"index\":").append(i).append(",\"status\":\"ACCEPTED\"}");
            } else {
                b.append("{\"index\":").append(i).append(",\"status\":\"REJECTED\",\"error\":{\"code\":\"")
                        .append(statuses[i]).append("\",\"message\":\"m\",\"timestamp\":\"2026-10-02T10:00:00Z\"}}");
            }
        }
        return new CannedResponse(200, Map.of("Content-Type", "application/json"), b.append("]}").toString());
    }

    private JsonNode body(int request) throws Exception {
        return mapper.readTree(server.requests().get(request).body()).get("events");
    }

    @Test
    void everyEventGetsItsIdBeforeSendingAndAllAccepted() throws Exception {
        server.enqueue(results("A", "A"));

        BatchResult result = client(RetryPolicy.exponential(3)).publishBatch(events(2));

        assertTrue(result.allAccepted());
        assertEquals("/api/v1/audit/publish/batch", server.lastRequest().path());
        JsonNode sent = body(0);
        assertEquals(result.entries().get(0).eventId().toString(), sent.get(0).get("eventId").asText());
        assertNotNull(result.entries().get(1).eventId());
    }

    @Test
    void onlyRetryableRejectionsAreSentAgainWithTheSameEventId() throws Exception {
        server.enqueue(results("A", "PUBLISH_FAILED", "VALIDATION_ERROR"), results("A"));

        BatchResult result = client(RetryPolicy.exponential(3)).publishBatch(events(3));

        assertEquals(2, server.requestCount());
        assertEquals(1, body(1).size());
        assertEquals(body(0).get(1).get("eventId").asText(), body(1).get(0).get("eventId").asText());
        assertTrue(result.entries().get(0).accepted());
        assertTrue(result.entries().get(1).accepted(), "the PUBLISH_FAILED entry was retried and accepted");
        assertFalse(result.entries().get(2).accepted());
        assertEquals("VALIDATION_ERROR", result.entries().get(2).errorCode());
    }

    @Test
    void aRejectionStillFailingAfterTheLastAttemptIsReturned() {
        server.enqueue(results("TENANT_RATE_LIMITED"), results("TENANT_RATE_LIMITED"));

        BatchResult result = client(RetryPolicy.exponential(2)).publishBatch(events(1));

        assertEquals(2, server.requestCount());
        assertEquals("TENANT_RATE_LIMITED", result.rejected().get(0).errorCode());
    }

    @Test
    void a503RetriesTheWholeRequest() {
        server.enqueue(CannedResponse.error(503, "{\"code\":\"PUBLISH_FAILED\",\"message\":\"down\",\"timestamp\":\"2026-10-02T10:00:00Z\"}"),
                results("A", "A"));

        BatchResult result = client(RetryPolicy.exponential(3)).publishBatch(events(2));

        assertEquals(2, server.requestCount());
        assertTrue(result.allAccepted());
    }

    @Test
    void aRefusedRequestThrows() {
        server.enqueue(CannedResponse.error(403, "{\"code\":\"TENANT_NOT_PROVISIONED\",\"message\":\"no\",\"timestamp\":\"2026-10-02T10:00:00Z\"}"));

        AuditFlowException e = assertThrows(AuditFlowException.class,
                () -> client(RetryPolicy.exponential(3)).publishBatch(events(1)));
        assertEquals(403, e.statusCode());
        assertEquals(1, server.requestCount());
    }

    @Test
    void moreThanOneHundredEventsAreSentInChunks() throws Exception {
        String[] hundred = new String[100];
        java.util.Arrays.fill(hundred, "A");
        String[] fifty = new String[50];
        java.util.Arrays.fill(fifty, "A");
        server.enqueue(results(hundred), results(hundred), results(fifty));

        BatchResult result = client(RetryPolicy.none()).publishBatch(events(250));

        assertEquals(3, server.requestCount());
        assertEquals(List.of(100, 100, 50), List.of(body(0).size(), body(1).size(), body(2).size()));
        assertEquals(250, result.acceptedCount());
        assertEquals(250, result.entries().stream().map(BatchResult.Entry::eventId).map(UUID::toString).distinct().count());
    }
}
