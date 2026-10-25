package com.sis.academic;

import java.time.Instant;

import com.sis.common.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Adds the 403 mapping next to the shared handler, using the same JSON error shape. */
@RestControllerAdvice
public class AcademicExceptionHandler {
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiError(403, HttpStatus.FORBIDDEN.getReasonPhrase(), e.getMessage(), Instant.now()));
    }
}
