package com.sis.common;

/**
 * Source of unique numeric IDs. The URL shortener, student IDs and course IDs all depend on this interface.
 * The default implementation is a durable atomic counter in MongoDB; another generator can be
 * plugged in by implementing this interface and marking it @Primary.
 */
public interface IdSource {
    long nextId();

    /**
     * Next 3-digit ID number ("001" to "999") from an independent counter named by {@code sequence}
     * (e.g. "student", "course"). Used for the numeric part of student and course IDs such as S001 / C001.
     * Fails once the 999 numbers of a sequence are used up instead of silently producing a longer ID.
     */
    String nextThreeDigitId(String sequence);
}
