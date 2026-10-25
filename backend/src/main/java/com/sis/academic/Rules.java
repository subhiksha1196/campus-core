package com.sis.academic;

import java.util.List;

/**
 * Academic rules as pure functions (no database, no Spring), so they are easy to unit test.
 * Attendance status OD (on duty) counts as present.
 */
public final class Rules {
    public static final int MAX_CREDITS = 24;
    public static final double ATTENDANCE_MIN = 75.0;
    public static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private Rules() {
    }

    /** One row of the grade scale: minimum percentage, letter and grade points. */
    public record GradeBand(int min, String letter, int points) {
    }

    public static final List<GradeBand> SCALE = List.of(
            new GradeBand(90, "O", 10), new GradeBand(80, "A+", 9), new GradeBand(70, "A", 8),
            new GradeBand(60, "B+", 7), new GradeBand(50, "B", 6), new GradeBand(40, "C", 5),
            new GradeBand(0, "F", 0));

    public static GradeBand gradeFor(double percent) {
        for (GradeBand b : SCALE) {
            if (percent >= b.min()) {
                return b;
            }
        }
        return SCALE.get(SCALE.size() - 1);
    }

    /** Marks as a percentage of the maximum (0 when the maximum is not positive). */
    public static double percent(double marks, double max) {
        return max > 0 ? marks / max * 100.0 : 0.0;
    }

    /**
     * Attendance summary. status: ok, low, or none (no classes yet).
     * canMiss = classes that can still be skipped and stay at or above the minimum.
     * need = classes that must be attended in a row to get back to the minimum.
     */
    public record AttendanceStats(int present, int total, Double pct, String status, int canMiss, int need) {
    }

    public static AttendanceStats attendanceStats(int present, int total) {
        double r = ATTENDANCE_MIN / 100.0;
        if (total <= 0) {
            return new AttendanceStats(0, 0, null, "none", 0, 0);
        }
        double pct = Math.round(1000.0 * present / total) / 10.0;
        if ((double) present / total >= r) {
            int canMiss = Math.max(0, (int) Math.floor(present / r - total + 1e-9));
            return new AttendanceStats(present, total, pct, "ok", canMiss, 0);
        }
        int need = (int) Math.ceil((r * total - present) / (1 - r) - 1e-9);
        return new AttendanceStats(present, total, pct, "low", 0, need);
    }

    /** True for statuses that count as attended. */
    public static boolean attended(String status) {
        return "PRESENT".equals(status) || "OD".equals(status);
    }

    /** Credit-weighted GPA over {gradePoints, credits} pairs, or null when there are none. */
    public static Double gpa(List<double[]> pointsAndCredits) {
        double credits = 0;
        double sum = 0;
        for (double[] pc : pointsAndCredits) {
            sum += pc[0] * pc[1];
            credits += pc[1];
        }
        return credits == 0 ? null : Math.round(sum / credits * 100.0) / 100.0;
    }

    /** Two "HH:mm" time ranges overlap (strings compare correctly because they are zero padded). */
    public static boolean overlaps(String aStart, String aEnd, String bStart, String bEnd) {
        return aStart.compareTo(bEnd) < 0 && bStart.compareTo(aEnd) < 0;
    }
}
