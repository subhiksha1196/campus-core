package com.sis.schema;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Collection "courses". SHARD KEY: id (the courseId, e.g. "C101"). semester example: "2026-ODD". */
@Document("courses")
public record Course(@Id String id, String code, String title, int credits, String department,
                     String semester, int capacity, String instructor) {
}