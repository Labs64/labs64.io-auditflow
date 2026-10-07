package io.labs64.audit.tenant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster-wide per-tenant token bucket, reusing the Redis already present for dedup. The bucket
 * refill/consume is a single atomic Lua script so concurrent replicas cannot over-admit.
 * Explicit opt-in only ({@code tenants.ratelimit.backend=redis}, set by the Helm chart for
 * multi-replica correctness) — never matchIfMissing, so Core keeps booting without Redis.
 *
 * <p>Ingest does not depend on Redis being reachable. The Redis call has its own short time budget
 * (the client's command timeout is far longer and would hold the request), and when it fails or
 * runs out of time this pod limits the tenant with a local bucket instead: the quota then holds per
 * pod rather than cluster-wide, and the gateway's ceiling still applies. Redis is tried again by
 * one request per cooldown period, so an outage costs one waiting request per period, not all of
 * them. Each locally decided request is counted in {@code auditflow.tenant.ratelimit.fallback}.</p>
 */
@Component
@ConditionalOnProperty(name = "tenants.ratelimit.backend", havingValue = "redis")
public class RedisTenantRateLimiter implements TenantRateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RedisTenantRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:tenant:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> script;
    private final Duration timeout;
    private final Duration cooldown;
    private final Counter fallbackCounter;
    private final InMemoryTenantRateLimiter local = new InMemoryTenantRateLimiter();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    /** 0 while Redis answers; otherwise the time (epoch millis) of the next attempt to reach it. */
    private final AtomicLong retryAt = new AtomicLong();

    public RedisTenantRateLimiter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, new SimpleMeterRegistry(), Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    @Autowired
    public RedisTenantRateLimiter(StringRedisTemplate redisTemplate, MeterRegistry meterRegistry,
            @Value("${tenants.ratelimit.redis.timeout:PT0.5S}") Duration timeout,
            @Value("${tenants.ratelimit.redis.fallback-cooldown:PT5S}") Duration cooldown) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(
                readScript("tenant-token-bucket.lua"), Long.class);
        this.timeout = timeout;
        this.cooldown = cooldown;
        this.fallbackCounter = meterRegistry.counter("auditflow.tenant.ratelimit.fallback");
    }

    @Override
    public boolean tryAcquire(String tenantId, int ratePerSec, int burst) {
        if (ratePerSec <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long next = retryAt.get();
        // Redis is failing: decide locally, except for the one request that wins the next attempt.
        if (next != 0 && (now < next || !retryAt.compareAndSet(next, now + cooldown.toMillis()))) {
            return acquireLocally(tenantId, ratePerSec, burst);
        }
        Future<Long> call = executor.submit(() -> redisTemplate.execute(
                script,
                List.of(KEY_PREFIX + tenantId),
                String.valueOf(ratePerSec),
                String.valueOf(burst),
                String.valueOf(System.currentTimeMillis())));
        try {
            Long allowed = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (next != 0) {
                retryAt.set(0);
                logger.info("Tenant rate limiter: Redis is reachable again, back to the cluster-wide quota");
            }
            return allowed != null && allowed == 1L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            return acquireLocally(tenantId, ratePerSec, burst);
        } catch (ExecutionException | TimeoutException e) {
            call.cancel(true);
            if (retryAt.getAndSet(now + cooldown.toMillis()) == 0) {
                logger.warn("Tenant rate limiter: Redis is unavailable ({}); limiting per pod until it answers again",
                        e instanceof TimeoutException ? "no answer within " + timeout : String.valueOf(e.getCause()));
            }
            return acquireLocally(tenantId, ratePerSec, burst);
        }
    }

    private boolean acquireLocally(String tenantId, int ratePerSec, int burst) {
        fallbackCounter.increment();
        return local.tryAcquire(tenantId, ratePerSec, burst);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static String readScript(String resource) {
        try {
            return new String(new ClassPathResource(resource).getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load Lua script " + resource, e);
        }
    }
}
