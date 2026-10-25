package com.sis.sharding;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Thrown when a request needs a shard that is down. Returned to clients as HTTP 503. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ShardUnavailableException extends RuntimeException {
    public ShardUnavailableException(String shard) {
        super("Shard " + shard + " is unavailable");
    }
}