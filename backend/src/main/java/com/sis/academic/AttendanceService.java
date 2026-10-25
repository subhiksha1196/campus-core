package com.sis.academic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.sis.academic.Dtos.AttendanceSaved;
import com.sis.academic.Dtos.AttendanceSheet;
import com.sis.academic.Dtos.CourseAttendanceRow;
import com.sis.academic.Dtos.PersonRef;
import com.sis.academic.Dtos.SessionRow;
import com.sis.academic.Dtos.SheetRow;
import com.sis.academic.Dtos.StudentAttendance;
import com.sis.academic.Dtos.StudentCourseAttendance;
import com.sis.schema.Attendance;
import com.sis.schema.Course;
import com.sis.schema.TimetableEntry;
import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Attendance rows are sharded by studentId: a student's own attendance is one single-shard read, while a
 * whole class (all students of a course on a date) is read from every shard and written as one bulk upsert
 * per shard. PRESENT and OD (on duty) both count as attended.
 */
@Service
public class AttendanceService {
    private final ShardRouter router;
    private final ShardScan scan;
    private final CourseService courses;
    private final NotificationHook notify;

    public AttendanceService(ShardRouter router, ShardScan scan, CourseService courses, NotificationHook notify) {
        this.router = router;
        this.scan = scan;
        this.courses = courses;
        this.notify = notify;
    }

    private List<Attendance> courseRecords(String courseId) {
        return scan.findAll(Query.query(Criteria.where("courseId").is(courseId)), Attendance.class);
    }

    private static Rules.AttendanceStats stats(List<String> statuses) {
        int attended = 0;
        for (String s : statuses) {
            if (Rules.attended(s)) {
                attended++;
            }
        }
        return Rules.attendanceStats(attended, statuses.size());
    }

    // ---- the class sheet used by faculty ----

    public AttendanceSheet sheet(String courseId, LocalDate requested) {
        Course c = courses.require(courseId);
        List<Attendance> records = courseRecords(courseId);

        Map<LocalDate, int[]> perDate = new TreeMap<>(Comparator.reverseOrder());   // {attended, total}
        for (Attendance a : records) {
            int[] t = perDate.computeIfAbsent(a.date(), d -> new int[2]);
            t[1]++;
            if (Rules.attended(a.status())) {
                t[0]++;
            }
        }
        LocalDate date = requested != null ? requested
                : perDate.isEmpty() ? LocalDate.now() : perDate.keySet().iterator().next();
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Attendance cannot be marked for a future date");
        }

