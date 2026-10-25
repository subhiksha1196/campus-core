package com.sis.academic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.sis.academic.Dtos.CourseGradeRow;
import com.sis.academic.Dtos.CourseGrades;
import com.sis.academic.Dtos.ExamInfo;
import com.sis.academic.Dtos.ExamSheet;
import com.sis.academic.Dtos.MarkRow;
import com.sis.academic.Dtos.MarksSaved;
import com.sis.academic.Dtos.PersonRef;
import com.sis.academic.Dtos.StudentCourseGrade;
import com.sis.academic.Dtos.StudentExamMark;
import com.sis.academic.Dtos.StudentGrades;
import com.sis.schema.Course;
import com.sis.schema.Grade;
import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Exams and marks. The shared schema has one Grade row per (student, course, exam) and no separate exam table,
 * so an "exam" exists once at least one student has marks for it, and a course result is the total marks over the
 * total maximum of the exams graded so far (so exams carry weight in proportion to their maximum marks).
 */
@Service
public class GradeService {
    private final ShardRouter router;
    private final ShardScan scan;
    private final CourseService courses;
    private final NotificationHook notify;

    public GradeService(ShardRouter router, ShardScan scan, CourseService courses, NotificationHook notify) {
        this.router = router;
        this.scan = scan;
        this.courses = courses;
        this.notify = notify;
    }

    private List<Grade> courseGrades(String courseId) {
        return scan.findAll(Query.query(Criteria.where("courseId").is(courseId)), Grade.class);
    }

    static String examName(String raw) {
        String e = raw == null ? "" : raw.trim().toUpperCase();
        if (e.isEmpty() || e.length() > 40) {
            throw new IllegalArgumentException("Exam name is required (up to 40 characters)");
        }
        return e;
    }

    // ---- faculty side ----

    public List<ExamInfo> exams(String courseId) {
        courses.require(courseId);
        Map<String, double[]> byExam = new TreeMap<>();   // {max, graded}
        for (Grade g : courseGrades(courseId)) {
            double[] v = byExam.computeIfAbsent(g.exam(), k -> new double[] {g.maxMarks(), 0});
            v[1]++;
        }
        List<ExamInfo> out = new ArrayList<>();
        byExam.forEach((exam, v) -> out.add(new ExamInfo(exam, v[0], (long) v[1])));
        return out;
    }

    public ExamSheet sheet(String courseId, String exam) {
        String name = examName(exam);
        Map<String, Grade> have = new HashMap<>();
        double max = 0;
        for (Grade g : courseGrades(courseId)) {
            if (g.exam().equals(name)) {
                have.put(g.studentId(), g);
                max = g.maxMarks();
            }
        }
        List<MarkRow> rows = new ArrayList<>();
        for (PersonRef p : courses.roster(courseId)) {
            Grade g = have.get(p.id());
            rows.add(new MarkRow(p.id(), p.name(), g == null ? null : g.marks()));
        }
        return new ExamSheet(name, max, rows);
    }

    /** marks: studentId to marks; a null value clears that student's mark. */
    public MarksSaved saveMarks(String courseId, String exam, double maxMarks, Map<String, Double> marks) {
        Course c = courses.require(courseId);
        String name = examName(exam);
        if (!(maxMarks > 0) || maxMarks > 1000) {
            throw new IllegalArgumentException("Maximum marks must be between 1 and 1000");
        }
        for (ExamInfo e : exams(courseId)) {
            if (e.exam().equals(name) && Math.abs(e.maxMarks() - maxMarks) > 1e-9) {
                throw new IllegalArgumentException(name + " is already out of " + trim(e.maxMarks())
                        + ". Use the same maximum, or delete the exam first.");
            }
        }
        Set<String> inRoster = new HashSet<>();
        courses.roster(courseId).forEach(p -> inRoster.add(p.id()));
        for (Map.Entry<String, Double> e : marks.entrySet()) {
            if (!inRoster.contains(e.getKey())) {
                throw new IllegalArgumentException(e.getKey() + " is not registered for " + c.code());
            }
            Double v = e.getValue();
            if (v != null && (v < 0 || v > maxMarks)) {
                throw new IllegalArgumentException("Marks for " + e.getKey() + " must be between 0 and " + trim(maxMarks));
            }
        }

        Map<String, List<String>> byShard = new HashMap<>();
        for (String id : marks.keySet()) {
            byShard.computeIfAbsent(router.shardFor(id), k -> new ArrayList<>()).add(id);
        }
        int saved = 0;
        int cleared = 0;
        List<String> graded = new ArrayList<>();
        for (Map.Entry<String, List<String>> shard : byShard.entrySet()) {
            BulkOperations ops = router.template(shard.getKey()).bulkOps(BulkOperations.BulkMode.UNORDERED, Grade.class);
            for (String id : shard.getValue()) {
                Query q = Query.query(Criteria.where("studentId").is(id).and("courseId").is(courseId).and("exam").is(name));
                Double v = marks.get(id);
                if (v == null) {
                    ops.remove(q);
                    cleared++;
                } else {
                    ops.upsert(q, new Update().set("marks", v).set("maxMarks", maxMarks)
                            .set("grade", Rules.gradeFor(Rules.percent(v, maxMarks)).letter()));
                    saved++;
                    graded.add(id);
                }
            }
            ops.execute();
        }
        notify.many(graded, "GRADE", "Marks posted: " + c.code() + " " + name,
                "Your " + name + " marks for " + c.code() + " " + c.title() + " are now available in the Grades tab.");
        return new MarksSaved(name, saved, cleared);
    }

