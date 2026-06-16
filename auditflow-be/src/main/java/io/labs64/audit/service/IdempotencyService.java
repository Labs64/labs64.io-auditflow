package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Idempotency / dedup guard backed by Redis, keyed by audit {@code eventId}.
 *
 * <p>Claim-on-receive: {@link #claim(String)} atomically sets a marker only if absent. A
 * duplicate (or an in-flight redelivery) fails to claim and is dropped by the caller. After
 * successful processing the caller calls {@link #markProcessed(String)} to extend the marker
 * TTL; on an event-level failure the caller calls {@link #release(String)} so an at-least-once
 * redelivery can retry.</p>
 */
@Service
public class IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String KEY_PREFIX = "evt:";

    private final StringRedisTemplate redisTemplate;
    private final Duration claimTtl;
    private final Duration doneTtl;

    public IdempotencyService(
            StringRedisTemplate redisTemplate,
            @Value("${auditflow.idempotency.claim-ttl:PT5M}") Duration claimTtl,
            @Value("${auditflow.idempotency.done-ttl:PT24H}") Duration doneTtl) {
        this.redisTemplate = redisTemplate;
        this.claimTtl = claimTtl;
        this.doneTtl = doneTtl;
    }

    /** @return true if this caller acquired the claim (first time seeing this eventId). */
    public boolean claim(String eventId) {
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(eventId), "processing", claimTtl);
        boolean result = Boolean.TRUE.equals(acquired);
        if (!result) {
            logger.debug("Duplicate or in-flight eventId '{}', claim refused", eventId);
        }
        return result;
    }

    /** Mark an event as fully processed, extending the marker so late duplicates are still dropped. */
    public void markProcessed(String eventId) {
        redisTemplate.opsForValue().set(key(eventId), "done", doneTtl);
    }

    /** Release a claim so an at-least-once redelivery can retry this event. */
    public void release(String eventId) {
        redisTemplate.delete(key(eventId));
    }

    private String key(String eventId) {
        return KEY_PREFIX + eventId;
    }
}
