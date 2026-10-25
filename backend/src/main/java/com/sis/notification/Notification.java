package com.sis.notification;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** studentId is just a string here, so this module does not depend on the student-profile module. */
@Document("notifications")
@CompoundIndex(name = "student_created", def = "{'studentId': 1, 'createdAt': -1}")
public record Notification(@Id String id, String studentId, String type, String title, String message,
                           boolean read, Instant createdAt) {
}
