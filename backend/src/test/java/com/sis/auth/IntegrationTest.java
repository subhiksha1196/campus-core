package com.sis.auth;

import com.sis.SisApplication;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: real Spring HTTP stack and Redis; Mongo emulator only when explicitly requested. */
@SpringBootTest(classes=SisApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties={"sis.auth.demo-password=IntegrationDemo123!","sis.academic.seed-students=20","sis.rate-limit.api-per-minute=10000"})
@EnabledIfEnvironmentVariable(named="SIS_INTEGRATION",matches="true")
class IntegrationTest {
 static MongoServer emulator;
 @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
  if("true".equals(System.getenv("SIS_MONGO_EMULATOR"))) {
   emulator=new MongoServer(new MemoryBackend());var address=emulator.bind();
   r.add("spring.data.mongodb.uri",()->"mongodb://localhost:"+address.getPort()+"/sis_test");
  }
 }
 @Autowired TestRestTemplate http;
 @LocalServerPort int port;
 String token;
 @BeforeEach void httpClient() { http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()); }
 ResponseEntity<Map> call(String method,String path,Object body) {
  HttpHeaders h=new HttpHeaders(); if(token!=null)h.setBearerAuth(token);
  return http.exchange(path,HttpMethod.valueOf(method),new HttpEntity<>(body,h),Map.class);
 }
 Map login(String email,String password) {
  var response=call("POST","/api/auth/login",Map.of("email",email,"password",password));
  assertEquals(200,response.getStatusCode().value(),response.toString());
  token=(String)response.getBody().get("token");return (Map)response.getBody().get("user");
 }
 @Test void completeAuthenticatedWorkflow() {
  assertEquals(401,call("GET","/api/academic/courses",null).getStatusCode().value());
  String email="integration-"+UUID.randomUUID()+"@example.com";
  Map<String,Object> registration=Map.of("name","Integration Student","email",email,"department","CSE","year",2,"password","StudentPassword123!");
  var created=call("POST","/api/auth/register",registration);
  assertEquals(201,created.getStatusCode().value(),created.toString());
  assertFalse(created.getBody().containsKey("passwordHash"));
  assertEquals(409,call("POST","/api/auth/register",registration).getStatusCode().value());
  assertEquals(401,call("POST","/api/auth/login",Map.of("email",email,"password","wrong")).getStatusCode().value());
  Map user=login(email,"StudentPassword123!");String sid=(String)user.get("id");
  assertEquals("STUDENT",user.get("role"));
  assertEquals(200,call("GET","/api/auth/me",null).getStatusCode().value());
  assertEquals(200,call("PUT","/api/auth/me",Map.of("name","Updated Student","department","IT","year",3)).getStatusCode().value());
  assertEquals("Updated Student",call("GET","/api/auth/me",null).getBody().get("name"));
  assertEquals(403,call("POST","/api/shards/admin/down?name=shard0",null).getStatusCode().value());
  assertEquals(403,call("GET","/api/academic/students/someone-else/grades",null).getStatusCode().value());
  assertEquals(403,call("GET","/api/notifications/unread-count?studentId=someone-else",null).getStatusCode().value());
  var courseResponse=call("GET","/api/academic/courses",null);assertEquals(200,courseResponse.getStatusCode().value(),courseResponse.toString());
  List<Map> courses=(List<Map>)courseResponse.getBody().get("courses");
  String cid=null;
  for(Map c:courses) {
   var enrolled=call("POST","/api/academic/registrations",Map.of("studentId",sid,"courseId",c.get("id")));
   if(enrolled.getStatusCode().value()==200){cid=(String)c.get("id");break;}
  }
  assertNotNull(cid,"At least one demo course must accept registration");
  assertEquals(200,call("GET","/api/academic/students/"+sid+"/enrollments",null).getStatusCode().value());
  assertEquals(200,call("GET","/api/academic/timetable?studentId="+sid,null).getStatusCode().value());
  assertEquals(200,call("GET","/api/academic/students/"+sid+"/grades",null).getStatusCode().value());
  assertEquals(200,call("DELETE","/api/academic/registrations/"+sid+"/"+cid,null).getStatusCode().value());
  String oldToken=token;assertEquals(200,call("POST","/api/auth/logout",null).getStatusCode().value());
  token=oldToken;assertEquals(401,call("GET","/api/auth/me",null).getStatusCode().value());
  login("admin@ssn.edu.in","IntegrationDemo123!");
  assertEquals(200,call("GET","/api/metrics/summary",null).getStatusCode().value());
  assertEquals(200,call("POST","/api/academic/search/rebuild",null).getStatusCode().value());
  var status=call("GET","/api/shards/admin/status",null);assertEquals(200,status.getStatusCode().value());
  assertEquals(200,call("POST","/api/shards/admin/add?name=integration_shard",null).getStatusCode().value());
  assertEquals(200,call("GET","/api/auth/me",null).getStatusCode().value());
  assertEquals(200,call("POST","/api/shards/admin/remove?name=integration_shard",null).getStatusCode().value());
  assertEquals(200,call("POST","/api/shards/admin/down?name=shard0",null).getStatusCode().value());
  assertEquals(200,call("POST","/api/shards/admin/up?name=shard0",null).getStatusCode().value());
  var notification=call("POST","/api/notifications",Map.of("studentId",sid,"title","Test","message","Academic notice","type","GENERAL"));
  assertEquals(201,notification.getStatusCode().value());
  String nid=(String)notification.getBody().get("id");
  var url=call("POST","/api/urls",Map.of("url","https://university.example/test"));
  assertEquals(201,url.getStatusCode().value());
  // Instructor workflow on a course actually owned by the faculty account.
  Map faculty=login("venkatesan@ssn.edu.in","IntegrationDemo123!");
  List<Map> owned=(List<Map>)call("GET","/api/academic/courses?instructorId="+faculty.get("id"),null).getBody().get("courses");
  assertFalse(owned.isEmpty());String teaching=(String)owned.get(0).get("id");
  assertEquals(200,call("GET","/api/academic/courses/"+teaching+"/attendance",null).getStatusCode().value());
  assertEquals(200,call("POST","/api/academic/courses/"+teaching+"/attendance",Map.of("date",java.time.LocalDate.now().toString(),"absent",List.of(),"onDuty",List.of())).getStatusCode().value());
  assertEquals(200,call("POST","/api/academic/courses/"+teaching+"/marks",Map.of("exam","INTEGRATION","maxMarks",100,"marks",Map.of())).getStatusCode().value());
  login(email,"StudentPassword123!");
  assertEquals(204,call("PATCH","/api/notifications/"+nid+"/read",null).getStatusCode().value());
  assertEquals(403,call("POST","/api/academic/courses/"+teaching+"/marks",Map.of("exam","TEST","maxMarks",100,"marks",Map.of())).getStatusCode().value());
  boolean rejected=false;
  for(int i=0;i<25;i++) if(call("POST","/api/auth/login",Map.of("email",email,"password","wrong")).getStatusCode().value()==429){rejected=true;break;}
  assertTrue(rejected,"Repeated login attempts must be rate limited");
 }
}
