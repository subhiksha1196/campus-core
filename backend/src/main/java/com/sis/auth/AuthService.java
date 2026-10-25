package com.sis.auth;

import com.sis.schema.Student;
import com.sis.sharding.ShardRouter;
import com.sis.common.IdSource;
import com.sis.kv.KvStore;
import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AuthService {
    private final ShardRouter router;
    private final MongoTemplate mongo;
    private final KvStore kv;
    private final IdSource ids;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    private final String dummy = Passwords.hash("dummy-password-for-timing");
    public AuthService(ShardRouter router, MongoTemplate mongo, KvStore kv, IdSource ids, io.micrometer.core.instrument.MeterRegistry metrics) {
        this.metrics=metrics;
        this.router=router; this.mongo=mongo; this.kv=kv; this.ids=ids;
    }
    public static String email(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    public Student student(String id) { return router.forKey(id).findById(id, Student.class); }
    public Student byEmail(String email) {
        Student found = null;
        for (String shard : router.shardNames()) {
            Student s = router.template(shard).findOne(Query.query(Criteria.where("email").is(email(email))), Student.class);
            if(s != null) found=s;
        }
        return found;
    }
    public Map<String,Object> profile(Student s) {
        return Map.of("id",s.id(),"name",s.name(),"email",s.email(),"department",s.department(),"year",s.year(),"role",s.role());
    }
    public Student register(String name,String email,String department,int year,String password) {
        email=email(email);
        if(byEmail(email)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already registered");
        String id="S"+ids.nextThreeDigitId("student");
        // Central, durable unique email reservation: shard-local indexes cannot enforce global uniqueness.
        try { mongo.insert(new Document("_id",email).append("studentId",id),"auth_emails"); }
        catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Email already registered"); }
        Student s=new Student(id,name.trim(),email,department.trim(),year,Passwords.hash(password),"STUDENT",Instant.now());
        // Keep reservation on uncertain write failures, preventing a second account with the same email.
        try { router.insert(id,s); }
        catch (DuplicateKeyException e) { metrics.counter("sis.id.collisions").increment(); throw new IllegalStateException("Generated ID collision; restore the ID counter before retrying",e); }
        return s;
    }
    public Map<String,Object> login(String email,String password) {
        Student s=byEmail(email);
        if(!Passwords.matches(password,s==null?dummy:s.passwordHash()) || s==null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password");
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        kv.put("session:"+token,s.id(),Duration.ofHours(8));
        return Map.of("token",token,"user",profile(s),"expiresIn",28800);
    }
    public Student authenticate(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        // Control-plane authentication remains available during a simulated shard-down experiment,
        // so an administrator can restore the shard. A real Mongo outage still fails closed.
        return kv.get("session:"+token).map(id -> router.peek(router.shardFor(id)).findById(id,Student.class)).orElse(null);
    }
    public void logout(String token) { if(token!=null) kv.delete("session:"+token); }
    public Student update(Student s,String name,String department,int year) {
        if("STUDENT".equals(s.role()) && year<1) throw new IllegalArgumentException("Student year must be between 1 and 8");
        Student updated=new Student(s.id(),name.trim(),s.email(),department.trim(),year,s.passwordHash(),s.role(),s.createdAt());
        router.save(s.id(),updated); kv.delete("person:"+s.id()); return updated;
    }
}
