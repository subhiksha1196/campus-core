package com.sis.academic;

import java.util.List;
import java.util.Map;

import com.sis.schema.Course;
import com.sis.schema.TimetableEntry;

/** Response shapes and the small wrapper records that KvStore.cached needs (it caches by class). */
public final class Dtos {
    private Dtos() {
    }

    /** A person without the password hash: students, faculty and admins all live in "students". */
    public record PersonRef(String id, String name, String department, int year, String role) {
    }

    public record SlotView(String day, String startTime, String endTime, String room) {
    }

    public record CourseView(String id, String code, String title, int credits, String department,
                             String semester, int capacity, String instructorId, String instructorName,
                             long enrolled, long seatsLeft, List<SlotView> schedule, String scheduleText,
                             boolean registered) {
    }

    public record TimetableSlot(String courseId, String code, String title, String day, String startTime,
                                String endTime, String room) {
    }

    // ---- cached wrappers ----
    public record AllCourses(List<Course> courses) {
    }

    public record AllTimetable(List<TimetableEntry> entries) {
    }

    public record CourseFill(Map<String, Long> enrolled) {
    }

    public record StudentTimetable(List<TimetableSlot> slots) {
    }

    // ---- registration ----
    public record RegistrationResult(String courseId, String code, long seatsLeft, int credits, int maxCredits) {
    }

    public record Enrollments(List<CourseView> courses, int credits, int maxCredits) {
    }

    public record Profile(PersonRef person, int credits, int maxCredits) {
    }

    // ---- attendance ----
    public record SheetRow(String id, String name, String status) {
    }

    public record SessionRow(String date, int present, int total) {
    }

    public record AttendanceSheet(String date, boolean exists, boolean scheduled, List<SheetRow> students,
                                  List<SessionRow> sessions) {
    }

    public record AttendanceSaved(String date, int total, int present, int absent, int onDuty) {
    }

    public record CourseAttendanceRow(String id, String name, String department, int year, Rules.AttendanceStats attendance) {
    }

    public record StudentCourseAttendance(String courseId, String code, String title, Rules.AttendanceStats stats) {
    }

    public record StudentAttendance(List<StudentCourseAttendance> courses, double minimum) {
    }

    // ---- grades ----
    public record ExamInfo(String exam, double maxMarks, long graded) {
    }

    public record MarkRow(String id, String name, Double marks) {
    }

    public record ExamSheet(String exam, double maxMarks, List<MarkRow> students) {
    }

    public record MarksSaved(String exam, int saved, int cleared) {
    }

    public record CourseGradeRow(String id, String name, Map<String, Double> marks, Double percent,
                                 String letter, int examsGraded) {
    }

    public record CourseGrades(List<ExamInfo> exams, List<CourseGradeRow> students, Map<String, Integer> distribution) {
    }

    public record StudentExamMark(String exam, double marks, double maxMarks, String grade) {
    }

    public record StudentCourseGrade(String courseId, String code, String title, int credits,
                                     List<StudentExamMark> exams, Double percent, String letter, Integer points) {
    }

    public record StudentGrades(List<StudentCourseGrade> courses, Double gpa, int gradedCourses) {
    }

    // ---- overview ----
    public record Overview(Map<String, Long> counts, List<CourseView> courses) {
    }
}
