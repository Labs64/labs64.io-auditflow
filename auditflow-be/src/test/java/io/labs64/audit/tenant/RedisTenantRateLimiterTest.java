package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

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
    void redisFailureFallsBackToALocalBucketAndIsCounted() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("valkey down"));
        var meters = new SimpleMeterRegistry();
        var limiter = new RedisTenantRateLimiter(template, meters, Duration.ofMillis(500), Duration.ofMinutes(1));

        // Still limited (burst 2), per pod: ingest is neither rejected wholesale nor unlimited.
        assertTrue(limiter.tryAcquire("acme", 1, 2));
        assertTrue(limiter.tryAcquire("acme", 1, 2));
        assertFalse(limiter.tryAcquire("acme", 1, 2));

        assertEquals(3.0, meters.counter("auditflow.tenant.ratelimit.fallback").count());
        // Redis is not asked again within the cooldown.
        verify(template, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void aHangingRedisCallDoesNotHoldTheRequest() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        CountDownLatch never = new CountDownLatch(1);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenAnswer(inv -> {
            never.await();
            return 1L;
        });
        var limiter = new RedisTenantRateLimiter(template, new SimpleMeterRegistry(),
                Duration.ofMillis(50), Duration.ofMinutes(1));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(limiter.tryAcquire("acme", 10, 10)));
        never.countDown();
    }

    @Test
    void redisIsUsedAgainAfterTheCooldown() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("valkey down"))
                .thenReturn(0L);
        var limiter = new RedisTenantRateLimiter(template, new SimpleMeterRegistry(),
                Duration.ofMillis(500), Duration.ZERO);

        assertTrue(limiter.tryAcquire("acme", 10, 10), "local bucket while Redis fails");
        assertFalse(limiter.tryAcquire("acme", 10, 10), "Redis decides again once it answers");
        assertFalse(limiter.tryAcquire("acme", 10, 10));
        verify(template, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void springBuildsTheLimiterWithItsDefaultTimeouts() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                // SpringApplication installs this conversion service (String -> Duration) at startup.
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
                .withPropertyValues("tenants.ratelimit.backend=redis")
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(RedisTenantRateLimiter.class)
                .run(context -> assertNotNull(context.getBean(TenantRateLimiter.class)));
    }

    @Test
    void nonPositiveRateSkipsRedisEntirely() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        var limiter = new RedisTenantRateLimiter(template);
        assertTrue(limiter.tryAcquire("acme", 0, 0));
        verifyNoInteractions(template);
    }
}
