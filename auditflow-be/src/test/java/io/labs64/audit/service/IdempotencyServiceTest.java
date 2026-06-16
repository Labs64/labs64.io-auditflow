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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(redisTemplate, Duration.ofMinutes(5), Duration.ofHours(24));
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
}
