package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

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
    private static final String PROCESSING = "processing";
    private static final String DONE = "done";

    /** Atomic: leave a done claim alone, otherwise (in progress or expired) claim it again. */
    private static final RedisScript<Long> TAKE_OVER = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return 0 end "
                    + "redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) "
                    + "return 1",
            Long.class);

    /** SET every key to ARGV[1] with a TTL of ARGV[2] ms. */
    private static final RedisScript<Long> MARK_ALL_DONE = new DefaultRedisScript<>(
            "for _, k in ipairs(KEYS) do redis.call('SET', k, ARGV[1], 'PX', ARGV[2]) end "
                    + "return #KEYS",
            Long.class);

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
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key(eventId), PROCESSING, claimTtl);
        boolean result = Boolean.TRUE.equals(acquired);
        if (!result) {
            logger.debug("Duplicate or in-flight eventId '{}', claim refused", eventId);
        }
        return result;
    }

    @Override
    public boolean takeOver(String eventId) {
        Long taken = redisTemplate.execute(TAKE_OVER, List.of(key(eventId)),
                DONE, PROCESSING, String.valueOf(claimTtl.toMillis()));
        return taken != null && taken == 1L;
    }

    @Override
    public void markProcessed(String eventId) {
        redisTemplate.opsForValue().set(key(eventId), DONE, doneTtl);
    }

    @Override
    public void release(String eventId) {
        redisTemplate.delete(key(eventId));
    }

    @Override
    public void markPipelineDone(String eventId, String pipelineName) {
        redisTemplate.opsForValue().set(pipelineKey(eventId, pipelineName), DONE, doneTtl);
    }

    @Override
    public boolean isPipelineDone(String eventId, String pipelineName) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(pipelineKey(eventId, pipelineName)));
    }

    /**
     * One MGET for the whole batch instead of a call per delivery. On the shared connection: Spring's
     * pipelining would borrow a dedicated one, and without a pool that is a new connection (and TLS
     * handshake) per call.
     */
    @Override
    public List<Boolean> arePipelinesDone(List<PipelineDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return List.of();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(pipelineKeys(deliveries));
        if (values == null || values.size() != deliveries.size()) {
            return IdempotencyService.super.arePipelinesDone(deliveries);
        }
        return values.stream().map(v -> v != null).toList();
    }

    /**
     * One script call sets every marker with the done TTL. A Redis that refuses it (a Redis Cluster
     * rejects keys of several hash slots in one script) gets one SET per marker instead.
     */
    @Override
    public void markPipelinesDone(List<PipelineDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        List<String> keys = pipelineKeys(deliveries);
        try {
            redisTemplate.execute(MARK_ALL_DONE, keys, DONE, String.valueOf(doneTtl.toMillis()));
        } catch (RuntimeException e) {
            logger.debug("Marking {} pipeline(s) done in one script failed ({}); one SET each", keys.size(), e.toString());
            keys.forEach(k -> redisTemplate.opsForValue().set(k, DONE, doneTtl));
        }
    }

    private List<String> pipelineKeys(List<PipelineDelivery> deliveries) {
        return deliveries.stream().map(d -> pipelineKey(d.eventId(), d.pipelineName())).toList();
    }

    private String key(String eventId) {
        return KEY_PREFIX + eventId;
    }

    private String pipelineKey(String eventId, String pipelineName) {
        return KEY_PREFIX + eventId + ":pipe:" + pipelineName;
    }
}
