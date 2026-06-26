package io.labs64.auditflow.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublishResultTest {

    private static HttpHeaders headers(Map<String, List<String>> map) {
        return HttpHeaders.of(map, (k, v) -> true);
    }

    @Test
    void parsesEventIdAndReceivedAtHeaders() {
        UUID id = UUID.randomUUID();
        PublishResult result = PublishResult.from(200, headers(Map.of(
                "X-Audit-Event-Id", List.of(id.toString()),
                "X-Audit-Received-At", List.of("2025-11-30T10:15:30Z"))));

        assertEquals(id, result.eventId());
        assertEquals(OffsetDateTime.parse("2025-11-30T10:15:30Z"), result.receivedAt());
        assertEquals(200, result.httpStatus());
    }

    @Test
    void missingHeadersYieldNulls() {
        PublishResult result = PublishResult.from(200, headers(Map.of()));

        assertNull(result.eventId());
        assertNull(result.receivedAt());
        assertEquals(200, result.httpStatus());
    }
}
