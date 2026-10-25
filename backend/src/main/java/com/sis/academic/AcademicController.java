package com.sis.academic;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sis.academic.Dtos.AttendanceSaved;
import com.sis.academic.Dtos.AttendanceSheet;
import com.sis.academic.Dtos.CourseAttendanceRow;
import com.sis.academic.Dtos.CourseGrades;
import com.sis.academic.Dtos.CourseView;
import com.sis.academic.Dtos.Enrollments;
import com.sis.academic.Dtos.ExamInfo;
import com.sis.academic.Dtos.ExamSheet;
import com.sis.academic.Dtos.MarksSaved;
import com.sis.academic.Dtos.Overview;
import com.sis.academic.Dtos.PersonRef;
import com.sis.academic.Dtos.Profile;
import com.sis.academic.Dtos.RegistrationResult;
import com.sis.academic.Dtos.StudentAttendance;
import com.sis.academic.Dtos.StudentGrades;
import com.sis.academic.Dtos.TimetableSlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Person 3: course registration, attendance, exams and grades, timetable, autocomplete search.
 * All API calls require a session. AccessGuard uses the authenticated principal, ignoring legacy ID headers.
 */
@RestController
@RequestMapping("/api/academic")
public class AcademicController {
    private final CourseService courses;
    private final RegistrationService registrations;
    private final TimetableService timetable;
    private final AttendanceService attendance;
    private final GradeService grades;
    private final SearchService search;
    private final AcademicMetrics metrics;
    private final PersonDirectory people;
    private final OverviewService overview;
    private final AcademicSeeder seeder;
    private final AccessGuard guard;

    public AcademicController(CourseService courses, RegistrationService registrations, TimetableService timetable,
                              AttendanceService attendance, GradeService grades, SearchService search,
                              AcademicMetrics metrics, PersonDirectory people, OverviewService overview,
                              AcademicSeeder seeder, AccessGuard guard) {
        this.courses = courses;
        this.registrations = registrations;
        this.timetable = timetable;
        this.attendance = attendance;
        this.grades = grades;
        this.search = search;
        this.metrics = metrics;
        this.people = people;
        this.overview = overview;
        this.seeder = seeder;
        this.guard = guard;
    }

