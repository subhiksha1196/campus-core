package com.sis.sharding;
import com.sis.schema.SchemaCatalog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;


/** Moves records to their correct shard after the ring changes, and reports how much moved. */
@Component
public class ShardRebalancer {
    /** collection -> field that holds its shard key */
    private static final Map<String, String> KEY_FIELDS = SchemaCatalog.KEY_FIELDS;

    private final ShardRouter router;

    public ShardRebalancer(ShardRouter router) {
        this.router = router;
    }

    public synchronized Map<String, Object> rebalance(int oldShardCount, int newShardCount) {
        long t0 = System.nanoTime();
        long scanned = 0;
        long moved = 0;
        long moduloWouldMove = 0;

        for (Map.Entry<String, String> e : KEY_FIELDS.entrySet()) {
            String coll = e.getKey();
            String keyField = e.getValue();
            for (String src : router.registeredShards()) {
                MongoTemplate from = router.peek(src);
                Map<String, List<Document>> toMove = new HashMap<>();
                for (Document d : from.findAll(Document.class, coll)) {
                    scanned++;
                    String key = String.valueOf(d.get(keyField));
                    if (Math.floorMod(key.hashCode(), oldShardCount) != Math.floorMod(key.hashCode(), newShardCount)) {
                        moduloWouldMove++;
                    }
                    String target = router.shardFor(key);
                    if (!target.equals(src)) {
                        toMove.computeIfAbsent(target, k -> new ArrayList<>()).add(d);
                    }
                }
                for (Map.Entry<String, List<Document>> t : toMove.entrySet()) {
                    router.peek(t.getKey()).insert(t.getValue(), coll);
                    List<Object> ids = t.getValue().stream().map(d -> d.get("_id")).toList();
                    from.remove(Query.query(Criteria.where("_id").in(ids)), coll);
                    moved += t.getValue().size();
                }
            }
        }

        Map<String, Object> after = new LinkedHashMap<>();
        for (String coll : KEY_FIELDS.keySet()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : router.shardNames()) {
                counts.put(s, router.peek(s).count(new Query(), coll));
            }
            after.put(coll, counts);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardsBefore", oldShardCount);
        out.put("shardsAfter", newShardCount);
        out.put("recordsScanned", scanned);
        out.put("recordsMoved", moved);
        out.put("movedPercent", pct(moved, scanned));
        out.put("plainModuloWouldHaveMovedPercent", pct(moduloWouldMove, scanned));
        out.put("rebalanceMillis", Math.round((System.nanoTime() - t0) / 1e6));
        out.put("distributionAfter", after);
        return out;
    }

    private static double pct(long part, long total) {
        return total == 0 ? 0 : Math.round(10000.0 * part / total) / 100.0;
    }
}