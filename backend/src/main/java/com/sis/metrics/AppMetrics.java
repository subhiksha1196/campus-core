package com.sis.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Custom Micrometer meters for Person 4's modules (also visible at /actuator/metrics and /actuator/prometheus). */
@Component
public class AppMetrics {
    public final Timer redirectCached;
    public final Timer redirectBaseline;
    public final Timer crawlFetch;
    public final Timer notificationWrite;
    public final Counter cacheHits;
    public final Counter cacheMisses;
    public final Counter notificationsCreated;

    public AppMetrics(MeterRegistry r) {
        redirectCached = Timer.builder("sis.shortener.redirect").tag("variant", "cached")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        redirectBaseline = Timer.builder("sis.shortener.redirect").tag("variant", "baseline")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        crawlFetch = Timer.builder("sis.crawler.fetch")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        notificationWrite = Timer.builder("sis.notification.write")
                .publishPercentiles(0.5, 0.95, 0.99).register(r);
        cacheHits = Counter.builder("sis.shortener.cache").tag("result", "hit").register(r);
        cacheMisses = Counter.builder("sis.shortener.cache").tag("result", "miss").register(r);
        notificationsCreated = Counter.builder("sis.notification.created").register(r);
    }
}
