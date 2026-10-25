package com.sis.schema;

import java.time.Instant;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Collection "registrations". SHARD KEY: studentId (all of a student's registrations on one shard).
 * status: REGISTERED or DROPPED. Unique per (studentId, courseId, semester).
 */
@Document("registrations")
public record Registration(@Id String id, String studentId, String courseId, String semester,
                           String status, Instant registeredAt) {

    public static Registration create(String studentId, String courseId, String semester) {
        return new Registration(new ObjectId().toHexString(), studentId, courseId, semester,
                "REGISTERED", Instant.now());
    }
}