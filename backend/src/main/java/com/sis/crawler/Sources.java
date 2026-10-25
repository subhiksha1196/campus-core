package com.sis.crawler;

import java.util.List;

/** The three simulated external sources. */
public final class Sources {
    public static final List<String> ALL = List.of("portal", "scholarships", "internships");

    private Sources() {
    }

    /** Notification type used when new listings from a source are announced. */
    public static String notificationType(String source) {
        return switch (source) {
            case "scholarships" -> "SCHOLARSHIP";
            case "internships" -> "INTERNSHIP";
            default -> "GENERAL";
        };
    }
}
