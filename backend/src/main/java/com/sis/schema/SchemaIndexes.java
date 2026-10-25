package com.sis.schema;

import com.sis.notification.Notification;
import com.sis.sharding.ShardRouter;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Spring only auto-creates indexes on the default database, so we create them on each shard.
 * Unique indexes always include the shard key, so each shard can enforce them on its own.
 * Email is NOT unique-indexed: it is not the shard key, so uniqueness must be checked in code.
 */
@Component
public class SchemaIndexes {
    private final ShardRouter router;

    public SchemaIndexes(ShardRouter router) {
        this.router = router;
    }

    @PostConstruct
    void onStartup() {
        for (String shard : router.shardNames()) {
            ensureOn(shard);
        }
    }

    public void ensureOn(String shard) {
        MongoTemplate db = router.peek(shard);

        db.indexOps(Student.class).ensureIndex(new Index().on("email", Sort.Direction.ASC).named("email_idx"));

        db.indexOps(Course.class).ensureIndex(new Index().on("code", Sort.Direction.ASC).named("code_idx"));
        db.indexOps(Course.class).ensureIndex(new Index().on("title", Sort.Direction.ASC).named("title_idx"));

        db.indexOps(Registration.class).ensureIndex(new Index()
                .on("studentId", Sort.Direction.ASC).on("courseId", Sort.Direction.ASC)
                .on("semester", Sort.Direction.ASC).unique().named("uniq_student_course_semester"));
        db.indexOps(Registration.class).ensureIndex(new Index()
                .on("courseId", Sort.Direction.ASC).named("course_idx"));

        db.indexOps(Attendance.class).ensureIndex(new Index()
                .on("studentId", Sort.Direction.ASC).on("courseId", Sort.Direction.ASC)
                .on("date", Sort.Direction.ASC).unique().named("uniq_student_course_date"));

        db.indexOps(Grade.class).ensureIndex(new Index()
                .on("studentId", Sort.Direction.ASC).on("courseId", Sort.Direction.ASC)
                .on("exam", Sort.Direction.ASC).unique().named("uniq_student_course_exam"));

        db.indexOps(TimetableEntry.class).ensureIndex(new Index()
                .on("courseId", Sort.Direction.ASC).on("day", Sort.Direction.ASC).named("course_day_idx"));

        db.indexOps(Notification.class).ensureIndex(new Index()
                .on("studentId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC)
                .named("student_created"));
    }
}