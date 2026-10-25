package com.sis.schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/schema")
public class SchemaCheckController {
    private final ShardRouter router;

    public SchemaCheckController(ShardRouter router) {
        this.router = router;
    }

    @PostMapping("/selftest")
    public Map<String, Object> selftest() {
        String sid = "S9001";
        String cid = "C9001";
        Query byStudent = Query.query(Criteria.where("studentId").is(sid));
        Query byCourse = Query.query(Criteria.where("courseId").is(cid));
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            router.save(sid, new Student(sid, "Test Student", "test@ssn.edu.in", "CSE", 2,
                    "hash", "STUDENT", Instant.now()));
            router.save(cid, new Course(cid, "CS9001", "Test Course", 4, "CSE", "2026-ODD", 60, "Dr Test"));
            router.save(sid, Registration.create(sid, cid, "2026-ODD"));
            router.save(sid, Attendance.create(sid, cid, LocalDate.now(), "PRESENT"));
            router.save(sid, Grade.create(sid, cid, "MIDSEM", 42, 50, "A"));
            router.save(cid, TimetableEntry.create(cid, "MON", "09:00", "09:50", "A101"));

            out.put("students", status(router.forKey(sid).findById(sid, Student.class) != null, sid));
            out.put("courses", status(router.forKey(cid).findById(cid, Course.class) != null, cid));
            out.put("registrations", status(!router.find(sid, byStudent, Registration.class).isEmpty(), sid));
            out.put("attendance", status(!router.find(sid, byStudent, Attendance.class).isEmpty(), sid));
            out.put("grades", status(!router.find(sid, byStudent, Grade.class).isEmpty(), sid));
            out.put("timetable", status(!router.find(cid, byCourse, TimetableEntry.class).isEmpty(), cid));
        } finally {
            router.forKey(sid).remove(byStudent, Registration.class);
            router.forKey(sid).remove(byStudent, Attendance.class);
            router.forKey(sid).remove(byStudent, Grade.class);
            router.forKey(sid).remove(Query.query(Criteria.where("_id").is(sid)), Student.class);
            router.forKey(cid).remove(Query.query(Criteria.where("_id").is(cid)), Course.class);
            router.forKey(cid).remove(byCourse, TimetableEntry.class);
        }
        return out;
    }

    private String status(boolean ok, String key) {
        return (ok ? "ok on " : "FAILED on ") + router.shardFor(key);
    }
}