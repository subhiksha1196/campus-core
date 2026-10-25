package com.sis.schema;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Collection "timetable". SHARD KEY: courseId (a course's slots live together).
 * day: MON..SUN. startTime/endTime are "HH:mm" strings. A student's timetable = their registrations
 * (student's shard) joined with these entries (course's shard), then cached in the KvStore.
 */
@Document("timetable")
public record TimetableEntry(@Id String id, String courseId, String day, String startTime,
                             String endTime, String room) {

    public static TimetableEntry create(String courseId, String day, String startTime,
                                        String endTime, String room) {
        return new TimetableEntry(new ObjectId().toHexString(), courseId, day, startTime, endTime, room);
    }
}