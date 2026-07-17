package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisTenantRateLimiterTest {

    @Test
    void allowedWhenScriptReturnsOne() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var limiter = new RedisTenantRateLimiter(template);
        assertTrue(limiter.tryAcquire("acme", 200, 400));
    }

    @Test
    void deniedWhenScriptReturnsZero() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        var limiter = new RedisTenantRateLimiter(template);
        assertFalse(limiter.tryAcquire("acme", 200, 400));
    }

    @Test
    void keyIsScopedPerTenant() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        var limiter = new RedisTenantRateLimiter(template);
        limiter.tryAcquire("acme", 200, 400);
        verify(template).execute(any(RedisScript.class),
                eq(List.of("ratelimit:tenant:acme")), any(Object[].class));
    }

    @Test
    void nonPositiveRateSkipsRedisEntirely() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        var limiter = new RedisTenantRateLimiter(template);
        assertTrue(limiter.tryAcquire("acme", 0, 0));
        verifyNoInteractions(template);
    }
}
