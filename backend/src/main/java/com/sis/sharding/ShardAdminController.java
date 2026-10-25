package com.sis.sharding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.sis.schema.SchemaIndexes;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scalability and failure experiments: add shard, remove shard, shard down / up. */
@RestController
@RequestMapping("/api/shards/admin")
public class ShardAdminController {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,30}");

    private final ShardRouter router;
    private final ShardRebalancer rebalancer;
    private final SchemaIndexes schemaIndexes;

    public ShardAdminController(ShardRouter router, ShardRebalancer rebalancer, SchemaIndexes schemaIndexes) {
        this.router = router;
        this.rebalancer = rebalancer;
        this.schemaIndexes = schemaIndexes;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeShards", router.shardNames());
        out.put("downShards", router.downShards());
        router.persistTopology();
        return out;
    }

    @PostMapping("/add")
    public Map<String, Object> add(@RequestParam String name) {
        checkName(name);
        if(!router.downShards().isEmpty()) throw new IllegalStateException("Bring all shards up before changing topology");
        int before = router.shardNames().size();
        router.addShard(name);
        schemaIndexes.ensureOn(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "added " + name);
        out.putAll(rebalancer.rebalance(before, before + 1));
        router.persistTopology();
        return out;
    }

    @PostMapping("/remove")
    public Map<String, Object> remove(@RequestParam String name) {
        checkName(name);
        if(!router.downShards().isEmpty()) throw new IllegalStateException("Bring all shards up before changing topology");
        int before = router.shardNames().size();
        router.removeFromRing(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", "removed " + name);
        out.putAll(rebalancer.rebalance(before, before - 1));
        router.unregisterShard(name);
        router.persistTopology();
        return out;
    }

    @PostMapping("/down")
    public Map<String, Object> down(@RequestParam String name) {
        router.markDown(name);
        return status();
    }

    @PostMapping("/up")
    public Map<String, Object> up(@RequestParam String name) {
        router.markUp(name);
        return status();
    }

    private static void checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Shard name must be letters, digits or underscore");
        }
    }
}