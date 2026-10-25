package com.sis.shortener;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.sis.common.Base62;
import com.sis.common.IdSource;
import com.sis.metrics.AppMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * URL shortener: unique id -> base62 code, stored in MongoDB, hot codes cached in Redis.
 * Two resolve paths exist on purpose so we can compare them in the performance evaluation:
 *  - resolveCached():   Redis first, MongoDB on a miss (the proposed design)
 *  - resolveBaseline(): MongoDB only (the simple baseline)
 */
@Service
public class ShortenerService {
    private static final String URL_KEY = "sis:short:";
    private static final String CLICK_KEY = "sis:clicks:";
    private static final int MAX_ALIAS_SUFFIX = 1000;

    private final ShortUrlRepository repo;
    private final StringRedisTemplate redis;
    private final IdSource ids;
    private final AppMetrics metrics;
    private final String baseUrl;
    private final long cacheTtlSeconds;
    private final long idOffset;

    public ShortenerService(ShortUrlRepository repo, StringRedisTemplate redis, IdSource ids, AppMetrics metrics,
                            @Value("${sis.shortener.base-url}") String baseUrl,
                            @Value("${sis.shortener.cache-ttl-seconds}") long cacheTtlSeconds,
                            @Value("${sis.shortener.id-offset}") long idOffset) {
        this.repo = repo;
        this.redis = redis;
        this.ids = ids;
        this.metrics = metrics;
        this.baseUrl = baseUrl;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.idOffset = idOffset;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String shortLink(String code) {
        return baseUrl + "/s/" + code;
    }

    public ShortUrl create(String url, String alias) {
        validate(url);
        ShortUrl saved = null;
        if (alias != null && !alias.isBlank()) {
            if (!alias.matches("[A-Za-z0-9_-]{3,32}")) {
                throw new IllegalArgumentException("Alias must be 3-32 characters: letters, digits, _ or -");
            }
            // Alias taken? Fall back to alias.1, alias.2, ... instead of failing.
            for (int n = 0; n <= MAX_ALIAS_SUFFIX && saved == null; n++) {
                String candidate = n == 0 ? alias : alias + "." + n;
                try {
                    saved = repo.insert(new ShortUrl(candidate, url, Instant.now()));
                } catch (DuplicateKeyException ignored) {
                    // taken, try the next suffix
                }
            }
            if (saved == null) {
                throw new IllegalStateException("Alias '" + alias + "' is already taken and no free variant was found");
            }
        } else {
            for (int i = 0; i < 3 && saved == null; i++) {
                String code = Base62.encode(idOffset + ids.nextId());
                try {
                    saved = repo.insert(new ShortUrl(code, url, Instant.now()));
                } catch (DuplicateKeyException ignored) {
                    // an alias already used this code; take the next id
                }
            }
            if (saved == null) {
                throw new IllegalStateException("Could not allocate a short code");
            }
        }
        redis.opsForValue().set(URL_KEY + saved.code(), url, Duration.ofSeconds(cacheTtlSeconds));
        return saved;
    }

    /** Proposed design: Redis cache-aside. */
    public Optional<String> resolveCached(String code) {
        long t0 = System.nanoTime();
        try {
            String cached = redis.opsForValue().get(URL_KEY + code);
            if (cached != null) {
                metrics.cacheHits.increment();
                redis.opsForValue().increment(CLICK_KEY + code);
                return Optional.of(cached);
            }
            metrics.cacheMisses.increment();
            Optional<String> fromDb = repo.findById(code).map(ShortUrl::url);
            fromDb.ifPresent(u -> {
                redis.opsForValue().set(URL_KEY + code, u, Duration.ofSeconds(cacheTtlSeconds));
                redis.opsForValue().increment(CLICK_KEY + code);
            });
            return fromDb;
        } finally {
            metrics.redirectCached.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    /** Baseline: every redirect reads MongoDB, no cache. */
    public Optional<String> resolveBaseline(String code) {
        long t0 = System.nanoTime();
        try {
            return repo.findById(code).map(ShortUrl::url);
        } finally {
            metrics.redirectBaseline.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        }
    }

    public long clicks(String code) {
        String v = redis.opsForValue().get(CLICK_KEY + code);
        return v == null ? 0L : Long.parseLong(v);
    }

    private static void validate(String url) {
        try {
            URI u = new URI(url);
            String scheme = u.getScheme();
            if (u.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("URL must be an absolute http(s) URL");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL");
        }
    }
}