        Map<String, String> marked = new HashMap<>();
        for (Attendance a : records) {
            if (a.date().equals(date)) {
                marked.put(a.studentId(), a.status());
            }
        }
        List<SheetRow> rows = new ArrayList<>();
        for (PersonRef p : courses.roster(courseId)) {
            rows.add(new SheetRow(p.id(), p.name(), marked.getOrDefault(p.id(), "PRESENT")));
        }
        String dayName = date.getDayOfWeek().name().substring(0, 3);
        boolean scheduled = courses.slotsOf(courseId).stream().map(TimetableEntry::day).anyMatch(dayName::equals);
        List<SessionRow> sessions = new ArrayList<>();
        for (Map.Entry<LocalDate, int[]> e : perDate.entrySet()) {
            if (sessions.size() >= 30) {
                break;
            }
            sessions.add(new SessionRow(e.getKey().toString(), e.getValue()[0], e.getValue()[1]));
        }
        return new AttendanceSheet(date.toString(), !marked.isEmpty(), scheduled, rows, sessions);
    }

    /** Saves one class. Everyone on the roster is PRESENT unless listed as absent or on duty. Saving a date again replaces it. */
    public AttendanceSaved mark(String courseId, LocalDate date, Set<String> absent, Set<String> onDuty) {
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Attendance cannot be marked for a future date");
        }
        Course c = courses.require(courseId);
        List<String> roster = courses.roster(courseId).stream().map(PersonRef::id).toList();
        Set<String> inRoster = new HashSet<>(roster);
        for (String id : absent) {
            if (!inRoster.contains(id)) {
                throw new IllegalArgumentException(id + " is not registered for " + c.code());
            }
        }
        for (String id : onDuty) {
            if (!inRoster.contains(id)) {
                throw new IllegalArgumentException(id + " is not registered for " + c.code());
            }
            if (absent.contains(id)) {
                throw new IllegalArgumentException(id + " cannot be both absent and on duty");
            }
        }

        // before/after percentages, to notify only students who just fell below the minimum
        Map<String, List<Attendance>> history = new HashMap<>();
        for (Attendance a : courseRecords(courseId)) {
            history.computeIfAbsent(a.studentId(), k -> new ArrayList<>()).add(a);
        }

        Map<String, String> status = new LinkedHashMap<>();
        for (String id : roster) {
            status.put(id, absent.contains(id) ? "ABSENT" : onDuty.contains(id) ? "OD" : "PRESENT");
        }
        Map<String, List<String>> byShard = new HashMap<>();
        for (String id : roster) {
            byShard.computeIfAbsent(router.shardFor(id), k -> new ArrayList<>()).add(id);
        }
        for (Map.Entry<String, List<String>> e : byShard.entrySet()) {
            BulkOperations ops = router.template(e.getKey()).bulkOps(BulkOperations.BulkMode.UNORDERED, Attendance.class);
            for (String id : e.getValue()) {
                ops.upsert(Query.query(Criteria.where("studentId").is(id).and("courseId").is(courseId).and("date").is(date)),
                        Update.update("status", status.get(id)));
            }
            ops.execute();
        }

        List<String> nowLow = new ArrayList<>();
        for (String id : roster) {
            List<Attendance> old = history.getOrDefault(id, List.of());
            List<String> before = new ArrayList<>();
            List<String> after = new ArrayList<>();
            for (Attendance a : old) {
                before.add(a.status());
                if (!a.date().equals(date)) {
                    after.add(a.status());
                }
            }
            after.add(status.get(id));
            if (stats(after).status().equals("low") && !stats(before).status().equals("low")) {
                nowLow.add(id);
            }
        }
        for (String id : nowLow) {
            notify.one(id, "ATTENDANCE", "Attendance below " + (int) Rules.ATTENDANCE_MIN + "% in " + c.code(),
                    "Your attendance in " + c.code() + " " + c.title() + " has fallen below the minimum. Open the Attendance tab to see how many classes you need.");
        }
        int abs = absent.size();
        int od = onDuty.size();
        return new AttendanceSaved(date.toString(), roster.size(), roster.size() - abs, abs, od);
    }

    // ---- per student / per course summaries ----

    public List<CourseAttendanceRow> courseSummary(String courseId) {
        Map<String, List<String>> byStudent = new HashMap<>();
        for (Attendance a : courseRecords(courseId)) {
            byStudent.computeIfAbsent(a.studentId(), k -> new ArrayList<>()).add(a.status());
        }
        List<CourseAttendanceRow> out = new ArrayList<>();
        for (PersonRef p : courses.roster(courseId)) {
            out.add(new CourseAttendanceRow(p.id(), p.name(), p.department(), p.year(),
                    stats(byStudent.getOrDefault(p.id(), List.of()))));
        }
        return out;
    }

    public StudentAttendance forStudent(String studentId) {
        Map<String, List<String>> byCourse = new HashMap<>();
        for (Attendance a : router.find(studentId, Query.query(Criteria.where("studentId").is(studentId)), Attendance.class)) {
            byCourse.computeIfAbsent(a.courseId(), k -> new ArrayList<>()).add(a.status());
        }
        List<StudentCourseAttendance> out = new ArrayList<>();
        for (String courseId : courses.registeredIds(studentId)) {
            Course c = courses.find(courseId);
            if (c != null) {
                out.add(new StudentCourseAttendance(courseId, c.code(), c.title(),
                        stats(byCourse.getOrDefault(courseId, List.of()))));
            }
        }
        out.sort(Comparator.comparing(StudentCourseAttendance::code));
        return new StudentAttendance(out, Rules.ATTENDANCE_MIN);
    }
}
