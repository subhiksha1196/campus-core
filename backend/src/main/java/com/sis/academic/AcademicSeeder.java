package com.sis.academic;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.sis.common.IdSource;
import com.sis.kv.KvStore;
import com.sis.schema.Attendance;
import com.sis.schema.Course;
import com.sis.schema.Grade;
import com.sis.schema.Registration;
import com.sis.schema.Student;
import com.sis.schema.TimetableEntry;
import com.sis.sharding.ShardRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Demo data for the academic features: faculty, students, courses, a clash-free timetable, registrations, 14 classes
 * of attendance per course and exam marks. Everything is written through the ShardRouter (batched per shard) using
 * the shared schema records and IDs from IdSource ("S" + id, "C" + id), so it is spread over all shards.
 * It only runs when there are no courses yet, so it never touches data created by anyone else.
 */
@Service
public class AcademicSeeder {
    private static final Logger log = LoggerFactory.getLogger(AcademicSeeder.class);
    static final String SEMESTER = "2026-ODD";
    private static final String[][] PERIODS = {{"09:00", "09:50"}, {"10:00", "10:50"}, {"11:10", "12:00"},
        {"13:30", "14:20"}, {"14:30", "15:20"}, {"15:30", "16:20"}};
    private static final String[] FIRST = {"Aarav", "Aditi", "Akash", "Ananya", "Arjun", "Bhavana", "Charan", "Deepak",
        "Divya", "Gokul", "Harini", "Ishaan", "Janani", "Karthik", "Kavya", "Lakshmi", "Madhav", "Meera", "Naveen",
        "Nithya", "Pranav", "Priya", "Rahul", "Ritika", "Sanjay", "Shruthi", "Sneha", "Surya", "Tanvi", "Varun",
        "Vignesh", "Yamini", "Abinaya", "Dhanush", "Harish", "Keerthana", "Mohan", "Preethi", "Rohan", "Swathi"};
    private static final String[] LAST = {"Iyer", "Raman", "Krishnan", "Narayanan", "Subramanian", "Menon", "Nair",
        "Reddy", "Sharma", "Patel", "Gupta", "Rao", "Venkatesh", "Balaji", "Chandran", "Das", "Mukherjee", "Pillai",
        "Srinivasan", "Kumar", "Natarajan", "Sundaram", "Varma", "Joshi", "Bose"};
    private static final String[][] FACULTY = {{"Dr. R. Venkatesan", "CSE"}, {"Dr. S. Gayathri", "CSE"},
        {"Prof. M. Karthikeyan", "CSE"}, {"Dr. A. Nandini", "ECE"}, {"Dr. P. Ramesh", "MATH"},
        {"Prof. K. Sudha", "MATH"}, {"Dr. T. Anand", "GEN"}};
    // code, title, credits, department
    private static final String[][] COURSES = {
        {"CS201", "Data Structures", "4", "CSE"}, {"CS202", "Database Management Systems", "3", "CSE"},
        {"CS203", "Operating Systems", "4", "CSE"}, {"CS204", "Computer Networks", "3", "CSE"},
        {"CS205", "Design and Analysis of Algorithms", "4", "CSE"}, {"CS206", "Software Engineering", "3", "CSE"},
        {"CS207", "Computer Organization", "3", "CSE"}, {"CS208", "Object Oriented Programming", "3", "CSE"},
        {"CS301", "Distributed Systems", "3", "CSE"}, {"CS302", "Machine Learning", "4", "CSE"},
        {"CS303", "Compiler Design", "3", "CSE"}, {"CS304", "Cloud Computing", "3", "CSE"},
        {"EC201", "Digital Electronics", "4", "ECE"}, {"EC202", "Signals and Systems", "3", "ECE"},
        {"EC203", "Analog Circuits", "4", "ECE"}, {"EC204", "Communication Theory", "3", "ECE"},
        {"MA201", "Probability and Statistics", "4", "MATH"}, {"MA202", "Discrete Mathematics", "3", "MATH"},
        {"MA203", "Linear Algebra", "3", "MATH"}, {"MA204", "Numerical Methods", "3", "MATH"},
        {"HS201", "Professional Communication", "2", "HSS"}, {"HS202", "Engineering Economics", "2", "HSS"},
        {"PH201", "Engineering Physics", "3", "PHY"}, {"IT301", "Web Technologies", "3", "IT"}};

