package io.labs64.auditflow.client;

import io.labs64.auditflow.client.exception.ValidationException;
import io.labs64.auditflow.client.support.StubAuditServer;
import io.labs64.auditflow.client.support.StubAuditServer.CannedResponse;
import io.labs64.auditflow.model.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAuditFlowClientAsyncTest {

    private StubAuditServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubAuditServer();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void publishAsyncCompletesWithResult() throws Exception {
        server.enqueue(CannedResponse.ok(Map.of("X-Audit-Received-At", "2025-11-30T10:15:30Z")));
        AuditFlowClient client = AuditFlowClient.builder().baseUrl(server.baseUrl()).build();

        CompletableFuture<PublishResult> future = client.publishAsync(new AuditEvent().eventType("x"));
        PublishResult result = future.get(5, TimeUnit.SECONDS);

        assertEquals(200, result.httpStatus());
    }

    @Test
    void publishAsyncCompletesExceptionallyOnError() {
        server.enqueue(CannedResponse.error(400,
                "{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"));
        AuditFlowClient client = AuditFlowClient.builder().baseUrl(server.baseUrl()).retry(RetryPolicy.none()).build();

        CompletableFuture<PublishResult> future = client.publishAsync(new AuditEvent());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ValidationException.class, ex.getCause());
    }

    @Test
    void fireAndForgetRoutesErrorsToHandlerAndNeverThrows() throws Exception {
        server.enqueue(CannedResponse.error(400,
                "{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad\",\"timestamp\":\"2025-11-30T10:15:30Z\"}"));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> captured = new AtomicReference<>();
        AuditFlowClient client = AuditFlowClient.builder()
                .baseUrl(server.baseUrl())
                .retry(RetryPolicy.none())
                .errorHandler((event, error) -> {
                    captured.set(error);
                    latch.countDown();
                })
                .build();

        assertDoesNotThrow(() -> client.fireAndForget(new AuditEvent()));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "error handler was not invoked");
        assertInstanceOf(ValidationException.class, captured.get());
    }
}
