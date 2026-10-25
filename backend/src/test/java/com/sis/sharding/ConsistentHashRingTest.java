package com.sis.sharding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class ConsistentHashRingTest {

    @Test
    void distributionAndRebalance() {
        ConsistentHashRing ring = new ConsistentHashRing(150);
        ring.addNode("shard0");
        ring.addNode("shard1");
        ring.addNode("shard2");

        int total = 10_000;
        Map<String, String> before = new HashMap<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < total; i++) {
            String key = "S" + (1000 + i);
            String node = ring.getNode(key);
            before.put(key, node);
            counts.merge(node, 1, Integer::sum);
        }
        System.out.println("=== Distribution with 3 shards ===");
        counts.forEach((n, c) -> System.out.printf("%s: %d (%.1f%%)%n", n, c, 100.0 * c / total));
        counts.values().forEach(c -> assertTrue(c > 2500 && c < 4200, "uneven distribution: " + c));

        // add a 4th shard and see how many keys move
        ring.addNode("shard3");
        int moved = 0;
        int movedToNew = 0;
        Map<String, Integer> after = new TreeMap<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            String now = ring.getNode(e.getKey());
            after.merge(now, 1, Integer::sum);
            if (!now.equals(e.getValue())) {
                moved++;
                if (now.equals("shard3")) {
                    movedToNew++;
                }
            }
        }
        System.out.println("=== After adding shard3 ===");
        after.forEach((n, c) -> System.out.printf("%s: %d (%.1f%%)%n", n, c, 100.0 * c / total));
        System.out.printf("Keys moved: %d of %d (%.1f%%)%n", moved, total, 100.0 * moved / total);

        assertEquals(moved, movedToNew, "keys should only move to the new shard");
        assertTrue(moved > 1000 && moved < 4000, "expected roughly 25% to move, got " + moved);
    }
}