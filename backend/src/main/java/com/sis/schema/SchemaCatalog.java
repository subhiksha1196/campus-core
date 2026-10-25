package com.sis.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every sharded collection and the field that holds its shard key.
 * "_id" means the document id itself is the key.
 * ShardRebalancer and ShardStatsController read this, so a new collection is one line here.
 */
public final class SchemaCatalog {
    public static final Map<String, String> KEY_FIELDS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("students", "_id");            // key = studentId
        m.put("courses", "_id");             // key = courseId
        m.put("registrations", "studentId");
        m.put("attendance", "studentId");
        m.put("grades", "studentId");
        m.put("timetable", "courseId");
        m.put("notifications", "studentId"); // Person 4
        m.put("shard_demo", "_id");          // experiments
        KEY_FIELDS = Collections.unmodifiableMap(m);
    }

    private SchemaCatalog() {
    }
}