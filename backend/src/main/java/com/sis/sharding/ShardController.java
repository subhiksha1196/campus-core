package com.sis.sharding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.bson.Document;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shards")
public class ShardController {
    private static final String DEMO = "shard_demo";
    private final ShardRouter router;

    public ShardController(ShardRouter router) {
        this.router = router;
    }

    @GetMapping
    public Map<String, Object> shards() {
        return Map.of("shards", router.shardNames());
    }

    /** Which shard would this key go to? */
    @GetMapping("/locate")
    public Map<String, String> locate(@RequestParam String key) {
        return Map.of("key", key, "shard", router.shardFor(key));
    }

    /** Inserts fake students S1000.. through the router and reports how many landed on each shard. */
    @PostMapping("/demo/seed")
    public Map<String, Object> seed(@RequestParam(defaultValue = "1000") int count) {
        int n = Math.min(Math.max(count, 1), 100_000);
        for (int i = 0; i < n; i++) {
            String key = "S" + (1000 + i);
            router.forKey(key).save(new Document("_id", key).append("name", "Student " + key), DEMO);
        }
        return distribution();
    }

    @GetMapping("/demo/distribution")
    public Map<String, Object> distribution() {
        Map<String, Long> counts = new TreeMap<>();
        long total = 0;
        for (String s : router.shardNames()) {
            long c = router.peek(s).count(new Query(), DEMO);
            counts.put(s, c);
            total += c;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("perShard", counts);
        return out;
    }

    @DeleteMapping("/demo")
    public Map<String, Object> clear() {
        router.shardNames().forEach(s -> router.template(s).dropCollection(DEMO));
        return distribution();
    }
}