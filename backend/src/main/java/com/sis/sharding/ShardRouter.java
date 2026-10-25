package com.sis.sharding;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Routes every record to the MongoDB database (shard) that owns its key.
 * Counts requests per shard, supports adding/removing shards at runtime,
 * and can mark a shard as down to test failure behaviour.
 */
@Component
public class ShardRouter {
    private final MongoClient mongoClient;
    private final MongoTemplate metadata;
    private final MongoConverter converter;
    private final ConsistentHashRing ring;
    private final Map<String, MongoTemplate> shards = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> load = new ConcurrentHashMap<>();
    private final Set<String> down = ConcurrentHashMap.newKeySet();

    public ShardRouter(MongoClient mongoClient, MongoConverter converter, MongoTemplate metadata,
                       @Value("${sis.sharding.shards:shard0,shard1,shard2}") List<String> shardNames,
                       @Value("${sis.sharding.virtual-nodes:150}") int virtualNodes) {
        this.mongoClient = mongoClient;
        this.metadata=metadata;
        this.converter = converter;
        this.ring = new ConsistentHashRing(virtualNodes);
        org.bson.Document saved=metadata.findById("active",org.bson.Document.class,"shard_topology");
        if(saved!=null) shardNames=saved.getList("shards",String.class);
        for (String name : shardNames) {
            registerShard(name.trim());
        }
    }

    private void registerShard(String name) {
        shards.put(name, new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, name), converter));
        load.put(name, new LongAdder());
        ring.addNode(name);
    }

    public void persistTopology() {
        metadata.save(new org.bson.Document("_id","active").append("shards",new java.util.ArrayList<>(shardNames())),"shard_topology");
    }
    // ---- topology changes ----

    public synchronized void addShard(String name) {
        if (shards.containsKey(name)) {
            throw new IllegalArgumentException("Shard " + name + " already exists");
        }
        registerShard(name);
    }

    /** Step 1 of removal: new requests stop going to this shard (its data is still readable for migration). */
    public synchronized void removeFromRing(String name) {
        if (!ring.getNodes().contains(name)) {
            throw new IllegalArgumentException("Unknown shard " + name);
        }
        if (ring.getNodes().size() <= 1) {
            throw new IllegalStateException("Cannot remove the last shard");
        }
        ring.removeNode(name);
    }

    /** Step 2 of removal, after its data has been moved. */
    public synchronized void unregisterShard(String name) {
        shards.remove(name);
        load.remove(name);
        down.remove(name);
    }

    public Set<String> registeredShards() {
        return new TreeSet<>(shards.keySet());
    }

    // ---- failure simulation ----

    public void markDown(String name) {
        if (!shards.containsKey(name)) {
            throw new IllegalArgumentException("Unknown shard " + name);
        }
        down.add(name);
    }

    public void markUp(String name) {
        down.remove(name);
    }

    public Set<String> downShards() {
        return new TreeSet<>(down);
    }

    // ---- routing ----

    /** Which shard owns this key. */
    public String shardFor(String key) {
        return ring.getNode(key);
    }

    /** The shard handle for this key (counts as one request; throws 503 if that shard is down). */
    public MongoTemplate forKey(String key) {
        String shard = ring.getNode(key);
        if (down.contains(shard)) {
            throw new ShardUnavailableException(shard);
        }
        load.get(shard).increment();
        return shards.get(shard);
    }

    /** A specific shard's handle (counts as one request; throws 503 if it is down). */
    public MongoTemplate template(String shardName) {
        if (down.contains(shardName)) {
            throw new ShardUnavailableException(shardName);
        }
        load.get(shardName).increment();
        return shards.get(shardName);
    }

    /** A shard's handle for monitoring/migration only (not counted, ignores the down flag). */
    public MongoTemplate peek(String shardName) {
        return shards.get(shardName);
    }

    public Set<String> shardNames() {
        return ring.getNodes();
    }

    public Map<String, Long> loadSnapshot() {
        Map<String, Long> out = new TreeMap<>();
        for (String s : ring.getNodes()) {
            LongAdder a = load.get(s);
            out.put(s, a == null ? 0L : a.sum());
        }
        return out;
    }

    public void resetLoad() {
        load.values().forEach(LongAdder::reset);
    }

    public <T> T insert(String key, T entity) {
        return forKey(key).insert(entity);
    }

    public <T> T save(String key, T entity) {
        return forKey(key).save(entity);
    }

    public <T> List<T> find(String key, Query query, Class<T> type) {
        return forKey(key).find(query, type);
    }
}