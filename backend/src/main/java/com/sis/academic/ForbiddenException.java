package com.sis.academic;

/** The caller is known but not allowed to do this (HTTP 403, see AcademicExceptionHandler). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
