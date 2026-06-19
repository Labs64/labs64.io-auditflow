package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed {@link IdempotencyService} — the default store for the full stack.
 *
 * <p>Active when {@code auditflow.idempotency.store} is {@code redis} or unset, so existing
 * deployments keep their behaviour with no config change.</p>
 */
@Service
@ConditionalOnProperty(name = "auditflow.idempotency.store", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotencyService implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(RedisIdempotencyService.class);
    private static final String KEY_PREFIX = "evt:";

    private final StringRedisTemplate redisTemplate;
    private final Duration claimTtl;
    private final Duration doneTtl;

    public RedisIdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${auditflow.idempotency.claim-ttl:PT5M}") Duration claimTtl,
            @Value("${auditflow.idempotency.done-ttl:PT24H}") Duration doneTtl) {
        this.redisTemplate = redisTemplate;
        this.claimTtl = claimTtl;
        this.doneTtl = doneTtl;
        logger.info("Idempotency store: redis (claim-ttl={}, done-ttl={})", claimTtl, doneTtl);
    }

    @Override
    public boolean claim(String eventId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(eventId), "processing", claimTtl);
        boolean result = Boolean.TRUE.equals(acquired);
        if (!result) {
            logger.debug("Duplicate or in-flight eventId '{}', claim refused", eventId);
        }
        return result;
    }

    @Override
    public void markProcessed(String eventId) {
        redisTemplate.opsForValue().set(key(eventId), "done", doneTtl);
    }

    @Override
    public void release(String eventId) {
        redisTemplate.delete(key(eventId));
    }

    @Override
    public void markPipelineDone(String eventId, String pipelineName) {
        redisTemplate.opsForValue().set(pipelineKey(eventId, pipelineName), "done", doneTtl);
    }

    @Override
    public boolean isPipelineDone(String eventId, String pipelineName) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(pipelineKey(eventId, pipelineName)));
    }

    private String key(String eventId) {
        return KEY_PREFIX + eventId;
    }

    private String pipelineKey(String eventId, String pipelineName) {
        return KEY_PREFIX + eventId + ":pipe:" + pipelineName;
    }
}
