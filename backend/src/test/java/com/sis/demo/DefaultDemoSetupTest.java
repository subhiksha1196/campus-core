package com.sis.demo;
import com.mongodb.client.*;
import com.sis.academic.*;
import com.sis.auth.Passwords;
import com.sis.schema.*;
import com.sis.sharding.ShardRouter;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DefaultDemoSetupTest {
 MongoServer server; MongoClient client; MongoTemplate metadata; ShardRouter router; DefaultDemoSetup setup;
 @BeforeEach void start() {
  server=new MongoServer(new MemoryBackend()); var address=server.bind();
  client=MongoClients.create("mongodb://localhost:"+address.getPort()); metadata=new MongoTemplate(client,"sis");
  router=new ShardRouter(client,metadata.getConverter(),metadata,List.of("shard0","shard1","shard2"),150);
  setup=create("LocalDemo2026!");
 }
 DefaultDemoSetup create(String password) {return new DefaultDemoSetup(router,metadata,mock(AcademicSeeder.class),mock(CourseService.class),mock(SearchService.class),password);}
 @AfterEach void stop() {if(client!=null)client.close();if(server!=null)server.shutdownNow();}
 <T> long count(Class<T> type) {return router.shardNames().stream().mapToLong(s->router.template(s).count(new Query(),type)).sum();}
 @Test void createsConnectedAccountsAndPreservesChangesOnRestart() {
  setup.onReady();
  var auth=new com.sis.auth.AuthService(router,metadata,mock(com.sis.kv.KvStore.class),mock(com.sis.common.IdSource.class),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  for(String id:List.of(DefaultDemoSetup.ADMIN,DefaultDemoSetup.TEACHER,DefaultDemoSetup.STUDENT)) {
   Student s=router.forKey(id).findById(id,Student.class); assertNotNull(s);
   assertNotNull(auth.login(s.email(),"LocalDemo2026!").get("token"));
   assertTrue(Passwords.matches("LocalDemo2026!",s.passwordHash()));assertFalse(Passwords.matches("wrong-password",s.passwordHash()));
  }
  assertEquals("ADMIN",router.forKey(DefaultDemoSetup.ADMIN).findById(DefaultDemoSetup.ADMIN,Student.class).role());
  assertEquals("FACULTY",router.forKey(DefaultDemoSetup.TEACHER).findById(DefaultDemoSetup.TEACHER,Student.class).role());
  assertEquals("STUDENT",router.forKey(DefaultDemoSetup.STUDENT).findById(DefaultDemoSetup.STUDENT,Student.class).role());
  assertEquals(3,count(Course.class)); assertEquals(3,count(Registration.class)); assertEquals(24,count(Attendance.class));
  assertEquals(6,count(Grade.class));assertEquals(3,count(TimetableEntry.class));
  for(String shard:router.shardNames())for(Course c:router.template(shard).findAll(Course.class))assertEquals(DefaultDemoSetup.TEACHER,c.instructor());
  String sid=DefaultDemoSetup.STUDENT; Registration r=router.forKey(sid).findById("RDEMO0",Registration.class);
  router.save(sid,new Registration(r.id(),r.studentId(),r.courseId(),r.semester(),"DROPPED",r.registeredAt()));
  setup.onReady();assertEquals(3,count(Student.class));assertEquals(24,count(Attendance.class));
  assertEquals("DROPPED",router.forKey(sid).findById("RDEMO0",Registration.class).status());
 }
 @Test void refusesToOverwriteUnrelatedAccount() {
  router.insert("S123",new Student("S123","Existing User","demo.admin@ssn.edu.in","CSE",2,Passwords.hash("ExistingPassword!"),"STUDENT",java.time.Instant.now()));
  assertThrows(IllegalStateException.class,setup::onReady);assertEquals(1,count(Student.class));assertEquals(0,count(Course.class));
  assertEquals("STUDENT",router.forKey("S123").findById("S123",Student.class).role());
 }
 @Test void rejectsInvalidPasswordBeforeWriting() {
  assertThrows(IllegalArgumentException.class,create("short")::onReady);assertEquals(0,count(Student.class));
 }
}
