package com.sis.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sis.common.NotFoundException;
import com.sis.metrics.AppMetrics;
import com.sis.shortener.ShortenerService;
import com.sis.sharding.ShardRouter;
import jakarta.annotation.PostConstruct;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Academic notifications, stored across database shards. The shard key is the studentId, so all
 * of one student's notifications live on one shard. Links inside a message are replaced by short
 * links, and the unread-badge count is cached in Redis (cache-aside, invalidated on every change).
 */
@Service
public class NotificationService {
    private static final Pattern URL = Pattern.compile("https?://\\S*[^\\s.,;:!?)]");
    private static final String UNREAD_KEY = "sis:unread:";
    private static final Duration UNREAD_TTL = Duration.ofSeconds(60);

    private final ShardRouter router;
    private final com.sis.academic.AccessGuard guard;
    private final StringRedisTemplate redis;
    private final ShortenerService shortener;
    private final AppMetrics metrics;

    public NotificationService(ShardRouter router, StringRedisTemplate redis,
                               ShortenerService shortener, AppMetrics metrics, com.sis.academic.AccessGuard guard) {
        this.guard=guard;
        this.router = router;
        this.redis = redis;
        this.shortener = shortener;
        this.metrics = metrics;
    }

    /** Spring only auto-creates indexes on the default database, so create them on every shard. */
    @PostConstruct
    void ensureIndexes() {
        for (String shard : router.shardNames()) {
            router.template(shard).indexOps(Notification.class).ensureIndex(
                    new Index().on("studentId", Sort.Direction.ASC)
                            .on("createdAt", Sort.Direction.DESC).named("student_created"));
        }
    }

    public Notification create(String studentId, String type, String title, String message) {
        return metrics.notificationWrite.record(() -> {
            Notification n = new Notification(newId(), studentId, normType(type), title,
                    shortenLinks(message), false, Instant.now());
            Notification saved = router.insert(studentId, n);
            redis.delete(UNREAD_KEY + studentId);
            metrics.notificationsCreated.increment();
            return saved;
        });
    }

    /** Sends the same notification to many students; one batch insert per shard. */
    public int broadcast(List<String> studentIds, String type, String title, String message) {
        String finalMessage = shortenLinks(message);
        String t = normType(type);
        Instant now = Instant.now();
        List<String> distinct = studentIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (distinct.isEmpty()) {
            return 0;
        }
        Map<String, List<Notification>> byShard = new HashMap<>();
        for (String s : distinct) {
            byShard.computeIfAbsent(router.shardFor(s), k -> new ArrayList<>())
                    .add(new Notification(newId(), s, t, title, finalMessage, false, now));
        }
        metrics.notificationWrite.record(() ->
                byShard.forEach((shard, list) -> router.template(shard).insert(list, Notification.class)));
        redis.delete(distinct.stream().map(s -> UNREAD_KEY + s).toList());
        metrics.notificationsCreated.increment(distinct.size());
        return distinct.size();
    }

    public List<Notification> list(String studentId, boolean unreadOnly, int page, int size) {
        int pageNo = Math.max(page, 0);
        int pageSize = Math.min(Math.max(size, 1), 100);
        Query q = new Query(Criteria.where("studentId").is(studentId));
        if (unreadOnly) {
            q.addCriteria(Criteria.where("read").is(false));
        }
        q.with(Sort.by(Sort.Direction.DESC, "createdAt")).skip((long) pageNo * pageSize).limit(pageSize);
        return router.find(studentId, q, Notification.class);
    }

    public long unreadCount(String studentId) {
        String cached = redis.opsForValue().get(UNREAD_KEY + studentId);
        if (cached != null) {
            return Long.parseLong(cached);
        }
        long count = router.forKey(studentId).count(
                Query.query(Criteria.where("studentId").is(studentId).and("read").is(false)), Notification.class);
        redis.opsForValue().set(UNREAD_KEY + studentId, Long.toString(count), UNREAD_TTL);
        return count;
    }

    /** The API only gives a notification id (no studentId), so we look for it on each shard. */
    public void markRead(String id) {
        for (String shard : router.shardNames()) {
            MongoTemplate db = router.template(shard);
            Notification n = db.findById(id, Notification.class);
            if (n != null) {
                guard.selfOrAdmin(null,n.studentId());
                if (!n.read()) {
                    db.updateFirst(Query.query(Criteria.where("id").is(id)),
                            Update.update("read", true), Notification.class);
                    redis.delete(UNREAD_KEY + n.studentId());
                }
                return;
            }
        }
        throw new NotFoundException("Notification " + id + " not found");
    }

    public long markAllRead(String studentId) {
        long modified = router.forKey(studentId).updateMulti(
                Query.query(Criteria.where("studentId").is(studentId).and("read").is(false)),
                Update.update("read", true), Notification.class).getModifiedCount();
        redis.delete(UNREAD_KEY + studentId);
        return modified;
    }

    private String shortenLinks(String text) {
        if (text == null) {
            return "";
        }
        String own = shortener.baseUrl() + "/s/";
        Matcher m = URL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String url = m.group();
            String replacement = url;
            if (!url.startsWith(own)) {
                try {
                    replacement = shortener.shortLink(shortener.create(url, null).code());
                } catch (IllegalArgumentException ignored) {
                    // not a valid URL: leave the text as it was
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normType(String type) {
        return (type == null || type.isBlank()) ? "GENERAL" : type.trim().toUpperCase();
    }

    private static String newId() {
        return new ObjectId().toHexString();
    }
}