package io.labs64.auditflow.client;

import io.labs64.auditflow.client.exception.PublishFailedException;
import io.labs64.auditflow.client.exception.UnauthorizedException;
import io.labs64.auditflow.client.exception.ValidationException;
import io.labs64.auditflow.client.support.StubAuditServer;
import io.labs64.auditflow.client.support.StubAuditServer.CannedResponse;
import io.labs64.auditflow.model.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAuditFlowClientPublishTest {

    private StubAuditServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubAuditServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private AuditFlowClient client() {
        return AuditFlowClient.builder()
                .baseUrl(server.baseUrl())
                .token("jwt-123")
                .retry(RetryPolicy.exponential(3))
                .build();
    }

    @Test
    void publishSendsAuthorizedJsonAndAutoFillsIds() {
        UUID id = UUID.randomUUID();
        server.enqueue(CannedResponse.ok(Map.of(
                "X-Audit-Event-Id", id.toString(),
                "X-Audit-Received-At", "2025-11-30T10:15:30Z")));

        AuditEvent event = new AuditEvent().eventType("user.login");
        PublishResult result = client().publish(event);

        var request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/api/v1/audit/publish", request.path());
        assertEquals(List.of("Bearer jwt-123"), request.headers().get("Authorization"));
        assertNotNull(request.headers().get("X-correlation-id")); // JDK lowercases after first char
        assertTrue(request.body().contains("\"eventType\":\"user.login\""));
        assertTrue(request.body().contains("\"eventId\":")); // auto-filled
        assertEquals(id, result.eventId());
    }

    @Test
    void publishUsesDefaultSourceSystemWhenAbsent() {
        server.enqueue(CannedResponse.ok(Map.of()));
        AuditFlowClient client = AuditFlowClient.builder()
                .baseUrl(server.baseUrl())
                .defaultSourceSystem("netlicensing/core")
                .build();

        client.publish(new AuditEvent().eventType("api.call"));

        assertTrue(server.lastRequest().body().contains("\"sourceSystem\":\"netlicensing/core\""));
    }

    @Test
    void validationErrorMapsToValidationException() {
        server.enqueue(CannedResponse.error(400,
                "{\"code\":\"VALIDATION_ERROR\",\"message\":\"eventType must not be null\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> client().publish(new AuditEvent()));
        assertEquals(400, ex.statusCode());
        assertNotNull(ex.errorResponse());
        assertEquals("eventType must not be null", ex.errorResponse().getMessage());
    }

    @Test
    void unauthorizedMapsToUnauthorizedException() {
        server.enqueue(CannedResponse.error(401,
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"auth required\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"));

        assertThrows(UnauthorizedException.class,
                () -> client().publish(new AuditEvent().eventType("x")));
    }

    @Test
    void retriesOn503ThenSucceeds() {
        server.enqueue(
                CannedResponse.error(503, "{\"code\":\"PUBLISH_FAILED\",\"message\":\"broker down\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"),
                CannedResponse.ok(Map.of()));

        PublishResult result = client().publish(new AuditEvent().eventType("x"));

        assertEquals(200, result.httpStatus());
        assertEquals(2, server.requestCount());
    }

    @Test
    void exhaustedRetriesThrowsPublishFailed() {
        server.enqueue(
                CannedResponse.error(503, "{\"code\":\"PUBLISH_FAILED\",\"message\":\"down\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"),
                CannedResponse.error(503, "{\"code\":\"PUBLISH_FAILED\",\"message\":\"down\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"),
                CannedResponse.error(503, "{\"code\":\"PUBLISH_FAILED\",\"message\":\"down\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"));

        assertThrows(PublishFailedException.class,
                () -> client().publish(new AuditEvent().eventType("x")));
        assertEquals(3, server.requestCount());
    }
}
