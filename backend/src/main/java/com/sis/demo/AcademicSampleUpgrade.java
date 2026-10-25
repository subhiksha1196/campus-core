package com.sis.demo;

import com.sis.academic.*;
import com.sis.auth.Passwords;
import com.sis.common.IdSource;
import com.sis.kv.KvStore;
import com.sis.notification.Notification;
import com.sis.schema.*;
import com.sis.sharding.ShardRouter;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

/** Additive, resumable upgrade for the already-installed three-account dataset. */
@Component
@ConditionalOnProperty(name="sis.default-demo.enabled",havingValue="true")
public class AcademicSampleUpgrade {
 private static final Logger log=LoggerFactory.getLogger(AcademicSampleUpgrade.class);
 private static final String MARKER="campuscore-academic-sample-v2";
 private static final String[] NAMES={"Aarav Sharma","Aditi Raman","Akash Iyer","Ananya Menon","Arjun Nair","Bhavana Rao","Charan Kumar","Deepak Reddy","Divya Krishnan","Gokul Natarajan","Harini Sundaram","Ishaan Patel","Janani Srinivasan","Karthik Balaji","Kavya Narayanan","Lakshmi Chandran","Madhav Joshi","Meera Pillai","Naveen Varma","Nithya Subramanian"};
 private final ShardRouter router;private final MongoTemplate metadata;private final DefaultDemoSetup original;
 private final IdSource ids;private final KvStore kv;private final CourseService courses;private final SearchService search;private final String password;
 public AcademicSampleUpgrade(ShardRouter router,MongoTemplate metadata,DefaultDemoSetup original,IdSource ids,KvStore kv,CourseService courses,SearchService search,
  @Value("${sis.default-demo.password:LocalDemo2026!}")String password){this.router=router;this.metadata=metadata;this.original=original;this.ids=ids;this.kv=kv;this.courses=courses;this.search=search;this.password=password;}
 @EventListener(ApplicationReadyEvent.class)
 public synchronized void onReady(){
  original.onReady();
  Document marker=metadata.findById(MARKER,Document.class,"demo_setup");
  if(marker!=null&&Boolean.TRUE.equals(marker.getBoolean("complete"))){log.info("Academic sample v2 already installed; edits retained.");return;}
  if(password.length()<10||password.length()>128)throw new IllegalArgumentException("Sample password must contain 10 to 128 characters");
  if(marker==null){
   List<String> students=new ArrayList<>();students.add(DefaultDemoSetup.STUDENT);
   for(int i=1;i<20;i++)students.add("S"+ids.nextThreeDigitId("student"));
   marker=new Document("_id",MARKER).append("students",students).append("complete",false);
   metadata.insert(marker,"demo_setup");
  }
  List<String> students=marker.getList("students",String.class);
  // Preflight all new email identities before modifying the existing profiles.
  checkEmail("office.admin@ssn.edu.in",DefaultDemoSetup.ADMIN);
  checkEmail("faculty.cse@ssn.edu.in",DefaultDemoSetup.TEACHER);
  for(int i=0;i<20;i++)checkEmail(email(i),students.get(i));
  rename(DefaultDemoSetup.ADMIN,"Meera Krishnan","office.admin@ssn.edu.in");
  rename(DefaultDemoSetup.TEACHER,"Dr. R. Venkatesan","faculty.cse@ssn.edu.in");
  rename(DefaultDemoSetup.STUDENT,NAMES[0],email(0));
  String hash=Passwords.hash(password);Instant now=Instant.now();
  for(int i=1;i<20;i++){
   String sid=students.get(i);
   if(router.forKey(sid).findById(sid,Student.class)==null)
    router.insert(sid,new Student(sid,NAMES[i],email(i),"CSE",2,hash,"STUDENT",now));
  }
  String[] titles={"Data Structures","Database Systems","Computer Networks"};
  for(int c=0;c<3;c++){
   String cid="CDEMO10"+(c+1);Course old=router.forKey(cid).findById(cid,Course.class);
   if(old==null)throw new IllegalStateException("Missing sample course "+cid);
   router.save(cid,new Course(old.id(),"CS2"+(c+1)+"1",titles[c],old.credits(),old.department(),old.semester(),Math.max(40,old.capacity()),old.instructor()));
   for(TimetableEntry t:router.find(cid,Query.query(Criteria.where("courseId").is(cid)),TimetableEntry.class))
    if(t.room()!=null&&t.room().startsWith("Demo "))router.save(cid,new TimetableEntry(t.id(),t.courseId(),t.day(),t.startTime(),t.endTime(),t.room().substring(5)));
   for(int i=1;i<20;i++){
    String sid=students.get(i),rid="RSAMPLE"+c+"-"+sid;
    insert(sid,new Registration(rid,sid,cid,old.semester(),"REGISTERED",now),Registration.class,rid);
    for(int w=0;w<8;w++){
     Attendance template=router.forKey(DefaultDemoSetup.STUDENT).findById("ADEMO"+c+"W"+w,Attendance.class);
     LocalDate date=template!=null?template.date():LocalDate.now().with(java.time.temporal.TemporalAdjusters.previous(new DayOfWeek[]{DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY,DayOfWeek.FRIDAY}[c])).minusWeeks(w);
     String aid="ASAMPLE"+c+"-"+sid+"-"+w;
     String status=(i+c+w)%7==0?"OD":(i+c+w)%(i%4==0?3:9)==0?"ABSENT":"PRESENT";
     insert(sid,new Attendance(aid,sid,cid,date,status),Attendance.class,aid);
    }
    for(int exam=0;exam<2;exam++){
     String gid="GSAMPLE"+c+"-"+sid+"-"+exam;
     double max=exam==0?50:25,marks=exam==0?25+(i*7+c*3)%26:13+(i*3+c)%13;
     insert(sid,new Grade(gid,sid,cid,exam==0?"MIDSEM":"ASSIGNMENT",marks,max,Rules.gradeFor(100*marks/max).letter()),Grade.class,gid);
    }
    kv.delete("timetable:"+sid);
   }
   kv.delete("course:"+cid);
  }
  for(String sid:students)for(Grade g:router.find(sid,Query.query(Criteria.where("studentId").is(sid).and("courseId").in("CDEMO101","CDEMO102","CDEMO103")),Grade.class))
   if(g.grade()==null||g.grade().isBlank())router.save(sid,new Grade(g.id(),g.studentId(),g.courseId(),g.exam(),g.marks(),g.maxMarks(),Rules.gradeFor(100*g.marks()/g.maxMarks()).letter()));
  for(int i=0;i<20;i++){
   String sid=students.get(i),nid="NSAMPLE-"+sid;
   insert(sid,new Notification(nid,sid,"EXAM","Semester assessments available","Your MIDSEM and ASSIGNMENT marks are available in Grades. Check your timetable and attendance for this semester.",false,now),Notification.class,nid);
  }
  for(String nid:List.of("NDEMOWELCOME","NDEMOATTENDANCE")){
   Notification n=router.forKey(DefaultDemoSetup.STUDENT).findById(nid,Notification.class);
   if(n!=null)router.save(n.studentId(),new Notification(n.id(),n.studentId(),n.type(),nid.equals("NDEMOWELCOME")?"Welcome to your semester":"Review your attendance",
    nid.equals("NDEMOWELCOME")?"Your courses, timetable, attendance and marks are ready. Your instructor can update assessments in Course Manager.":"Review your Computer Networks attendance and recovery guidance in Attendance.",n.read(),n.createdAt()));
  }
  kv.delete("timetable:"+DefaultDemoSetup.STUDENT);courses.invalidateLists();search.refresh();
  marker.put("complete",true);metadata.save(marker,"demo_setup");
  log.info("Academic sample v2 ready: 20 students, 3 courses, 60 registrations, 480 initial attendance records and 120 initial marks. Logins: office.admin@ssn.edu.in, faculty.cse@ssn.edu.in, aarav.2026@ssn.edu.in");
 }
 private String email(int i){return NAMES[i].split(" ")[0].toLowerCase(Locale.ROOT)+".2026@ssn.edu.in";}
 private void checkEmail(String email,String id){for(String shard:router.shardNames())for(Student s:router.template(shard).find(Query.query(Criteria.where("email").is(email)),Student.class))if(!s.id().equals(id))throw new IllegalStateException("Email already belongs to another account: "+email);}
 private void rename(String id,String name,String email){Student s=router.forKey(id).findById(id,Student.class);if(s==null)throw new IllegalStateException("Missing sample account "+id);router.save(id,new Student(id,name,email,s.department(),s.year(),s.passwordHash(),s.role(),s.createdAt()));kv.delete("person:"+id);}
 private <T>void insert(String key,T value,Class<T> type,String id){if(router.forKey(key).findById(id,type)==null)router.insert(key,value);}
}
