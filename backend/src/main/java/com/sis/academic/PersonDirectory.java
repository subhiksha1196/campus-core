package com.sis.academic;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.sis.academic.Dtos.PersonRef;
import com.sis.common.NotFoundException;
import com.sis.kv.KvStore;
import com.sis.schema.Student;
import com.sis.sharding.ShardRouter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Looks people up by id (students, faculty and admins share the "students" collection). Never exposes passwordHash. */
@Service
public class PersonDirectory {
    private static final Duration TTL = Duration.ofMinutes(2);

    private final ShardRouter router;
    private final ShardScan scan;
    private final KvStore kv;

    public PersonDirectory(ShardRouter router, ShardScan scan, KvStore kv) {
        this.router = router;
        this.scan = scan;
        this.kv = kv;
    }

    static PersonRef ref(Student s) {
        return new PersonRef(s.id(), s.name(), s.department(), s.year(), s.role() == null ? "STUDENT" : s.role());
    }

    /** One lookup on the shard that owns the id; null when there is no such person. */
    public PersonRef find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return kv.cached("person:" + id, TTL, PersonRef.class, () -> {
            Student s = router.forKey(id).findById(id, Student.class);
            return s == null ? null : ref(s);
        });
    }

    public PersonRef require(String id) {
        PersonRef p = find(id);
        if (p == null) {
            throw new NotFoundException("Student or staff member " + id + " not found");
        }
        return p;
    }

    /** Many ids at once: one query per shard instead of one per id. */
    public Map<String, PersonRef> byIds(Collection<String> ids) {
        Map<String, PersonRef> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        Query q = new Query(Criteria.where("id").in(new HashSet<>(ids)));
        q.fields().include("name", "department", "year", "role");
        List<Student> found = scan.findAll(q, Student.class);
        for (Student s : found) {
            out.put(s.id(), ref(s));
        }
        return out;
    }
}
