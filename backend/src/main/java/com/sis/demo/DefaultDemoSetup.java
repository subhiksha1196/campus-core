package com.sis.demo;

import com.sis.academic.AcademicSeeder;
import com.sis.academic.CourseService;
import com.sis.academic.SearchService;
import com.sis.auth.Passwords;
import com.sis.notification.Notification;
import com.sis.schema.*;
import com.sis.sharding.ShardRouter;
import java.time.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/** Explicit local-demo bootstrap. Only app1 enables this component. */
@Component
@ConditionalOnProperty(name="sis.default-demo.enabled", havingValue="true")
public class DefaultDemoSetup {
    private static final Logger log = LoggerFactory.getLogger(DefaultDemoSetup.class);
    private static final String MARKER = "campuscore-default-demo-v1";
    private static final String COLLECTION = "demo_setup";
    public static final String ADMIN = "SDEMOADMIN1", TEACHER = "SDEMOTEACHER1", STUDENT = "SDEMOSTUDENT1";
    private final ShardRouter router;
    private final MongoTemplate metadata;
    private final AcademicSeeder academic;
    private final CourseService courses;
    private final SearchService search;
    private final String password;

    public DefaultDemoSetup(ShardRouter router, MongoTemplate metadata, AcademicSeeder academic,
            CourseService courses, SearchService search,
            @Value("${sis.default-demo.password:LocalDemo2026!}") String password) {
        this.router=router; this.metadata=metadata; this.academic=academic;
        this.courses=courses; this.search=search; this.password=password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        Document marker=metadata.findById(MARKER, Document.class, COLLECTION);
        if(marker!=null && Boolean.TRUE.equals(marker.getBoolean("complete"))) {
            log.info("Default demo setup already complete; existing demo edits preserved.");
            return;
        }
        if(password.length()<10 || password.length()>128)
            throw new IllegalArgumentException("Default demo password must contain 10 to 128 characters");
        // Ensure the original dataset is seeded first, regardless of listener ordering.
        academic.seedIfEmpty();
        if(marker==null) {
            // Never take over an account or a course that already uses our reserved identity.
            for(String shard:router.shardNames()) {
                MongoTemplate db=router.template(shard);
                if(db.exists(Query.query(new Criteria().orOperator(
                    Criteria.where("_id").in(ADMIN,TEACHER,STUDENT),
                    Criteria.where("email").in("demo.admin@ssn.edu.in","demo.teacher@ssn.edu.in","demo.student@ssn.edu.in"))),Student.class)
                    || db.exists(Query.query(Criteria.where("_id").in("CDEMO101","CDEMO102","CDEMO103")),Course.class))
                    throw new IllegalStateException("Default demo identity already exists without setup ownership; no accounts overwritten");
            }
            // Reserved fixture IDs intentionally do not consume/reset the production ID counter.
            metadata.insert(new Document("_id",MARKER).append("complete",false),COLLECTION);
        }
        Instant now=Instant.now();
        String hash=Passwords.hash(password);
        insertMissing(ADMIN,new Student(ADMIN,"Demo Administrator","demo.admin@ssn.edu.in","ADMIN",0,hash,"ADMIN",now),Student.class,ADMIN);
        insertMissing(TEACHER,new Student(TEACHER,"Dr. Demo Teacher","demo.teacher@ssn.edu.in","CSE",0,hash,"FACULTY",now),Student.class,TEACHER);
        insertMissing(STUDENT,new Student(STUDENT,"Demo Student","demo.student@ssn.edu.in","CSE",2,hash,"STUDENT",now),Student.class,STUDENT);
        String semester=Year.now()+"-"+(LocalDate.now().getMonthValue()<=6?"EVEN":"ODD");
        String[] titles={"Data Structures — Demo","Database Systems — Demo","Computer Networks — Demo"};
        String[] days={"MON","WED","FRI"};
        for(int i=0;i<3;i++) {
            String cid="CDEMO10"+(i+1), code="DEMO10"+(i+1);
            insertMissing(cid,new Course(cid,code,titles[i],i==0?4:3,"CSE",semester,40,TEACHER),Course.class,cid);
            String tid="TTDEMO"+i;
            insertMissing(cid,new TimetableEntry(tid,cid,days[i],"09:00","09:50","Demo A10"+(i+1)),TimetableEntry.class,tid);
            String rid="RDEMO"+i;
            insertMissing(STUDENT,new Registration(rid,STUDENT,cid,semester,"REGISTERED",now),Registration.class,rid);
            LocalDate last=LocalDate.now().with(java.time.temporal.TemporalAdjusters.previous(DayOfWeek.valueOf(new String[]{"MONDAY","WEDNESDAY","FRIDAY"}[i])));
            for(int week=0;week<8;week++) {
                String aid="ADEMO"+i+"W"+week;
                // 7/8 for two courses, 5/8 for one: demonstrates an attendance warning.
                String status=week<(i==2?3:1)?"ABSENT":"PRESENT";
                insertMissing(STUDENT,new Attendance(aid,STUDENT,cid,last.minusWeeks(week),status),Attendance.class,aid);
            }
            for(int exam=0;exam<2;exam++) {
                String gid="GDEMO"+i+"E"+exam;
                double marks=exam==0?42-i*4:23-i*2;
                insertMissing(STUDENT,new Grade(gid,STUDENT,cid,exam==0?"MIDSEM":"ASSIGNMENT",marks,exam==0?50:25,""),Grade.class,gid);
            }
        }
        insertMissing(STUDENT,new Notification("NDEMOWELCOME",STUDENT,"GENERAL","Welcome to your demo semester",
            "Your three demo courses include timetable entries, attendance and exam marks. Sign in as the demo teacher to edit them.",false,now),Notification.class,"NDEMOWELCOME");
        insertMissing(STUDENT,new Notification("NDEMOATTENDANCE",STUDENT,"ATTENDANCE","Review your attendance",
            "Your Computer Networks demo course has attendance below 75%. Open Attendance to see the recovery guidance.",false,now),Notification.class,"NDEMOATTENDANCE");
        courses.invalidateLists();
        search.refresh();
        metadata.save(new Document("_id",MARKER).append("complete",true).append("createdAt",java.util.Date.from(now)),COLLECTION);
        log.info("Default demo setup ready: demo.admin@ssn.edu.in, demo.teacher@ssn.edu.in, demo.student@ssn.edu.in; 3 courses, 24 attendance records, 6 marks.");
    }
    private <T> void insertMissing(String key,T value,Class<T> type,String id) {
        if(router.forKey(key).findById(id,type)==null) router.insert(key,value);
    }
}