    // ---------------------------------------------------------------- search + metrics

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(defaultValue = "student") String type,
                                      @RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "8") int limit) {
        long t0 = System.nanoTime();
        List<SearchIndex.Result> results = search.search(type, q, Math.min(Math.max(limit, 1), 50));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", q);
        out.put("type", type);
        out.put("results", results);
        out.put("tookMs", Math.round((System.nanoTime() - t0) / 1000.0) / 1000.0);
        return out;
    }

    public record FeedbackRequest(int rank) {
    }

    @PostMapping("/search/feedback")
    public Map<String, Object> feedback(@RequestBody FeedbackRequest r) {
        if (r.rank() < 1) {
            throw new IllegalArgumentException("rank must be 1 or more");
        }
        metrics.recordSelection(r.rank());
        return Map.of("ok", true);
    }

    @PostMapping("/search/rebuild")
    public Map<String, Object> rebuild() {
        return Map.of("rebuilt", search.refresh(), "index", search.sizes());
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot(search.sizes());
    }

    public record BenchmarkRequest(Integer n) {
    }

    @PostMapping("/metrics/benchmark")
    public Map<String, Object> benchmark(@RequestBody(required = false) BenchmarkRequest r) {
        int n = r == null || r.n() == null ? 400 : r.n();
        if (n < 20 || n > 5000) {
            throw new IllegalArgumentException("n must be between 20 and 5000");
        }
        return search.benchmark(n);
    }

    @PostMapping("/metrics/reset")
    public Map<String, Object> resetMetrics() {
        metrics.reset();
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- people, overview, demo data

    @GetMapping("/people/{id}")
    public Profile profile(@PathVariable String id) {
        PersonRef p = people.require(id);
        int credits = "STUDENT".equals(p.role()) ? registrations.enrollments(id).credits() : 0;
        return new Profile(p, credits, Rules.MAX_CREDITS);
    }

    @GetMapping("/overview")
    public Overview overview() {
        return overview.overview();
    }

    @PostMapping("/seed")
    public Map<String, Object> seed() {
        return seeder.seedIfEmpty();
    }

    // ---------------------------------------------------------------- courses and registration

    @GetMapping("/courses")
    public Map<String, Object> courses(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String department,
                                       @RequestParam(required = false) String instructorId,
                                       @RequestParam(required = false) String studentId) {
        List<CourseView> list = courses.list(q, department, instructorId, studentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("courses", list);
        out.put("departments", courses.allCourses().stream().map(c -> c.department()).distinct().sorted().toList());
        return out;
    }

    @GetMapping("/courses/{id}")
    public CourseView course(@PathVariable String id) {
        return courses.view(id, null);
    }

    @GetMapping("/courses/{id}/roster")
    public List<CourseAttendanceRow> roster(@PathVariable String id) {
        guard.instructorOrAdmin(null, courses.require(id));
        return attendance.courseSummary(id);
    }

    public record RegisterRequest(@NotBlank String studentId, @NotBlank String courseId) {
    }

    @PostMapping("/registrations")
    public RegistrationResult register(@RequestHeader(value = "X-User-Id", required = false) String user,
                                       @Valid @RequestBody RegisterRequest r) {
        guard.selfOrAdmin(user, r.studentId());
        return registrations.register(r.studentId(), r.courseId());
    }

    @DeleteMapping("/registrations/{studentId}/{courseId}")
    public Map<String, Object> drop(@RequestHeader(value = "X-User-Id", required = false) String user,
                                    @PathVariable String studentId, @PathVariable String courseId) {
        guard.selfOrAdmin(user, studentId);
        registrations.drop(studentId, courseId);
        return Map.of("ok", true);
    }

    @GetMapping("/students/{id}/enrollments")
    public Enrollments enrollments(@PathVariable String id) {
        guard.selfOrAdmin(null, id);
        return registrations.enrollments(id);
    }

    // ---------------------------------------------------------------- timetable

    @GetMapping("/timetable")
    public Map<String, Object> timetable(@RequestParam(required = false) String studentId,
                                         @RequestParam(required = false) String instructorId) {
        List<TimetableSlot> slots;
        if (studentId != null && !studentId.isBlank()) {
            guard.selfOrAdmin(null, studentId);
            people.require(studentId);
            slots = timetable.forStudent(studentId);
        } else if (instructorId != null && !instructorId.isBlank()) {
            guard.selfOrAdmin(null, instructorId);
            slots = timetable.forInstructor(instructorId);
        } else {
            throw new IllegalArgumentException("Pass studentId or instructorId");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("slots", slots);
        out.put("days", Rules.DAYS.subList(0, 5));
        out.put("periods", List.of(List.of("09:00", "09:50"), List.of("10:00", "10:50"), List.of("11:10", "12:00"),
                List.of("13:30", "14:20"), List.of("14:30", "15:20"), List.of("15:30", "16:20")));
        return out;
    }

    // ---------------------------------------------------------------- attendance

    @GetMapping("/courses/{id}/attendance")
    public AttendanceSheet sheet(@PathVariable String id,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        guard.instructorOrAdmin(null, courses.require(id));
        return attendance.sheet(id, date);
    }

    public record AttendanceRequest(LocalDate date, List<String> absent, List<String> onDuty) {
    }

    @PostMapping("/courses/{id}/attendance")
    public AttendanceSaved markAttendance(@RequestHeader(value = "X-User-Id", required = false) String user,
                                          @PathVariable String id, @RequestBody AttendanceRequest r) {
        guard.instructorOrAdmin(user, courses.require(id));
        Set<String> absent = r.absent() == null ? Set.of() : new HashSet<>(r.absent());
        Set<String> od = r.onDuty() == null ? Set.of() : new HashSet<>(r.onDuty());
        return attendance.mark(id, r.date(), absent, od);
    }

    @GetMapping("/students/{id}/attendance")
    public StudentAttendance studentAttendance(@PathVariable String id) {
        guard.selfOrAdmin(null, id);
        people.require(id);
        return attendance.forStudent(id);
    }

    // ---------------------------------------------------------------- exams and grades

    @GetMapping("/courses/{id}/exams")
    public List<ExamInfo> exams(@PathVariable String id) {
        return grades.exams(id);
    }

    @GetMapping("/courses/{id}/exams/{exam}/marks")
    public ExamSheet examSheet(@PathVariable String id, @PathVariable String exam) {
        guard.instructorOrAdmin(null, courses.require(id));
        return grades.sheet(id, exam);
    }

    public record MarksRequest(@NotBlank String exam, double maxMarks, Map<String, Object> marks) {
    }

    @PostMapping("/courses/{id}/marks")
    public MarksSaved saveMarks(@RequestHeader(value = "X-User-Id", required = false) String user,
                                @PathVariable String id, @Valid @RequestBody MarksRequest r) {
        guard.instructorOrAdmin(user, courses.require(id));
        if (r.marks() == null) {
            throw new IllegalArgumentException("marks is required");
        }
        Map<String, Double> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : r.marks().entrySet()) {
            parsed.put(e.getKey(), toMark(e.getKey(), e.getValue()));
        }
        return grades.saveMarks(id, r.exam(), r.maxMarks(), parsed);
    }

    @DeleteMapping("/courses/{id}/exams/{exam}")
    public Map<String, Object> deleteExam(@RequestHeader(value = "X-User-Id", required = false) String user,
                                          @PathVariable String id, @PathVariable String exam) {
        guard.instructorOrAdmin(user, courses.require(id));
        grades.deleteExam(id, exam);
        return Map.of("ok", true);
    }

    @GetMapping("/courses/{id}/grades")
    public CourseGrades courseGrades(@PathVariable String id) {
        guard.instructorOrAdmin(null, courses.require(id));
        return grades.forCourse(id);
    }

    @GetMapping("/students/{id}/grades")
    public StudentGrades studentGrades(@PathVariable String id) {
        guard.selfOrAdmin(null, id);
        people.require(id);
        return grades.forStudent(id);
    }

    private static Double toMark(String student, Object v) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Marks for " + student + " must be a number");
        }
    }
}
