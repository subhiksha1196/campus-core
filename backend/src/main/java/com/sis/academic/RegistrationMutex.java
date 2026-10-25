package com.sis.academic;

import java.util.UUID;
import java.util.function.Supplier;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Durable global mutex: serializes enrollment checks and writes across app instances.
 * No expiring lease: a stopped owner cannot resume and violate capacity after its lease expires.
 * Following a crash an operator must clear the lock after stopping all writers.
 */
@Component
public class RegistrationMutex {
    private final MongoTemplate mongo;
    public RegistrationMutex(MongoTemplate mongo) { this.mongo=mongo; }
    public <T> T run(Supplier<T> work) {
        String owner=UUID.randomUUID().toString();
        try { mongo.insert(new Document("_id","registration").append("owner",owner),"operation_locks"); }
        catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"Another registration is in progress. Please retry."); }
        try { return work.get(); }
        finally { mongo.remove(Query.query(Criteria.where("_id").is("registration").and("owner").is(owner)),"operation_locks"); }
    }
}
