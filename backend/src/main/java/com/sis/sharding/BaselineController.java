package com.sis.sharding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Baseline (one plain database, no routing) vs the sharded design, at three workload levels. */
@RestController
@RequestMapping("/api/shards/baseline")
public class BaselineController {
    private static final String DEMO = "shard_demo";
    private static final int[] LEVELS = {1, 8, 32};

    private final ShardRouter router;
    private final MongoTemplate single;

    public BaselineController(ShardRouter router, MongoClient client, MongoConverter converter) {
        this.router = router;
        this.single = new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, "baseline_single"), converter);
    }

    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestParam(defaultValue = "10000") int requests,
                                       @RequestParam(defaultValue = "1000") int keys) {
        int k = clamp(keys, 1, 100_000);
        int n = clamp(requests, 32, 500_000);
        ensureBaselineData(k);

        Function<String, Document> baselineLookup = key -> single.findById(key, Document.class, DEMO);
        Function<String, Document> shardedLookup = key -> router.forKey(key).findById(key, Document.class, DEMO);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int threads : LEVELS) {
            run(baselineLookup, n / 4, k, threads); // warm-up, discarded
            Map<String, Object> base = run(baselineLookup, n, k, threads);

            run(shardedLookup, n / 4, k, threads); // warm-up, discarded
            router.resetLoad();
            Map<String, Object> sharded = run(shardedLookup, n, k, threads);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("threads", threads);
            row.put("baselineSingleDb", base);
            row.put("shardedDesign", sharded);
            row.put("shardedRequestLoad", router.loadSnapshot());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestsPerRun", n);
        out.put("keys", k);
        out.put("results", rows);
        return out;
    }

    private void ensureBaselineData(int keys) {
        if (single.count(new Query(), DEMO) >= keys) {
            return;
        }
        for (int i = 0; i < keys; i++) {
            String id = "S" + (1000 + i);
            single.save(new Document("_id", id).append("name", "Student " + id), DEMO);
        }
    }

    private Map<String, Object> run(Function<String, Document> lookup, int requests, int keys, int threads) {
        int per = Math.max(requests / threads, 1);
        LongAdder nanos = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder missing = new LongAdder();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);

        long t0 = System.nanoTime();
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            pool.execute(() -> {
                Random rnd = new Random(7 + seed);
                try {
                    for (int j = 0; j < per; j++) {
                        String key = "S" + (1000 + rnd.nextInt(keys));
                        long s = System.nanoTime();
                        try {
                            Document d = lookup.apply(key);
                            nanos.add(System.nanoTime() - s);
                            if (d == null) {
                                missing.increment();
                            }
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

        long total = (long) per * threads;
        long ok = total - errors.sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requests", total);
        out.put("throughputReqPerSec", Math.round(total / secs));
        out.put("avgLatencyMicros", ok == 0 ? 0 : Math.round(nanos.sum() / 10.0 / ok) / 100.0);
        out.put("errors", errors.sum());
        out.put("missing", missing.sum());
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.min(Math.max(v, lo), hi);
    }
}