package com.sis.auth;

import com.sis.auth.TokenBucketRateLimiter.Decision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Rate limit policy on top of {@link TokenBucketRateLimiter}: one scope for login/register ("auth", per IP)
 * and one for every other API call ("api", per signed-in account). The per-minute settings keep their
 * meaning as the sustained rate (refill = limit / 60 tokens per second). The burst is how many requests
 * may arrive at once and defaults to the per-minute limit.
 */
@Component
public class RateLimiter {
    private final TokenBucketRateLimiter buckets;
    private final int apiCapacity, authCapacity;
    private final double apiRefillPerSecond, authRefillPerSecond;

    public RateLimiter(TokenBucketRateLimiter buckets,
                       @Value("${sis.rate-limit.api-per-minute:600}") int apiPerMinute,
                       @Value("${sis.rate-limit.auth-per-minute:20}") int authPerMinute,
                       @Value("${sis.rate-limit.api-burst:0}") int apiBurst,
                       @Value("${sis.rate-limit.auth-burst:0}") int authBurst) {
        this.buckets = buckets;
        apiPerMinute = Math.max(1, apiPerMinute);
        authPerMinute = Math.max(1, authPerMinute);
        this.apiCapacity = apiBurst > 0 ? apiBurst : apiPerMinute;
        this.authCapacity = authBurst > 0 ? authBurst : authPerMinute;
        this.apiRefillPerSecond = apiPerMinute / 60.0;
        this.authRefillPerSecond = authPerMinute / 60.0;
    }

    /** Takes one token. Throws IllegalStateException when Redis cannot be reached (the filter answers 503). */
    public Decision allow(String key, boolean auth) {
        return auth ? buckets.acquire("auth", key, authCapacity, authRefillPerSecond, 1)
                    : buckets.acquire("api", key, apiCapacity, apiRefillPerSecond, 1);
    }
}
