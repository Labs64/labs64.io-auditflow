package io.labs64.audit.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Cluster-wide per-tenant token bucket, reusing the Redis already present for dedup. The bucket
 * refill/consume is a single atomic Lua script so concurrent replicas cannot over-admit.
 * Explicit opt-in only ({@code tenants.ratelimit.backend=redis}, set by the Helm chart for
 * multi-replica correctness) — never matchIfMissing, so Core keeps booting without Redis.
 */
@Component
@ConditionalOnProperty(name = "tenants.ratelimit.backend", havingValue = "redis")
public class RedisTenantRateLimiter implements TenantRateLimiter {

    private static final String KEY_PREFIX = "ratelimit:tenant:";
    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> script;

    public RedisTenantRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(
                readScript("tenant-token-bucket.lua"), Long.class);
    }

    @Override
    public boolean tryAcquire(String tenantId, int ratePerSec, int burst) {
        if (ratePerSec <= 0) {
            return true;
        }
        Long allowed = redisTemplate.execute(
                script,
                List.of(KEY_PREFIX + tenantId),
                String.valueOf(ratePerSec),
                String.valueOf(burst),
                String.valueOf(System.currentTimeMillis()));
        return allowed != null && allowed == 1L;
    }

    private static String readScript(String resource) {
        try {
            return new String(new ClassPathResource(resource).getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load Lua script " + resource, e);
        }
    }
}
