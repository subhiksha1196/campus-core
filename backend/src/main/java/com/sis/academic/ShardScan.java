package com.sis.academic;

import java.util.ArrayList;
import java.util.List;

import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Queries that do not know the shard key (all registrations of a course, course search, ...) must ask
 * every shard and merge. If one shard is down these fail with ShardUnavailableException (HTTP 503).
 */
@Component
public class ShardScan {
    private final ShardRouter router;

    public ShardScan(ShardRouter router) {
        this.router = router;
    }

    public <T> List<T> findAll(Query query, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (String shard : router.shardNames()) {
            out.addAll(router.template(shard).find(query, type));
        }
        return out;
    }

    public long countAll(Query query, Class<?> type) {
        long total = 0;
        for (String shard : router.shardNames()) {
            total += router.template(shard).count(query, type);
        }
        return total;
    }
}
