package com.sis.demo;
import com.mongodb.client.*;
import com.sis.academic.*;
import com.sis.auth.*;
import com.sis.common.IdSource;
import com.sis.kv.KvStore;
import com.sis.schema.*;
import com.sis.sharding.ShardRouter;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AcademicSampleUpgradeTest {
 MongoServer server;MongoClient client;MongoTemplate meta;ShardRouter router;DefaultDemoSetup original;AcademicSampleUpgrade upgrade;KvStore kv;CourseService courses;SearchService search;
 @BeforeEach void start(){server=new MongoServer(new MemoryBackend());var address=server.bind();client=MongoClients.create("mongodb://localhost:"+address.getPort());meta=new MongoTemplate(client,"sis");router=new ShardRouter(client,meta.getConverter(),meta,List.of("shard0","shard1","shard2"),150);kv=mock(KvStore.class);courses=mock(CourseService.class);search=mock(SearchService.class);original=new DefaultDemoSetup(router,meta,mock(AcademicSeeder.class),courses,search,"LocalDemo2026!");AtomicLong counter=new AtomicLong(10000);upgrade=new AcademicSampleUpgrade(router,meta,original,new IdSource(){public long nextId(){return counter.incrementAndGet();}public String nextThreeDigitId(String s){return String.format("%03d",counter.incrementAndGet()-10000);}},kv,courses,search,"LocalDemo2026!");}
 @AfterEach void stop(){client.close();server.shutdownNow();}
 <T>long count(Class<T> type){return router.shardNames().stream().mapToLong(s->router.template(s).count(new Query(),type)).sum();}
 @Test void upgradesExistingDatasetAndAuthenticatesRealNames(){
  original.onReady();upgrade.onReady();assertEquals(22,count(Student.class));assertEquals(60,count(Registration.class));assertEquals(480,count(Attendance.class));assertEquals(120,count(Grade.class));
  var auth=new AuthService(router,meta,kv,mock(IdSource.class),new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
  for(String email:List.of("office.admin@ssn.edu.in","faculty.cse@ssn.edu.in","aarav.2026@ssn.edu.in"))assertNotNull(auth.login(email,"LocalDemo2026!").get("token"));
  for(String shard:router.shardNames())for(Student s:router.template(shard).findAll(Student.class)){assertFalse(s.name().contains("Demo"));assertFalse(s.email().contains("demo"));}
  for(String shard:router.shardNames())for(Course c:router.template(shard).findAll(Course.class)){assertFalse(c.title().contains("Demo"));assertFalse(c.code().contains("DEMO"));}
  assertEquals(20,router.shardNames().stream().mapToLong(s->router.template(s).count(Query.query(Criteria.where("courseId").is("CDEMO101")),Registration.class)).sum());
  String sid=DefaultDemoSetup.STUDENT;Grade g=router.forKey(sid).findById("GDEMO0E0",Grade.class);router.save(sid,new Grade(g.id(),sid,g.courseId(),g.exam(),49,50,""));
  upgrade.onReady();assertEquals(22,count(Student.class));assertEquals(480,count(Attendance.class));assertEquals(49,router.forKey(sid).findById(g.id(),Grade.class).marks());
 }
 @Test void worksOnFreshDatabase(){upgrade.onReady();assertEquals(22,count(Student.class));assertEquals(60,count(Registration.class));}
}