    public void deleteExam(String courseId, String exam) {
        courses.require(courseId);
        String name = examName(exam);
        Query q = Query.query(Criteria.where("courseId").is(courseId).and("exam").is(name));
        for (String shard : router.shardNames()) {
            router.template(shard).remove(q, Grade.class);
        }
    }

    public CourseGrades forCourse(String courseId) {
        courses.require(courseId);
        List<Grade> all = courseGrades(courseId);
        Map<String, Map<String, Grade>> byStudent = new HashMap<>();
        for (Grade g : all) {
            byStudent.computeIfAbsent(g.studentId(), k -> new HashMap<>()).put(g.exam(), g);
        }
        Map<String, Integer> dist = new LinkedHashMap<>();
        Rules.SCALE.forEach(b -> dist.put(b.letter(), 0));
        List<CourseGradeRow> rows = new ArrayList<>();
        for (PersonRef p : courses.roster(courseId)) {
            Map<String, Grade> mine = byStudent.getOrDefault(p.id(), Map.of());
            Map<String, Double> marks = new LinkedHashMap<>();
            mine.values().stream().sorted(Comparator.comparing(Grade::exam)).forEach(g -> marks.put(g.exam(), g.marks()));
            Double pct = overall(mine.values());
            String letter = pct == null ? null : Rules.gradeFor(pct).letter();
            if (letter != null) {
                dist.merge(letter, 1, Integer::sum);
            }
            rows.add(new CourseGradeRow(p.id(), p.name(), marks, pct, letter, mine.size()));
        }
        return new CourseGrades(exams(courseId), rows, dist);
    }

    // ---- student side ----

    public StudentGrades forStudent(String studentId) {
        Map<String, List<Grade>> byCourse = new HashMap<>();
        for (Grade g : router.find(studentId, Query.query(Criteria.where("studentId").is(studentId)), Grade.class)) {
            byCourse.computeIfAbsent(g.courseId(), k -> new ArrayList<>()).add(g);
        }
        List<StudentCourseGrade> out = new ArrayList<>();
        List<double[]> forGpa = new ArrayList<>();
        for (String courseId : courses.registeredIds(studentId)) {
            Course c = courses.find(courseId);
            if (c == null) {
                continue;
            }
            List<Grade> gs = byCourse.getOrDefault(courseId, List.of());
            List<StudentExamMark> exams = gs.stream().sorted(Comparator.comparing(Grade::exam))
                    .map(g -> new StudentExamMark(g.exam(), g.marks(), g.maxMarks(), g.grade())).toList();
            Double pct = overall(gs);
            Rules.GradeBand band = pct == null ? null : Rules.gradeFor(pct);
            if (band != null) {
                forGpa.add(new double[] {band.points(), c.credits()});
            }
            out.add(new StudentCourseGrade(courseId, c.code(), c.title(), c.credits(), exams, pct,
                    band == null ? null : band.letter(), band == null ? null : band.points()));
        }
        out.sort(Comparator.comparing(StudentCourseGrade::code));
        return new StudentGrades(out, Rules.gpa(forGpa), forGpa.size());
    }

    /** Total marks over total maximum, as a percentage; null when nothing is graded. */
    static Double overall(java.util.Collection<Grade> grades) {
        double got = 0;
        double max = 0;
        for (Grade g : grades) {
            got += g.marks();
            max += g.maxMarks();
        }
        return max > 0 ? Math.round(got / max * 10000.0) / 100.0 : null;
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
