package com.sis.kv;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sis.sharding.ShardRouter;
import org.bson.Document;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kv")
public class KvController {
    private static final String DEMO = "shard_demo";
    private static final Duration DEMO_TTL = Duration.ofMinutes(10);

    public record StudentView(String id, String name, String shard) {
    }

    private final KvStore kv;
    private final ShardRouter router;

    public KvController(KvStore kv, ShardRouter router) {
        this.kv = kv;
        this.router = router;
    }

    // ---- plain key-value store (sessions, tokens, anything with an expiry) ----

    @PostMapping
    public Map<String, Object> put(@RequestParam String key, @RequestParam String value,
                                   @RequestParam(defaultValue = "3600") long ttlSeconds) {
        kv.put(key, value, Duration.ofSeconds(ttlSeconds));
        return Map.of("stored", true, "key", key, "ttlSeconds", ttlSeconds);
    }

    @GetMapping
    public Map<String, Object> get(@RequestParam String key) {
        Map<String, Object> out = new LinkedHashMap<>();
        var v = kv.get(key);
        out.put("key", key);
        out.put("found", v.isPresent());
        out.put("value", v.orElse(null));
        return out;
    }

    @DeleteMapping
    public Map<String, Object> delete(@RequestParam String key) {
        return Map.of("deleted", kv.delete(key));
    }

    // ---- hot lookup in front of the shards (cache-aside) ----

    private StudentView loadFromShard(String id) {
        Document d = router.forKey(id).findById(id, Document.class, DEMO);
        return d == null ? null : new StudentView(id, d.getString("name"), router.shardFor(id));
    }

    @GetMapping("/demo/student/{id}")
    public Map<String, Object> student(@PathVariable String id) {
        AtomicBoolean fromDb = new AtomicBoolean(false);
        long t0 = System.nanoTime();
        StudentView s = kv.cached("student:" + id, DEMO_TTL, StudentView.class, () -> {
            fromDb.set(true);
            return loadFromShard(id);
        });
        long micros = (System.nanoTime() - t0) / 1000;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("student", s);
        out.put("source", s == null ? "not found" : fromDb.get() ? "shard (db)" : "cache");
        out.put("latencyMicros", micros);
        return out;
    }

    /** Compares lookups straight from the shards against lookups through the cache. */
    @PostMapping("/demo/benchmark")
    public Map<String, Object> benchmark(@RequestParam(defaultValue = "2000") int lookups,
                                         @RequestParam(defaultValue = "50") int hotKeys) {
        int n = Math.min(Math.max(lookups, 1), 100_000);
        int k = Math.min(Math.max(hotKeys, 1), 1000);
        if (loadFromShard("S1000") == null) {
            return Map.of("error", "No demo data. Run POST /api/shards/demo/seed?count=1000 first.");
        }
        for (int i = 0; i < k; i++) {
            kv.delete("student:S" + (1000 + i)); // start with a cold cache
        }

        Random rnd = new Random(42);
        int[] picks = new int[n];
        for (int i = 0; i < n; i++) {
            picks[i] = 1000 + rnd.nextInt(k);
        }

        long t0 = System.nanoTime();
        for (int p : picks) {
            loadFromShard("S" + p);
        }
        double dbMicros = (System.nanoTime() - t0) / 1000.0 / n;

        long h0 = (long) kv.hits.count();
        long m0 = (long) kv.misses.count();
        long t1 = System.nanoTime();
        for (int p : picks) {
            String id = "S" + p;
            kv.cached("student:" + id, DEMO_TTL, StudentView.class, () -> loadFromShard(id));
        }
        double cacheMicros = (System.nanoTime() - t1) / 1000.0 / n;
        long hits = (long) kv.hits.count() - h0;
        long misses = (long) kv.misses.count() - m0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lookups", n);
        out.put("hotKeys", k);
        out.put("avgMicrosDirectFromShards", Math.round(dbMicros * 10) / 10.0);
        out.put("avgMicrosWithCache", Math.round(cacheMicros * 10) / 10.0);
        out.put("speedup", Math.round(dbMicros / cacheMicros * 10) / 10.0);
        out.put("cacheHits", hits);
        out.put("cacheMisses", misses);
        out.put("hitRatio", Math.round(100.0 * hits / Math.max(1, hits + misses)) / 100.0);
        return out;
    }
}