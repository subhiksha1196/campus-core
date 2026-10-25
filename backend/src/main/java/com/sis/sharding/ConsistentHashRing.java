package com.sis.sharding;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hash ring with virtual nodes.
 * Shards and keys are hashed onto a circle (0..2^64). A key belongs to the first shard
 * found clockwise from the key's position. Virtual nodes give each shard many points on
 * the circle so the data spreads evenly.
 */
public class ConsistentHashRing {
    private final int virtualNodes;
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final Set<String> nodes = new LinkedHashSet<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public ConsistentHashRing(int virtualNodes) {
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be >= 1");
        }
        this.virtualNodes = virtualNodes;
    }

    public void addNode(String node) {
        lock.writeLock().lock();
        try {
            if (!nodes.add(node)) {
                return;
            }
            for (int i = 0; i < virtualNodes; i++) {
                ring.put(hash(node + "#" + i), node);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeNode(String node) {
        lock.writeLock().lock();
        try {
            if (!nodes.remove(node)) {
                return;
            }
            for (int i = 0; i < virtualNodes; i++) {
                ring.remove(hash(node + "#" + i));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Returns the shard that owns this key. */
    public String getNode(String key) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) {
                throw new IllegalStateException("No shards in the ring");
            }
            Map.Entry<Long, String> e = ring.ceilingEntry(hash(key));
            if (e == null) {
                e = ring.firstEntry(); // wrap around the circle
            }
            return e.getValue();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Set<String> getNodes() {
        lock.readLock().lock();
        try {
            return new LinkedHashSet<>(nodes);
        } finally {
            lock.readLock().unlock();
        }
    }

    private static long hash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(d).getLong(); // first 8 bytes
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}