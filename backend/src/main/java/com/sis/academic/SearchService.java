package com.sis.academic;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sis.schema.Course;
import com.sis.schema.Student;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Autocomplete. The tries are built from a scan of every shard at startup and refreshed periodically, so a
 * keystroke never touches the database. If a shard is down the refresh fails and the old index keeps serving,
 * so search still works during the failure demo.
 *
 * <p>Results are also cached in Redis (cache-aside): a lookup checks Redis first, and on a miss it searches the
 * trie and writes the result back with a TTL. The cache is shared by every app instance. Each successful index
 * rebuild bumps a version number that is part of every cache key, so results from the previous index are never
 * served again (the old keys simply expire). If Redis is unavailable the search falls back to the trie.
 */
@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    public static final List<String> TYPES = List.of("student", "faculty", "admin", "course");

    private static final String CACHE_PREFIX = "sis:autocomplete:";
    private static final String VERSION_KEY = CACHE_PREFIX + "version";
    private static final int MAX_CACHED_QUERY_LENGTH = 100;
    private static final TypeReference<List<SearchIndex.Result>> RESULT_LIST = new TypeReference<>() {
    };

    private final ShardScan scan;
    private final AcademicMetrics metrics;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration cacheTtl;
    private volatile Map<String, SearchIndex> indexes = Map.of();
    private volatile String lastError;

    public SearchService(ShardScan scan, AcademicMetrics metrics, StringRedisTemplate redis, ObjectMapper json,
                         @Value("${sis.academic.search-cache-ttl-seconds:120}") long cacheTtlSeconds) {
        this.scan = scan;
        this.metrics = metrics;
        this.redis = redis;
        this.json = json;
        this.cacheTtl = Duration.ofSeconds(Math.max(1, cacheTtlSeconds));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${sis.academic.search-refresh-ms:60000}",
            initialDelayString = "${sis.academic.search-refresh-ms:60000}")
    public void scheduledRefresh() {
        refresh();
    }

    /** Rebuild from the shards. Returns false (and keeps the old index) when a shard cannot be read. */
    public synchronized boolean refresh() {
        try {
            Map<String, SearchIndex> next = new HashMap<>();
            for (String t : TYPES) {
                next.put(t, new SearchIndex());
            }
            Query people = new Query();
            people.fields().include("name", "department", "year", "role");
            Map<String, String> facultyNames = new HashMap<>();
            for (Student s : scan.findAll(people, Student.class)) {
                String role = s.role() == null ? "STUDENT" : s.role();
                String type = "FACULTY".equals(role) ? "faculty" : "ADMIN".equals(role) ? "admin" : "student";
                String sub = "STUDENT".equals(role) ? s.department() + ", year " + s.year() : s.department();
                next.get(type).add(s.id(), s.name(), sub == null ? "" : sub, s.department());
                if ("faculty".equals(type)) {
                    facultyNames.put(s.id(), s.name());
                }
            }
            for (Course c : scan.findAll(new Query(), Course.class)) {
                String teacher = facultyNames.getOrDefault(c.instructor(), c.instructor());
                next.get("course").add(c.id(), c.title(), c.code() + ", " + c.credits() + " credits, " + teacher,
                        c.code() + " " + c.department() + " " + teacher);
            }
            indexes = next;
            invalidateCache();   // after the swap, so nothing built from the old index is stored under the new version
            lastError = null;
            log.info("Search index rebuilt: {}", sizes());
            return true;
        } catch (RuntimeException e) {
            lastError = e.getMessage();
            log.warn("Search index refresh failed, keeping the previous one: {}", e.getMessage());
            return false;
        }
    }

    public List<SearchIndex.Result> search(String type, String query, int limit) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be one of " + TYPES);
        }
        long t0 = System.nanoTime();
        String key = cacheKey(type, query, limit);
        List<SearchIndex.Result> out = key == null ? null : cacheGet(key);
        if (out == null) {
            SearchIndex idx = indexes.get(type);
            out = idx == null ? List.of() : idx.search(query, limit);
            if (key != null && idx != null) {
                cachePut(key, out);
            }
        }
        metrics.recordSearch(type, System.nanoTime() - t0);
        return out;
    }

    /** Search without recording metrics (used to order the course list). */
    public List<String> rankedIds(String type, String query, int limit) {
        SearchIndex idx = indexes.get(type);
        return idx == null ? List.of() : idx.search(query, limit).stream().map(SearchIndex.Result::id).toList();
    }

    public Map<String, Object> sizes() {
        Map<String, Object> out = new LinkedHashMap<>();
        indexes.forEach((k, v) -> out.put(k, Map.of("items", v.size(), "tokens", v.vocabularySize())));
        if (lastError != null) {
            out.put("lastRefreshError", lastError);
        }
        return out;
    }

    public Map<String, Object> benchmark(int n) {
        Map<String, Object> result = SearchBenchmark.run(indexes, n, 7);
        metrics.setBenchmark(result);
        return result;
    }

    // ---------------------------------------------------------------- Redis cache (cache-aside)

    /** Key: sis:autocomplete:{index version}:{type}:{limit}:{query}. Null when the query is not worth caching. */
    private String cacheKey(String type, String query, int limit) {
        // Same normalisation SearchIndex applies, so two queries share a key only if they give the same result.
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || q.length() > MAX_CACHED_QUERY_LENGTH) {
            return null;
        }
        try {
            String version = redis.opsForValue().get(VERSION_KEY);
            return CACHE_PREFIX + (version == null ? "0" : version) + ":" + type + ":" + limit + ":" + q;
        } catch (RuntimeException e) {
            log.debug("Autocomplete cache unavailable, searching the index directly: {}", e.getMessage());
            return null;
        }
    }

    private List<SearchIndex.Result> cacheGet(String key) {
        try {
            String cached = redis.opsForValue().get(key);
            return cached == null ? null : json.readValue(cached, RESULT_LIST);
        } catch (Exception e) {
            log.debug("Autocomplete cache read failed for {}: {}", key, e.getMessage());
            return null;
        }
    }

    private void cachePut(String key, List<SearchIndex.Result> results) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(results), cacheTtl);
        } catch (Exception e) {
            log.debug("Autocomplete cache write failed for {}: {}", key, e.getMessage());
        }
    }

    /** New index, new version: every instance stops reading the old entries, which then expire by TTL. */
    private void invalidateCache() {
        try {
            redis.opsForValue().increment(VERSION_KEY);
        } catch (RuntimeException e) {
            log.warn("Could not bump the autocomplete cache version, entries will expire by TTL: {}", e.getMessage());
        }
    }
}
