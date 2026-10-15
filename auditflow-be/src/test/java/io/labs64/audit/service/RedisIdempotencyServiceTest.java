package io.labs64.audit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new RedisIdempotencyService(redisTemplate, Duration.ofMinutes(5), Duration.ofHours(24));
    }

    @Test
    @DisplayName("claim returns true when the key was newly set")
    void claimSucceedsWhenKeyAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("evt:abc"), eq("processing"), any(Duration.class))).thenReturn(true);

        assertTrue(service.claim("abc"));
        verify(valueOps).setIfAbsent("evt:abc", "processing", Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("claim returns false when the key already exists (duplicate)")
    void claimFailsWhenKeyPresent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("evt:abc"), eq("processing"), any(Duration.class))).thenReturn(false);

        assertFalse(service.claim("abc"));
    }

    @Test
    @DisplayName("takeOver succeeds for a claim that is not done, with the claim TTL")
    void takeOverOfUnfinishedClaim() {
        when(redisTemplate.execute(any(RedisScript.class), eq(List.of("evt:abc")),
                eq("done"), eq("processing"), eq("300000"))).thenReturn(1L);

        assertTrue(service.takeOver("abc"));
    }

    @Test
    @DisplayName("takeOver is refused for a done event")
    void takeOverOfDoneEventIsRefused() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        assertFalse(service.takeOver("abc"));
    }

    @Test
    @DisplayName("markProcessed re-sets the key with the longer done TTL")
    void markProcessedSetsDoneTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.markProcessed("abc");

        verify(valueOps).set("evt:abc", "done", Duration.ofHours(24));
    }

    @Test
    @DisplayName("release deletes the claim key")
    void releaseDeletesKey() {
        service.release("abc");
        verify(redisTemplate).delete("evt:abc");
    }
    private static final List<IdempotencyService.PipelineDelivery> BATCH = List.of(
            new IdempotencyService.PipelineDelivery("e1", "a"), new IdempotencyService.PipelineDelivery("e2", "a"),
            new IdempotencyService.PipelineDelivery("e3", "b"));

    @Test
    @DisplayName("arePipelinesDone asks for the whole batch in one MGET")
    void arePipelinesDoneIsOneMget() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.multiGet(List.of("evt:e1:pipe:a", "evt:e2:pipe:a", "evt:e3:pipe:b")))
                .thenReturn(java.util.Arrays.asList("done", null, "done"));

        assertEquals(List.of(true, false, true), service.arePipelinesDone(BATCH));
        verify(redisTemplate, never()).hasKey(anyString());
    }

    @Test
    @DisplayName("markPipelinesDone sets every marker with the done TTL in one script call")
    void markPipelinesDoneIsOneScriptCall() {
        service.markPipelinesDone(BATCH);

        verify(redisTemplate, times(1)).execute(any(RedisScript.class),
                eq(List.of("evt:e1:pipe:a", "evt:e2:pipe:a", "evt:e3:pipe:b")), eq("done"), eq("86400000"));
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    @DisplayName("a Redis that refuses the script (cluster, keys in several slots) gets one SET per marker")
    void markPipelinesDoneFallsBackToOneSetEach() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("CROSSSLOT Keys in request don't hash to the same slot"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.markPipelinesDone(BATCH);

        verify(valueOps).set("evt:e1:pipe:a", "done", Duration.ofHours(24));
        verify(valueOps).set("evt:e2:pipe:a", "done", Duration.ofHours(24));
        verify(valueOps).set("evt:e3:pipe:b", "done", Duration.ofHours(24));
    }

    @Test
    @DisplayName("nothing to ask or mark: no call to Redis")
    void emptyBatchesDoNotCallRedis() {
        assertEquals(List.of(), service.arePipelinesDone(List.of()));
        service.markPipelinesDone(List.of());
        verifyNoInteractions(redisTemplate);
    }
}
