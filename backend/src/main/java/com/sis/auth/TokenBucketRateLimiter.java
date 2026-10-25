package com.sis.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Distributed token-bucket limiter. The refill-and-take logic lives in {@code scripts/token_bucket.lua} and
 * runs atomically inside Redis, so every app server behind the load balancer shares one bucket per client.
 *
 * <p>Each client owns a bucket of {@code capacity} tokens that refills at {@code refillPerSecond}. A request
 * costs one token: a client can burst up to {@code capacity} requests, then is held to the refill rate.
 * Unlike the old fixed window there is no counter reset, so no double burst at a window edge.
 */
@Component
public class TokenBucketRateLimiter {
    /** Result of one acquire attempt. */
    public record Decision(boolean allowed, long remaining, long retryAfterMs) {
        /** Whole seconds for a Retry-After header (at least 1). */
        public long retryAfterSeconds() {
            return Math.max(1, (retryAfterMs + 999) / 1000);
        }
    }

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        SCRIPT.setResultType(List.class);
    }

    private static final String KEY_PREFIX = "sis:bucket:";

    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;

    public TokenBucketRateLimiter(StringRedisTemplate redis, MeterRegistry metrics) {
        this.redis = redis;
        this.metrics = metrics;
    }

    /**
     * Takes {@code cost} tokens from the bucket of {@code clientKey} in {@code scope}. Scopes never share
     * tokens, and each call supplies its own capacity and refill rate so every scope keeps its own policy.
     */
    public Decision acquire(String scope, String clientKey, int capacity, double refillPerSecond, int cost) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be greater than 0");
        }
        if (cost < 1 || cost > capacity) {
            throw new IllegalArgumentException("cost must be between 1 and " + capacity);
        }
        List<?> raw = redis.execute(SCRIPT, List.of(KEY_PREFIX + scope + ":" + clientKey),
                Integer.toString(capacity), Double.toString(refillPerSecond), Integer.toString(cost));
        if (raw == null || raw.size() < 3) {
            throw new IllegalStateException("Rate limiter unavailable");
        }
        Decision decision = new Decision(((Number) raw.get(0)).longValue() == 1L,
                ((Number) raw.get(1)).longValue(), ((Number) raw.get(2)).longValue());
        metrics.counter("sis.ratelimit.requests", "result", decision.allowed() ? "allowed" : "rejected",
                "scope", scope).increment();
        return decision;
    }
}
