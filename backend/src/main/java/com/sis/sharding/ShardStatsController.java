package com.sis.sharding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import com.sis.schema.SchemaCatalog;
import com.sis.kv.KvStore;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.bson.Document;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Metrics for the data layer: shard distribution, request load per shard, KV lookup latency. */
@RestController
@RequestMapping("/api/shards/stats")
public class ShardStatsController {
    private static final String DEMO = "shard_demo";
    private static final List<String> COLLECTIONS = List.copyOf(SchemaCatalog.KEY_FIELDS.keySet());
    private final ShardRouter router;
    private final KvStore kv;

    public ShardStatsController(ShardRouter router, KvStore kv) {
        this.router = router;
        this.kv = kv;
    }

    @GetMapping
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shards", router.shardNames());

        Map<String, Object> data = new LinkedHashMap<>();
        for (String c : COLLECTIONS) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : router.shardNames()) {
                counts.put(s, router.peek(s).count(new Query(), c));
            }
            data.put(c, balance(counts));
        }
        out.put("dataDistribution", data);
        out.put("requestLoad", balance(router.loadSnapshot()));

        Map<String, Object> kvm = new LinkedHashMap<>();
        kvm.put("hit", timerStats(kv.hitTimer));
        kvm.put("miss", timerStats(kv.missTimer));
        kvm.put("write", timerStats(kv.writeTimer));
        out.put("kvLookupLatency", kvm);
        return out;
    }

    /** Resets the per-shard request counters (do this between workload levels). */
    @PostMapping("/reset-load")
    public Map<String, Object> resetLoad() {
        router.resetLoad();
        return Map.of("reset", true);
    }

    /** Sends many lookups through the router from several threads and reports the results. */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestParam(defaultValue = "10000") int requests,
                                        @RequestParam(defaultValue = "1000") int keys,
                                        @RequestParam(defaultValue = "8") int threads) {
        int t = Math.min(Math.max(threads, 1), 256);
        int k = Math.min(Math.max(keys, 1), 100_000);
        int per = Math.min(Math.max(requests, t), 500_000) / t;

        router.resetLoad();
        LongAdder nanos = new LongAdder();
          LongAdder errors = new LongAdder();
          LongAdder missing = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(t);
        CountDownLatch done = new CountDownLatch(t);

        long t0 = System.nanoTime();
        for (int i = 0; i < t; i++) {
            final int seed = i;
            pool.execute(() -> {
                Random rnd = new Random(7 + seed);
                try {
                    for (int j = 0; j < per; j++) {
                        String key = "S" + (1000 + rnd.nextInt(k));
                        long s = System.nanoTime();
                        try {
                              Document found = router.forKey(key).findById(key, Document.class, DEMO);
                              if (found == null) {
                                  missing.increment();
                              }
                            nanos.add(System.nanoTime() - s);
                        } catch (Exception e) {
                            errors.increment();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();
        double secs = (System.nanoTime() - t0) / 1e9;

        long total = (long) per * t;
        long ok = total - errors.sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threads", t);
        out.put("requests", total);
        out.put("errors", errors.sum());
        out.put("errorRatePercent", round(100.0 * errors.sum() / total));
          out.put("missing", missing.sum());
          out.put("seconds", round(secs));
        out.put("throughputReqPerSec", Math.round(total / secs));
        out.put("avgLatencyMicros", ok == 0 ? 0 : round(nanos.sum() / 1000.0 / ok));
        out.put("requestLoad", balance(router.loadSnapshot()));
        return out;
    }

    private static Map<String, Object> balance(Map<String, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        int n = Math.max(counts.size(), 1);
        double mean = total / (double) n;
        double sd = Math.sqrt(counts.values().stream()
                .mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0));
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long min = counts.values().stream().mapToLong(Long::longValue).min().orElse(0);
        Map<String, Object> pct = new LinkedHashMap<>();
        counts.forEach((s, v) -> pct.put(s, round(total == 0 ? 0 : 100.0 * v / total)));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("perShard", counts);
        out.put("percent", pct);
        out.put("stdDev", round(sd));
        out.put("cvPercent", round(mean == 0 ? 0 : sd / mean * 100));
        out.put("maxMinRatio", min == 0 ? "n/a" : round(max / (double) min));
        return out;
    }

    private static Map<String, Object> timerStats(Timer t) {
        HistogramSnapshot snap = t.takeSnapshot();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("count", t.count());
        s.put("meanMicros", round(t.mean(TimeUnit.MICROSECONDS)));
        s.put("maxMicros", round(t.max(TimeUnit.MICROSECONDS)));
        for (ValueAtPercentile p : snap.percentileValues()) {
            s.put("p" + Math.round(p.percentile() * 100) + "Micros", round(p.value(TimeUnit.MICROSECONDS)));
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}