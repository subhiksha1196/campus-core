package com.sis.common;

import java.time.Instant;

public record ApiError(int status, String error, String message, Instant timestamp) {
}
