package com.sis.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class RulesTest {
    @Test
    void gradeBoundaries() {
        assertEquals("O", Rules.gradeFor(90).letter());
        assertEquals("A+", Rules.gradeFor(89.99).letter());
        assertEquals("C", Rules.gradeFor(40).letter());
        assertEquals("F", Rules.gradeFor(39.99).letter());
        assertEquals(10, Rules.gradeFor(100).points());
    }

    @Test
    void percentHandlesZeroMax() {
        assertEquals(80.0, Rules.percent(40, 50), 1e-9);
        assertEquals(0.0, Rules.percent(10, 0), 1e-9);
    }

    @Test
    void attendanceAboveMinimumSaysHowManyCanBeMissed() {
        Rules.AttendanceStats s = Rules.attendanceStats(12, 14);   // 85.7%; 12/16 = 75%
        assertEquals("ok", s.status());
        assertEquals(2, s.canMiss());
        assertEquals(85.7, s.pct(), 1e-9);
    }

    @Test
    void attendanceBelowMinimumSaysHowManyToAttend() {
        Rules.AttendanceStats s = Rules.attendanceStats(9, 14);
        assertEquals("low", s.status());
        assertTrue((9.0 + s.need()) / (14 + s.need()) >= 0.75);
        assertTrue((9.0 + s.need() - 1) / (14 + s.need() - 1) < 0.75);
    }

    @Test
    void attendanceWithNoClasses() {
        Rules.AttendanceStats s = Rules.attendanceStats(0, 0);
        assertEquals("none", s.status());
        assertNull(s.pct());
    }

    @Test
    void onDutyCountsAsAttended() {
        assertTrue(Rules.attended("PRESENT"));
        assertTrue(Rules.attended("OD"));
        assertFalse(Rules.attended("ABSENT"));
    }

    @Test
    void gpaIsCreditWeighted() {
        assertEquals(7.5, Rules.gpa(List.of(new double[] {10, 4}, new double[] {5, 4})), 1e-9);
        assertNull(Rules.gpa(List.of()));
    }

    @Test
    void overlapDetection() {
        assertTrue(Rules.overlaps("09:00", "09:50", "09:30", "10:20"));
        assertFalse(Rules.overlaps("09:00", "09:50", "09:50", "10:40"));   // back to back is fine
        assertFalse(Rules.overlaps("09:00", "09:50", "13:30", "14:20"));
    }
}
