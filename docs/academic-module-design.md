> Integration update: the original notes below describe the supplied modules. Authentication, permissions, ID allocation and registration concurrency are superseded by `auth-and-platform-design.md` and the root README. There is no identity picker or trusted X-User-Id header now.

# Academic module: design notes

Package `com.sis.academic` (backend) and the tabs Courses, Timetable, Attendance, Grades, Faculty, Search (frontend).
It follows the team rules: all SIS records go through `ShardRouter`, only the shared `com.sis.schema` records are
used (no new collections, no extra indexes), shard keys are Strings, and hot lookups use `KvStore`.

## Requirement mapping

| Feature | Where | How it uses the shared infrastructure |
|---|---|---|
| Course registration | `RegistrationService` | Registration row on the student's shard. Seat count summed over every shard. Credit limit (24) and timetable clash checked against the student's other registrations. Duplicate registration stopped by the unique index (`DuplicateKeyException` becomes HTTP 409). |
| Attendance | `AttendanceService` | Rows sharded by `studentId`. A student's own attendance is one single-shard read. A whole class is read from all shards and written as one bulk upsert per shard. `OD` counts as present. 75% minimum with "can miss N more" / "attend N in a row" hints. |
| Exams and grades | `GradeService` | One `Grade` row per (student, course, exam) on the student's shard. Course result = total marks / total maximum of the exams graded so far. Letter and points from a 10-point scale (90 O, 80 A+, 70 A, 60 B+, 50 B, 40 C). GPA is credit weighted. |
| Timetable | `TimetableService` | Slots live on the course's shard. A student's timetable joins their registrations with each course's slots and is cached as `timetable:{studentId}` in `KvStore`. |
| Autocomplete | `SearchIndex`, `SearchService` | Prefix trie per type (student, faculty, admin, course), built from a scan of all shards at startup and refreshed every minute. No database call per keystroke. One typo is tolerated. |
| Metrics | `AcademicMetrics`, `SearchBenchmark` | Search response time (mean, p50, p95, p99, per type) and suggestion accuracy (first, top 5, mean reciprocal rank) plus the position of suggestions that people really pick. Also exported as Micrometer timer `sis.academic.search`. |

## Where the data lives

| Operation | Shards touched |
|---|---|
| Register, drop, student attendance, student grades, student's registrations | the student's shard only |
| Seats left / seat check on register | all shards (count of registrations by course) |
| Course list, course roster, class attendance sheet, class marks | all shards (scatter-gather) |
| Course by id, course timetable slots | the course's shard (list views use one cached scan) |
| Autocomplete | none at query time (in-memory index) |

If a shard is down, single-student operations for students on the other shards keep working, scatter-gather
operations return HTTP 503 (as agreed for the failure demo), and autocomplete keeps serving the last index.

## Cache keys (KvStore) and when they are deleted

| Key | Content | Deleted when |
|---|---|---|
| `timetable:{studentId}` | student's timetable | the student registers or drops |
| `course:{courseId}` | one course | (10 min TTL) |
| `person:{id}` | name, department, year, role (never the password hash) | (2 min TTL) |
| `academic:courses`, `academic:timetable` | course list, all timetable slots | demo data is created |
| `academic:fill` | registered count per course (30 s TTL) | any registration or drop |

The real seat check on registration never uses the cache.

## Who may do what

There is no login yet. The UI sends the picked person's ID in `X-User-Id`, and `AccessGuard` loads that
record and checks its `role`: a student can register or drop only themself, only the course instructor (a FACULTY
record whose id equals `Course.instructor`) or an ADMIN can mark attendance and marks. When the real login exists,
change `AccessGuard.actor()` to return the logged-in person and nothing else needs to change. Forbidden actions
return HTTP 403 in the shared `ApiError` format.

## Connections to the notifications module

* Notifications are sent through `NotificationService` (best effort, never fails the main action): registered for a
  course, attendance has just fallen below 75%, and marks posted for an exam. Switch off with `sis.academic.notifications: false`.
* The "Notifications" tab now starts with the student picked on the academic tabs.
* `load-tests/locustfile.py` has a new `academic` scenario (autocomplete, course list, timetable, attendance, grades,
  roster) and `run_load_tests.py` runs it with the others.

## Differences from the shared schema's point of view

* There is no exam table, so an exam exists once at least one student has marks for it, and exams count in proportion
  to their maximum marks (no separate weights). If weights are wanted, ask for an `Exam` record in the shared schema.
* Drop sets `status = DROPPED` (the unique index forbids a second row). Registering again switches it back to `REGISTERED`.
* Faculty and admins are `Student` records with `role` FACULTY or ADMIN, and `Course.instructor` holds the faculty id.
* Seeded accounts have `passwordHash = "!seeded"` so nobody can log in as them until real credentials are set by the authentication module.

## Known limits

* No cross-shard transactions: two students racing for the very last seat can both get in. The unique index still
  prevents double registration. A fix would be a per-course seat counter in Redis (`INCR`) taken before the insert.
* The autocomplete index can be up to a minute old. `POST /api/academic/search/rebuild` refreshes it at once.
* Typo matching scans the whole vocabulary, so it is the slowest path (visible in p95). Fine for thousands of names;
  a BK-tree would replace it at larger scale.
* Class sheets read all attendance rows of a course. Fine for a class of 40 with 14 sessions.

## How to check it really uses the shards (section 9 of the team guide)

1. Start the backend. The log prints `Academic demo data: {seeded=true, ...}`.
2. In Compass, refresh: `students`, `courses`, `registrations`, `attendance`, `grades`, `timetable` appear in
   shard0, shard1 and shard2, each record in exactly one of them. The old `sis` database has none of them.
3. `GET /api/shards/locate?key=<studentId>` tells you which shard owns a student.
4. `POST /api/shards/admin/down?name=shard1`, then open Courses: the list fails with 503 while Search still answers.
   `POST /api/shards/admin/up?name=shard1` restores it.

## Tests

`RulesTest` and `SearchIndexTest` (JUnit, no database needed) cover grading, attendance hints, overlap detection,
the trie, typo tolerance and the accuracy benchmark. Run with `mvn test`.
