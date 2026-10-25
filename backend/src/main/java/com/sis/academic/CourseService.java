package com.sis.academic;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.sis.academic.Dtos.AllCourses;
import com.sis.academic.Dtos.AllTimetable;
import com.sis.academic.Dtos.CourseFill;
import com.sis.academic.Dtos.CourseView;
import com.sis.academic.Dtos.PersonRef;
import com.sis.academic.Dtos.SlotView;
import com.sis.common.NotFoundException;
import com.sis.kv.KvStore;
import com.sis.schema.Course;
import com.sis.schema.Registration;
import com.sis.schema.TimetableEntry;
import com.sis.sharding.ShardRouter;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Courses live on the shard of their courseId, timetable slots too. Lists scan every shard once and are cached
 * in the KvStore; every registration change deletes the seat-count key so the numbers stay fresh.
 */
@Service
public class CourseService {
    static final String K_COURSES = "academic:courses";
    static final String K_TIMETABLE = "academic:timetable";
    static final String K_FILL = "academic:fill";

    private final ShardRouter router;
    private final ShardScan scan;
    private final KvStore kv;
    private final PersonDirectory people;
    private final SearchService search;

    public CourseService(ShardRouter router, ShardScan scan, KvStore kv, PersonDirectory people, SearchService search) {
        this.router = router;
        this.scan = scan;
        this.kv = kv;
        this.people = people;
        this.search = search;
    }

    // ---- single course ----

    public Course find(String id) {
        return kv.cached("course:" + id, Duration.ofMinutes(10), Course.class,
                () -> router.forKey(id).findById(id, Course.class));
    }

    public Course require(String id) {
        Course c = find(id);
        if (c == null) {
            throw new NotFoundException("Course " + id + " not found");
        }
        return c;
    }

    // ---- lists (cached) ----

    public List<Course> allCourses() {
        return kv.cached(K_COURSES, Duration.ofMinutes(10), AllCourses.class, () -> {
            List<Course> list = new ArrayList<>(scan.findAll(new Query(), Course.class));
            list.sort(Comparator.comparing(Course::code));
            return new AllCourses(list);
        }).courses();
    }

    public Map<String, List<TimetableEntry>> slotsByCourse() {
        List<TimetableEntry> all = kv.cached(K_TIMETABLE, Duration.ofMinutes(10), AllTimetable.class,
                () -> new AllTimetable(scan.findAll(new Query(), TimetableEntry.class))).entries();
        Map<String, List<TimetableEntry>> out = new HashMap<>();
        for (TimetableEntry t : all) {
            out.computeIfAbsent(t.courseId(), k -> new ArrayList<>()).add(t);
        }
        out.values().forEach(l -> l.sort(Comparator.comparingInt((TimetableEntry t) -> Rules.DAYS.indexOf(t.day()))
                .thenComparing(TimetableEntry::startTime)));
        return out;
    }

    public List<TimetableEntry> slotsOf(String courseId) {
        return slotsByCourse().getOrDefault(courseId, List.of());
    }

    /** Registered students per course, summed over every shard (a course's registrations live on all of them). */
    public Map<String, Long> enrolledCounts() {
        return kv.cached(K_FILL, Duration.ofSeconds(30), CourseFill.class, () -> {
            Map<String, Long> totals = new HashMap<>();
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").is("REGISTERED")),
                    Aggregation.group("courseId").count().as("n"));
            for (String shard : router.shardNames()) {
                MongoTemplate db = router.template(shard);
                AggregationResults<Document> res = db.aggregate(agg, "registrations", Document.class);
                for (Document d : res.getMappedResults()) {
                    totals.merge(d.getString("_id"), ((Number) d.get("n")).longValue(), Long::sum);
                }
            }
            return new CourseFill(totals);
        }).enrolled();
    }

    public void invalidateFill() {
        kv.delete(K_FILL);
    }

    public void invalidateLists() {
        kv.delete(K_COURSES);
        kv.delete(K_TIMETABLE);
        kv.delete(K_FILL);
    }

    // ---- views ----

    public List<CourseView> list(String q, String department, String instructorId, String studentId) {
        List<Course> courses = new ArrayList<>(allCourses());
        if (department != null && !department.isBlank()) {
            courses.removeIf(c -> !department.equals(c.department()));
        }
        if (instructorId != null && !instructorId.isBlank()) {
            courses.removeIf(c -> !instructorId.equals(c.instructor()));
        }
        if (q != null && !q.isBlank()) {
            List<String> order = search.rankedIds("course", q, 200);
            Map<String, Integer> pos = new HashMap<>();
            for (int i = 0; i < order.size(); i++) {
                pos.put(order.get(i), i);
            }
            courses.removeIf(c -> !pos.containsKey(c.id()));
            courses.sort(Comparator.comparingInt(c -> pos.get(c.id())));
        }
        Set<String> mine = studentId == null || studentId.isBlank() ? Set.of() : registeredIds(studentId);
        return views(courses, mine);
    }

    public CourseView view(String courseId, String studentId) {
        Set<String> mine = studentId == null || studentId.isBlank() ? Set.of() : registeredIds(studentId);
        return views(List.of(require(courseId)), mine).get(0);
    }

    public List<CourseView> views(List<Course> courses, Set<String> registeredIds) {
        Map<String, Long> fill = enrolledCounts();
        Map<String, List<TimetableEntry>> slots = slotsByCourse();
        Set<String> teacherIds = courses.stream().map(Course::instructor).collect(Collectors.toSet());
        Map<String, PersonRef> teachers = people.byIds(teacherIds);
        List<CourseView> out = new ArrayList<>();
        for (Course c : courses) {
            List<SlotView> sv = slots.getOrDefault(c.id(), List.of()).stream()
                    .map(t -> new SlotView(t.day(), t.startTime(), t.endTime(), t.room())).toList();
            long enrolled = fill.getOrDefault(c.id(), 0L);
            PersonRef teacher = teachers.get(c.instructor());
            out.add(new CourseView(c.id(), c.code(), c.title(), c.credits(), c.department(), c.semester(),
                    c.capacity(), c.instructor(), teacher == null ? c.instructor() : teacher.name(), enrolled,
                    Math.max(0, c.capacity() - enrolled), sv,
                    sv.stream().map(s -> s.day() + " " + s.startTime()).collect(Collectors.joining(", ")),
                    registeredIds.contains(c.id())));
        }
        return out;
    }

    /** Course ids this student is currently registered for (one shard: the student's own). */
    public Set<String> registeredIds(String studentId) {
        Query q = Query.query(Criteria.where("studentId").is(studentId).and("status").is("REGISTERED"));
        Set<String> ids = new HashSet<>();
        for (Registration r : router.find(studentId, q, Registration.class)) {
            ids.add(r.courseId());
        }
        return ids;
    }

    /** Students registered for a course, sorted by name. Asks every shard. */
    public List<PersonRef> roster(String courseId) {
        Course c = require(courseId);
        Query q = Query.query(Criteria.where("courseId").is(courseId).and("semester").is(c.semester())
                .and("status").is("REGISTERED"));
        List<String> ids = scan.findAll(q, Registration.class).stream().map(Registration::studentId).distinct().toList();
        List<PersonRef> out = new ArrayList<>(people.byIds(ids).values());
        out.sort(Comparator.comparing(PersonRef::name));
        return out;
    }
}
