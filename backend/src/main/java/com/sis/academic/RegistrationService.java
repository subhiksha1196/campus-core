package com.sis.academic;

import java.time.Instant;
import java.util.List;

import com.sis.academic.Dtos.CourseView;
import com.sis.academic.Dtos.Enrollments;
import com.sis.academic.Dtos.RegistrationResult;
import com.sis.common.NotFoundException;
import com.sis.kv.KvStore;
import com.sis.schema.Course;
import com.sis.schema.Registration;
import com.sis.schema.TimetableEntry;
import com.sis.sharding.ShardRouter;
import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** Sharded enrollment with a shared durable mutex around capacity, credit and schedule checks.
 * Conflicting concurrent writes return 409; see person1-design.md for crash recovery.
 */
@Service
public class RegistrationService {
    private final ShardRouter router;
    private final RegistrationMutex mutex;
    private final ShardScan scan;
    private final KvStore kv;
    private final CourseService courses;
    private final PersonDirectory people;
    private final NotificationHook notify;

    public RegistrationService(ShardRouter router, ShardScan scan, KvStore kv, CourseService courses,
                               PersonDirectory people, NotificationHook notify, RegistrationMutex mutex) {
        this.mutex=mutex;
        this.router = router;
        this.scan = scan;
        this.kv = kv;
        this.courses = courses;
        this.people = people;
        this.notify = notify;
    }

    public RegistrationResult register(String studentId, String courseId) {
        return mutex.run(() -> registerLocked(studentId,courseId));
    }
    private RegistrationResult registerLocked(String studentId, String courseId) {
        if (!"STUDENT".equals(people.require(studentId).role())) throw new IllegalArgumentException("Only students can register for courses");
        Course c = courses.require(courseId);

        List<Registration> mine = router.find(studentId,
                Query.query(Criteria.where("studentId").is(studentId)), Registration.class);
        Registration existing = mine.stream()
                .filter(r -> r.courseId().equals(courseId) && c.semester().equals(r.semester()))
                .findFirst().orElse(null);
        if (existing != null && "REGISTERED".equals(existing.status())) {
            throw new IllegalStateException("Already registered for " + c.code());
        }

        // seats: the course's registrations are spread over all shards
        Query taken = Query.query(Criteria.where("courseId").is(courseId)
                .and("semester").is(c.semester()).and("status").is("REGISTERED"));
        long seatsTaken = scan.countAll(taken, Registration.class);
        if (seatsTaken >= c.capacity()) {
            throw new IllegalStateException(c.code() + " is full (" + seatsTaken + " of " + c.capacity() + " seats taken)");
        }

        // credit limit and timetable clashes against the student's other registered courses
        int have = 0;
        List<TimetableEntry> wanted = courses.slotsOf(courseId);
        for (Registration r : mine) {
            if (!"REGISTERED".equals(r.status()) || !c.semester().equals(r.semester()) || r.courseId().equals(courseId)) {
                continue;
            }
            Course other = courses.find(r.courseId());
            if (other == null) {
                continue;
            }
            have += other.credits();
            for (TimetableEntry a : wanted) {
                for (TimetableEntry b : courses.slotsOf(other.id())) {
                    if (a.day().equals(b.day()) && Rules.overlaps(a.startTime(), a.endTime(), b.startTime(), b.endTime())) {
                        throw new IllegalStateException("Timetable clash: " + c.code() + " and " + other.code()
                                + " both meet " + a.day() + " at " + a.startTime());
                    }
                }
            }
        }
        if (have + c.credits() > Rules.MAX_CREDITS) {
            throw new IllegalStateException("Credit limit reached: " + have + " registered, " + c.code() + " adds "
                    + c.credits() + ", maximum is " + Rules.MAX_CREDITS);
        }

        if (existing != null) {
            // a dropped course is switched back on, because the unique index forbids a second row
            router.forKey(studentId).updateFirst(Query.query(Criteria.where("id").is(existing.id())),
                    new Update().set("status", "REGISTERED").set("registeredAt", Instant.now()), Registration.class);
        } else {
            try {
                router.insert(studentId, Registration.create(studentId, courseId, c.semester()));
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException("Already registered for " + c.code());
            }
        }
        kv.delete(TimetableService.key(studentId));
        courses.invalidateFill();
        notify.one(studentId, "COURSE", "Registered for " + c.code(),
                "You are registered for " + c.code() + " " + c.title() + ".");
        return new RegistrationResult(courseId, c.code(), c.capacity() - seatsTaken - 1, have + c.credits(),
                Rules.MAX_CREDITS);
    }

    public void drop(String studentId, String courseId) {
        mutex.run(() -> { dropLocked(studentId,courseId); return null; });
    }
    private void dropLocked(String studentId, String courseId) {
        Course c = courses.require(courseId);
        UpdateResult r = router.forKey(studentId).updateFirst(
                Query.query(Criteria.where("studentId").is(studentId).and("courseId").is(courseId)
                        .and("semester").is(c.semester()).and("status").is("REGISTERED")),
                Update.update("status", "DROPPED"), Registration.class);
        if (r.getMatchedCount() == 0) {
            throw new NotFoundException(studentId + " is not registered for " + c.code());
        }
        kv.delete(TimetableService.key(studentId));
        courses.invalidateFill();
    }

    public Enrollments enrollments(String studentId) {
        people.require(studentId);
        var ids = courses.registeredIds(studentId);
        List<CourseView> views = courses.views(courses.allCourses().stream().filter(c -> ids.contains(c.id())).toList(), ids);
        return new Enrollments(views, views.stream().mapToInt(CourseView::credits).sum(), Rules.MAX_CREDITS);
    }
}
