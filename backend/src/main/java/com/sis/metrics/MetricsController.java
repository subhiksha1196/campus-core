package com.sis.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.sis.crawler.CrawlRunRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One JSON summary of the component-specific metrics, used by the React "Metrics" tab. */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {
    private final AppMetrics m;
    private final MeterRegistry registry;
    private final CrawlRunRepository runs;

    public MetricsController(AppMetrics m, MeterRegistry registry, CrawlRunRepository runs) {
        this.m = m;
        this.registry = registry;
        this.runs = runs;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> redirect = new LinkedHashMap<>();
        redirect.put("cached", timerStats(m.redirectCached));
        redirect.put("baseline", timerStats(m.redirectBaseline));
        out.put("redirectLatency", redirect);

        double hits = m.cacheHits.count();
        double misses = m.cacheMisses.count();
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hits", (long) hits);
        cache.put("misses", (long) misses);
        cache.put("hitRatio", (hits + misses) == 0 ? 0.0 : round(hits / (hits + misses)));
        out.put("cache", cache);

        out.put("crawlFetchLatency", timerStats(m.crawlFetch));

        Map<String, Object> notif = new LinkedHashMap<>();
        notif.put("created", (long) m.notificationsCreated.count());
        notif.put("writeLatency", timerStats(m.notificationWrite));
        out.put("notifications", notif);

        out.put("recentCrawlRuns", runs.findTop20ByOrderByStartedAtDesc().stream().limit(5).toList());
        out.put("resources", resources());
        double generated=registry.counter("sis.id.generated").count();
        double uptime=java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()/1000.0;
        double allowed=registry.find("sis.ratelimit.requests").tag("result","allowed").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        double rejected=registry.find("sis.ratelimit.requests").tag("result","rejected").counters().stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        out.put("person1",Map.of("idsGenerated",generated,"idGenerationRate",generated/Math.max(uptime,1),
            "collisionCount",registry.counter("sis.id.collisions").count(),"requestsAllowed",allowed,"requestsRejected",rejected,
            "rejectionRate",rejected/Math.max(allowed+rejected,1)));

        return out;
    }

    private Map<String, Object> resources() {
        Map<String, Object> r = new LinkedHashMap<>();
        Gauge cpu = registry.find("process.cpu.usage").gauge();
        r.put("processCpuPercent", cpu == null ? 0.0 : round(cpu.value() * 100));
        double heapBytes = registry.find("jvm.memory.used").tag("area", "heap").gauges().stream()
                .mapToDouble(Gauge::value).sum();
        r.put("jvmHeapUsedMb", round(heapBytes / 1048576.0));
        return r;
    }

    private Map<String, Object> timerStats(Timer t) {
        HistogramSnapshot snap = t.takeSnapshot();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("count", t.count());
        s.put("meanMs", round(t.mean(TimeUnit.MILLISECONDS)));
        s.put("maxMs", round(t.max(TimeUnit.MILLISECONDS)));
        for (ValueAtPercentile p : snap.percentileValues()) {
            s.put("p" + Math.round(p.percentile() * 100) + "Ms", round(p.value(TimeUnit.MILLISECONDS)));
        }
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
