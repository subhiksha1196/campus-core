package com.sis.common;

import io.micrometer.core.instrument.MeterRegistry;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

/** Durable atomic counter. Name retained for compatibility; Mongo owns ID durability, Redis is only cache. */
@Component
public class RedisCounterIdSource implements IdSource {
    private final MongoTemplate mongo; private final MeterRegistry metrics;
    public RedisCounterIdSource(MongoTemplate mongo,MeterRegistry metrics) {
        this.mongo=mongo;this.metrics=metrics;
        metrics.counter("sis.id.collisions");
    }
    public String nextThreeDigitId(String sequence) {
        Document counter=mongo.findAndModify(Query.query(Criteria.where("_id").is("three-digit:"+sequence)),new Update().inc("value",1L),
            FindAndModifyOptions.options().upsert(true).returnNew(true),Document.class,"id_sequences");
        if(counter==null) throw new IllegalStateException("ID allocation failed");
        long n=((Number)counter.get("value")).longValue();
        if(n>999) throw new IllegalStateException("3-digit ID range exhausted for '"+sequence+"' (001-999)");
        metrics.counter("sis.id.generated").increment();
        return String.format("%03d",n);
    }
    public long nextId() {
        long start=System.nanoTime();
        Document counter=mongo.findAndModify(Query.query(Criteria.where("_id").is("global")),new Update().inc("value",1L),
            FindAndModifyOptions.options().upsert(true).returnNew(true),Document.class,"id_sequences");
        if(counter==null) throw new IllegalStateException("ID allocation failed");
        metrics.counter("sis.id.generated").increment();
        metrics.timer("sis.id.generation").record(System.nanoTime()-start,java.util.concurrent.TimeUnit.NANOSECONDS);
        // Reserved high range avoids IDs previously allocated by the original Redis implementation.
        return Math.addExact(1_000_000_000_000L,((Number)counter.get("value")).longValue());
    }
}
