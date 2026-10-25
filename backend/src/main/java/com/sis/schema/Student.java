package com.sis.schema;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Collection "students". SHARD KEY: id (the studentId, e.g. "S1042").
 * role: STUDENT, FACULTY or ADMIN. passwordHash must be a hash (e.g. BCrypt), never the password.
 */
@Document("students")
public record Student(@Id String id, String name, String email, String department, int year,
                      String passwordHash, String role, Instant createdAt) {
}