package io.labs64.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryIdempotencyServiceTest {

    private InMemoryIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryIdempotencyService(Duration.ofMinutes(5), Duration.ofHours(24));
    }

    @Test
    @DisplayName("claim succeeds the first time and is refused for an in-flight duplicate")
    void claimIsExclusive() {
        assertTrue(service.claim("abc"));
        assertFalse(service.claim("abc"), "second claim while still in-flight must be refused");
    }

    @Test
    @DisplayName("an expired claim can be re-claimed")
    void expiredClaimIsReclaimable() {
        InMemoryIdempotencyService shortTtl =
                new InMemoryIdempotencyService(Duration.ofMillis(1), Duration.ofHours(24));
        assertTrue(shortTtl.claim("abc"));
        sleep(5);
        assertTrue(shortTtl.claim("abc"), "claim should be re-acquirable after the claim TTL elapses");
    }

    @Test
    @DisplayName("release lets the same event be claimed again immediately")
    void releaseAllowsReclaim() {
        assertTrue(service.claim("abc"));
        service.release("abc");
        assertTrue(service.claim("abc"));
    }

    @Test
    @DisplayName("markProcessed keeps the event deduplicated against late duplicates")
    void markProcessedDeduplicates() {
        assertTrue(service.claim("abc"));
        service.markProcessed("abc");
        assertFalse(service.claim("abc"), "a processed event must not be claimable again within the done TTL");
    }

    @Test
    @DisplayName("per-pipeline completion is tracked independently and only after markPipelineDone")
    void pipelineCompletionTracked() {
        assertFalse(service.isPipelineDone("abc", "p1"));
        service.markPipelineDone("abc", "p1");
        assertTrue(service.isPipelineDone("abc", "p1"));
        assertFalse(service.isPipelineDone("abc", "p2"), "other pipelines are unaffected");
    }

    @Test
    @DisplayName("a pipeline completion expires once its retention elapses")
    void pipelineCompletionExpires() {
        InMemoryIdempotencyService shortTtl =
                new InMemoryIdempotencyService(Duration.ofMinutes(5), Duration.ofMillis(1));
        shortTtl.markPipelineDone("abc", "p1");
        sleep(5);
        assertFalse(shortTtl.isPipelineDone("abc", "p1"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
