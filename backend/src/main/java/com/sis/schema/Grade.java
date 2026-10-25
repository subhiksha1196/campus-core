package com.sis.schema;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Collection "grades". SHARD KEY: studentId. exam example: "MIDSEM". Unique per (studentId, courseId, exam). */
@Document("grades")
public record Grade(@Id String id, String studentId, String courseId, String exam,
                    double marks, double maxMarks, String grade) {

    public static Grade create(String studentId, String courseId, String exam,
                               double marks, double maxMarks, String grade) {
        return new Grade(new ObjectId().toHexString(), studentId, courseId, exam, marks, maxMarks, grade);
    }
}