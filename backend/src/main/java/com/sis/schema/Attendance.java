package com.sis.schema;

import java.time.LocalDate;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Collection "attendance". SHARD KEY: studentId.
 * status: PRESENT, ABSENT or OD. Unique per (studentId, courseId, date).
 */
@Document("attendance")
public record Attendance(@Id String id, String studentId, String courseId, LocalDate date, String status) {

    public static Attendance create(String studentId, String courseId, LocalDate date, String status) {
        return new Attendance(new ObjectId().toHexString(), studentId, courseId, date, status);
    }
}