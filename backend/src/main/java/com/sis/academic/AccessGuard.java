package com.sis.academic;

import com.sis.academic.Dtos.PersonRef;
import com.sis.schema.Course;
import org.springframework.stereotype.Component;

/** Authorizes the authenticated server-side principal. Legacy caller-supplied IDs are ignored. */
@Component
public class AccessGuard {
    private final PersonDirectory people;

    public AccessGuard(PersonDirectory people) {
        this.people = people;
    }

    public PersonRef actor(String userId) {
        var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var student = attrs == null ? null : (com.sis.schema.Student) attrs.getRequest().getAttribute("sis.actor");
        if (student == null) throw new ForbiddenException("Sign in required");
        PersonRef p = PersonDirectory.ref(student);
        return p;
    }

    public void selfOrAdmin(String userId, String studentId) {
        PersonRef a = actor(userId);
        if (!"ADMIN".equals(a.role()) && !a.id().equals(studentId)) {
            throw new ForbiddenException("You can only change your own records");
        }
    }

    public void instructorOrAdmin(String userId, Course course) {
        PersonRef a = actor(userId);
        boolean owner = "FACULTY".equals(a.role()) && a.id().equals(course.instructor());
        if (!owner && !"ADMIN".equals(a.role())) {
            throw new ForbiddenException("Only the course instructor or an admin can do this");
        }
    }

    public void adminOnly(String userId) {
        if (!"ADMIN".equals(actor(userId).role())) {
            throw new ForbiddenException("Admins only");
        }
    }
}
