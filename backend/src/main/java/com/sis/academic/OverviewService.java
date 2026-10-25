package com.sis.academic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sis.academic.Dtos.CourseView;
import com.sis.academic.Dtos.Overview;
import com.sis.schema.Attendance;
import com.sis.schema.Course;
import com.sis.schema.Grade;
import com.sis.schema.Registration;
import com.sis.schema.Student;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Totals for the admin overview. Every count is summed over all shards. */
@Service
public class OverviewService {
    private final ShardScan scan;
    private final CourseService courses;

    public OverviewService(ShardScan scan, CourseService courses) {
        this.scan = scan;
        this.courses = courses;
    }

    public Overview overview() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("students", scan.countAll(Query.query(Criteria.where("role").is("STUDENT")), Student.class));
        counts.put("faculty", scan.countAll(Query.query(Criteria.where("role").is("FACULTY")), Student.class));
        counts.put("courses", scan.countAll(new Query(), Course.class));
        counts.put("registrations", scan.countAll(Query.query(Criteria.where("status").is("REGISTERED")), Registration.class));
        counts.put("attendanceRecords", scan.countAll(new Query(), Attendance.class));
        counts.put("gradeRecords", scan.countAll(new Query(), Grade.class));
        List<CourseView> list = courses.list(null, null, null, null);
        return new Overview(counts, list);
    }
}
