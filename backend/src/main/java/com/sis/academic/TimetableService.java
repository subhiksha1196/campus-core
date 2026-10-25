package com.sis.academic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.sis.academic.Dtos.StudentTimetable;
import com.sis.academic.Dtos.TimetableSlot;
import com.sis.kv.KvStore;
import com.sis.schema.Course;
import com.sis.schema.TimetableEntry;
import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * A student's timetable = their registrations (student's shard) joined with the slots of each course
 * (course's shard). The join is cached per student and the key is deleted whenever registrations change.
 */
@Service
public class TimetableService {
    public static String key(String studentId) {
        return "timetable:" + studentId;
    }

    private static final Comparator<TimetableSlot> ORDER = Comparator
            .comparingInt((TimetableSlot s) -> Rules.DAYS.indexOf(s.day())).thenComparing(TimetableSlot::startTime);

    private final ShardRouter router;
    private final KvStore kv;
    private final CourseService courses;

    public TimetableService(ShardRouter router, KvStore kv, CourseService courses) {
        this.router = router;
        this.kv = kv;
        this.courses = courses;
    }

    public List<TimetableSlot> forStudent(String studentId) {
        return kv.cached(key(studentId), Duration.ofMinutes(10), StudentTimetable.class, () -> {
            Set<String> ids = courses.registeredIds(studentId);
            List<TimetableSlot> slots = new ArrayList<>();
            for (String courseId : ids) {
                Course c = courses.find(courseId);
                if (c == null) {
                    continue;
                }
                Query q = Query.query(Criteria.where("courseId").is(courseId));
                for (TimetableEntry t : router.find(courseId, q, TimetableEntry.class)) {
                    slots.add(new TimetableSlot(courseId, c.code(), c.title(), t.day(), t.startTime(), t.endTime(), t.room()));
                }
            }
            slots.sort(ORDER);
            return new StudentTimetable(slots);
        }).slots();
    }

    public List<TimetableSlot> forInstructor(String instructorId) {
        var byCourse = courses.slotsByCourse();
        List<TimetableSlot> slots = new ArrayList<>();
        for (Course c : courses.allCourses()) {
            if (!instructorId.equals(c.instructor())) {
                continue;
            }
            for (TimetableEntry t : byCourse.getOrDefault(c.id(), List.of())) {
                slots.add(new TimetableSlot(c.id(), c.code(), c.title(), t.day(), t.startTime(), t.endTime(), t.room()));
            }
        }
        slots.sort(ORDER);
        return slots;
    }
}
