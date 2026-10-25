package com.sis.kv;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Key-value store on Redis with per-key expiry (TTL).
 * Uses: sessions/tokens (put/get with TTL) and cache-aside for hot lookups (cached()).
 * Every lookup is timed, split into hits and misses, for the performance report.
 */
@Component
public class KvStore {
    private static final String PREFIX = "sis:kv:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    public final Timer hitTimer;
    public final Timer missTimer;
    public final Timer writeTimer;
    public final Counter hits;
    public final Counter misses;

    public KvStore(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry r) {
        this.redis = redis;
        this.mapper = mapper;
        this.hitTimer = Timer.builder("sis.kv.lookup").tag("result", "hit")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        this.missTimer = Timer.builder("sis.kv.lookup").tag("result", "miss")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        this.writeTimer = Timer.builder("sis.kv.write")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        this.hits = Counter.builder("sis.kv.cache").tag("result", "hit").register(r);
        this.misses = Counter.builder("sis.kv.cache").tag("result", "miss").register(r);
    }

    public void put(String key, String value, Duration ttl) {
        long t0 = System.nanoTime();
        redis.opsForValue().set(PREFIX + key, value, ttl);
        writeTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
    }

    public Optional<String> get(String key) {
        long t0 = System.nanoTime();
        String v = redis.opsForValue().get(PREFIX + key);
        long dt = System.nanoTime() - t0;
        if (v != null) {
            hits.increment();
            hitTimer.record(dt, TimeUnit.NANOSECONDS);
        } else {
            misses.increment();
            missTimer.record(dt, TimeUnit.NANOSECONDS);
        }
        return Optional.ofNullable(v);
    }

    public boolean delete(String key) {
        return Boolean.TRUE.equals(redis.delete(PREFIX + key));
    }

    /** Cache-aside: return the cached value, or load it (e.g. from a shard), cache it, and return it. */
    public <T> T cached(String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        Optional<String> json = get(key);
        if (json.isPresent()) {
            try {
                return mapper.readValue(json.get(), type);
            } catch (JsonProcessingException e) {
                delete(key); // corrupt entry: fall through and reload
            }
        }
        T value = loader.get();
        if (value != null) {
            try {
                put(key, mapper.writeValueAsString(value), ttl);
            } catch (JsonProcessingException e) {
                // could not serialise: just skip caching
            }
        }
        return value;
    }
}