    private final ShardRouter router;
    private final ShardScan scan;
    private final IdSource ids;
    private final KvStore kv;
    private final CourseService courses;
    private final SearchService search;
    private final boolean onStartup;
    private final int studentCount;
    @Value("${sis.auth.demo-password:}") private String demoPassword;

    public AcademicSeeder(ShardRouter router, ShardScan scan, IdSource ids, KvStore kv, CourseService courses,
                          SearchService search,
                          @Value("${sis.academic.seed-on-startup:true}") boolean onStartup,
                          @Value("${sis.academic.seed-students:120}") int studentCount) {
        this.router = router;
        this.scan = scan;
        this.ids = ids;
        this.kv = kv;
        this.courses = courses;
        this.search = search;
        this.onStartup = onStartup;
        this.studentCount = studentCount;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!onStartup) {
            return;
        }
        try {
            Map<String, Object> r = seedIfEmpty();
            log.info("Academic demo data: {}", r);
        } catch (RuntimeException e) {
            log.warn("Academic demo data not created: {}", e.getMessage());
        }
    }

    public synchronized Map<String, Object> seedIfEmpty() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (demoPassword != null && !demoPassword.isBlank()) {
            if (demoPassword.length() < 10) throw new IllegalArgumentException("Demo password must contain at least 10 characters");
            String hash = com.sis.auth.Passwords.hash(demoPassword);
            for (String shard : router.shardNames()) router.template(shard).updateMulti(
                Query.query(org.springframework.data.mongodb.core.query.Criteria.where("passwordHash").is("!seeded")),
                org.springframework.data.mongodb.core.query.Update.update("passwordHash",hash),Student.class);
        }
        if (scan.countAll(new Query(), Course.class) > 0) {
            out.put("seeded", false);
            out.put("reason", "courses already exist, nothing was changed");
            return out;
        }
        String seedHash = demoPassword == null || demoPassword.isBlank() ? "!seeded" : com.sis.auth.Passwords.hash(demoPassword);
        Random rnd = new Random(2026);
        Instant now = Instant.now();

        // ---- people: one admin, faculty, students ----
        List<Student> people = new ArrayList<>();
        String adminId = "S" + ids.nextThreeDigitId("student");
        people.add(new Student(adminId, "Administrator", "admin@ssn.edu.in", "ADMIN", 0, seedHash, "ADMIN", now));
        Map<String, List<String>> facultyByDept = new HashMap<>();
        for (String[] f : FACULTY) {
            String id = "S" + ids.nextThreeDigitId("student");
            String mail = f[0].substring(f[0].lastIndexOf(' ') + 1).toLowerCase() + "@ssn.edu.in";
            people.add(new Student(id, f[0], mail, f[1], 0, seedHash, "FACULTY", now));
            facultyByDept.computeIfAbsent(f[1], k -> new ArrayList<>()).add(id);
        }
        List<String[]> names = new ArrayList<>();
        for (String f : FIRST) {
            for (String l : LAST) {
                names.add(new String[] {f, l});
            }
        }
        Collections.shuffle(names, rnd);
        String[] programs = {"CSE", "CSE", "ECE", "IT"};
        List<String> studentIds = new ArrayList<>();
        for (int i = 0; i < studentCount; i++) {
            String id = "S" + ids.nextThreeDigitId("student");
            String[] n = names.get(i);
            people.add(new Student(id, n[0] + " " + n[1], (n[0] + "." + n[1]).toLowerCase() + "@ssn.edu.in",
                    programs[rnd.nextInt(programs.length)], 2 + rnd.nextInt(2), seedHash, "STUDENT", now));
            studentIds.add(id);
        }

        // ---- courses and a timetable with no teacher or room double-booked ----
        record Plan(String id, String code, String title, int credits, String dept, String teacher, int capacity) {
        }
        List<Plan> plans = new ArrayList<>();
        Map<String, Integer> turn = new HashMap<>();
        for (String[] c : COURSES) {
            String pool = c[3].equals("HSS") || c[3].equals("PHY") || c[3].equals("IT") ? "GEN" : c[3];
            List<String> teachers = facultyByDept.get(pool);
            int k = turn.merge(pool, 1, Integer::sum) - 1;
            plans.add(new Plan("C" + ids.nextThreeDigitId("course"), c[0], c[1], Integer.parseInt(c[2]), c[3],
                    teachers.get(k % teachers.size()), 30 + rnd.nextInt(16)));
        }
        String[] rooms = {"A101", "A102", "A103", "A201", "A202", "B101", "B102", "B103", "B201", "B202"};
        Set<String> teacherBusy = new HashSet<>();
        Set<String> roomBusy = new HashSet<>();
        Map<String, List<TimetableEntry>> slots = new LinkedHashMap<>();
        for (Plan p : plans) {
            int perWeek = p.credits() >= 3 ? 3 : 2;
            List<Integer> days = new ArrayList<>(List.of(0, 1, 2, 3, 4));
            Collections.shuffle(days, rnd);
            List<TimetableEntry> list = new ArrayList<>();
            for (int d : days.subList(0, perWeek).stream().sorted().toList()) {
                for (int attempt = 0; attempt < 500; attempt++) {
                    int period = rnd.nextInt(PERIODS.length);
                    String room = rooms[rnd.nextInt(rooms.length)];
                    String tKey = p.teacher() + d + period;
                    String rKey = room + d + period;
                    if (teacherBusy.contains(tKey) || roomBusy.contains(rKey)) {
                        continue;
                    }
                    teacherBusy.add(tKey);
                    roomBusy.add(rKey);
                    list.add(TimetableEntry.create(p.id(), Rules.DAYS.get(d), PERIODS[period][0], PERIODS[period][1], room));
                    break;
                }
            }
            slots.put(p.id(), list);
        }

        // ---- registrations: 4 courses per student, no clashes, within capacity ----
        Map<String, Integer> seatsLeft = new HashMap<>();
        Map<String, List<String>> enrolled = new LinkedHashMap<>();
        for (Plan p : plans) {
            seatsLeft.put(p.id(), p.capacity());
            enrolled.put(p.id(), new ArrayList<>());
        }
        List<Registration> regs = new ArrayList<>();
        for (String sid : studentIds) {
            List<Plan> order = new ArrayList<>(plans);
            Collections.shuffle(order, rnd);
            List<Plan> chosen = new ArrayList<>();
            for (Plan p : order) {
                if (chosen.size() == 4) {
                    break;
                }
                if (seatsLeft.get(p.id()) <= 0 || clashes(slots, p.id(), chosen.stream().map(Plan::id).toList())) {
                    continue;
                }
                chosen.add(p);
                seatsLeft.merge(p.id(), -1, Integer::sum);
                enrolled.get(p.id()).add(sid);
                regs.add(Registration.create(sid, p.id(), SEMESTER));
            }
        }
        // make the busiest course completely full, so the "course is full" message can be demonstrated
        String busiest = Collections.max(enrolled.entrySet(),
                java.util.Comparator.comparingInt((Map.Entry<String, List<String>> e) -> e.getValue().size())).getKey();
        List<Course> courseRows = new ArrayList<>();
        for (Plan p : plans) {
            int cap = p.id().equals(busiest) ? enrolled.get(p.id()).size() : p.capacity();
            courseRows.add(new Course(p.id(), p.code(), p.title(), p.credits(), p.dept(), SEMESTER, cap, p.teacher()));
        }

        // ---- attendance: the last 14 class days of every course ----
        Map<String, Double> propensity = new HashMap<>();
        for (String sid : studentIds) {
            propensity.put(sid, Math.min(1.0, Math.max(0.5, 0.89 + 0.08 * rnd.nextGaussian())));
        }
        List<Attendance> attendance = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Plan p : plans) {
            Set<DayOfWeek> classDays = new HashSet<>();
            slots.get(p.id()).forEach(t -> classDays.add(DayOfWeek.of(Rules.DAYS.indexOf(t.day()) + 1)));
            if (classDays.isEmpty()) {
                continue;
            }
            List<LocalDate> dates = new ArrayList<>();
            for (LocalDate d = today.minusDays(1); dates.size() < 14; d = d.minusDays(1)) {
                if (classDays.contains(d.getDayOfWeek())) {
                    dates.add(d);
                }
            }
            for (LocalDate d : dates) {
                for (String sid : enrolled.get(p.id())) {
                    double pr = propensity.get(sid);
                    double r = rnd.nextDouble();
                    String st = r < pr ? "PRESENT" : r < pr + (1 - pr) * 0.1 ? "OD" : "ABSENT";
                    attendance.add(Attendance.create(sid, p.id(), d, st));
                }
            }
        }

        // ---- marks: CAT1 and MIDSEM everywhere, ENDSEM for half of the courses ----
        Map<String, Double> ability = new HashMap<>();
        for (String sid : studentIds) {
            ability.put(sid, 0.68 + 0.14 * rnd.nextGaussian());
        }
        String[] examNames = {"CAT1", "MIDSEM", "ENDSEM"};
        double[] examMax = {50, 50, 100};
        List<Grade> grades = new ArrayList<>();
        for (int ci = 0; ci < plans.size(); ci++) {
            Plan p = plans.get(ci);
            for (int e = 0; e < 3; e++) {
                if (e == 2 && ci % 2 == 0) {
                    continue;
                }
                for (String sid : enrolled.get(p.id())) {
                    double frac = Math.min(1.0, Math.max(0.15, ability.get(sid) + 0.08 * rnd.nextGaussian()));
                    double marks = Math.round(frac * examMax[e] * 2) / 2.0;
                    grades.add(Grade.create(sid, p.id(), examNames[e], marks, examMax[e],
                            Rules.gradeFor(Rules.percent(marks, examMax[e])).letter()));
                }
            }
        }

        // ---- write everything through the router ----
        batch(people, Student::id, Student.class);
        batch(courseRows, Course::id, Course.class);
        batch(slots.values().stream().flatMap(List::stream).toList(), TimetableEntry::courseId, TimetableEntry.class);
        batch(regs, Registration::studentId, Registration.class);
        batch(attendance, Attendance::studentId, Attendance.class);
        batch(grades, Grade::studentId, Grade.class);

        courses.invalidateLists();
        search.refresh();
        out.put("seeded", true);
        out.put("students", studentIds.size());
        out.put("faculty", FACULTY.length);
        out.put("courses", courseRows.size());
        out.put("registrations", regs.size());
        out.put("attendanceRecords", attendance.size());
        out.put("gradeRecords", grades.size());
        out.put("fullCourse", courseRows.stream().filter(c -> c.id().equals(busiest)).findFirst().map(Course::code).orElse(""));
        return out;
    }

    private boolean clashes(Map<String, List<TimetableEntry>> slots, String courseId, List<String> others) {
        for (String other : others) {
            for (TimetableEntry a : slots.get(courseId)) {
                for (TimetableEntry b : slots.get(other)) {
                    if (a.day().equals(b.day()) && Rules.overlaps(a.startTime(), a.endTime(), b.startTime(), b.endTime())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Groups records by owning shard and inserts each group in chunks (same approach as NotificationService.broadcast). */
    private <T> void batch(List<T> items, Function<T, String> key, Class<T> type) {
        Map<String, List<T>> byShard = new HashMap<>();
        for (T t : items) {
            byShard.computeIfAbsent(router.shardFor(key.apply(t)), k -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<String, List<T>> e : byShard.entrySet()) {
            List<T> list = e.getValue();
            for (int i = 0; i < list.size(); i += 1000) {
                router.template(e.getKey()).insert(new ArrayList<>(list.subList(i, Math.min(i + 1000, list.size()))), type);
            }
        }
    }
}
