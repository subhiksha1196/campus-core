package com.sis.academic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Search response time and suggestion feedback. Every search is recorded twice: in Micrometer (so it shows up
 * at /actuator/prometheus as sis.academic.search) and in a short in-memory window used for the percentiles
 * on the Search page.
 */
@Component
public class AcademicMetrics {
    private static final int WINDOW = 5000;

    private final MeterRegistry registry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private final Map<String, ArrayDeque<Double>> samples = new HashMap<>();
    private final ArrayDeque<Integer> ranks = new ArrayDeque<>();
    private long searches;
    private final long startedAt = System.currentTimeMillis();
    private volatile Map<String, Object> benchmark;

    public AcademicMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSearch(String type, long nanos) {
        timers.computeIfAbsent(type, t -> Timer.builder("sis.academic.search").tag("type", t)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry)).record(nanos, TimeUnit.NANOSECONDS);
        synchronized (lock) {
            ArrayDeque<Double> d = samples.computeIfAbsent(type, k -> new ArrayDeque<>());
            d.addLast(nanos / 1_000_000.0);
            if (d.size() > WINDOW) {
                d.removeFirst();
            }
            searches++;
        }
    }

    /** The position (1 = first) of the suggestion a person actually picked. */
    public void recordSelection(int rank) {
        synchronized (lock) {
            ranks.addLast(rank);
            if (ranks.size() > WINDOW) {
                ranks.removeFirst();
            }
        }
    }

    public void setBenchmark(Map<String, Object> result) {
        this.benchmark = result;
    }

    public void reset() {
        synchronized (lock) {
            samples.clear();
            ranks.clear();
            searches = 0;
        }
    }

    public Map<String, Object> snapshot(Map<String, Object> indexSizes) {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (lock) {
            List<Double> all = new ArrayList<>();
            Map<String, Object> byType = new LinkedHashMap<>();
            for (Map.Entry<String, ArrayDeque<Double>> e : samples.entrySet()) {
                all.addAll(e.getValue());
                byType.put(e.getKey(), summarize(new ArrayList<>(e.getValue())));
            }
            out.put("searchLatency", summarize(all));
            out.put("searchLatencyByType", byType);

            Map<String, Object> sel = new LinkedHashMap<>();
            sel.put("count", ranks.size());
            if (!ranks.isEmpty()) {
                double avg = ranks.stream().mapToInt(i -> i).average().orElse(0);
                long first = ranks.stream().filter(r -> r == 1).count();
                long top3 = ranks.stream().filter(r -> r <= 3).count();
                sel.put("avgRank", Math.round(avg * 100.0) / 100.0);
                sel.put("firstPct", Math.round(1000.0 * first / ranks.size()) / 10.0);
                sel.put("top3Pct", Math.round(1000.0 * top3 / ranks.size()) / 10.0);
            }
            out.put("selectionFeedback", sel);
            out.put("searches", searches);
        }
        out.put("benchmark", benchmark);
        out.put("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000);
        out.put("index", indexSizes);
        return out;
    }

    static Map<String, Object> summarize(List<Double> values) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", values.size());
        if (values.isEmpty()) {
            return m;
        }
        Collections.sort(values);
        m.put("meanMs", round(values.stream().mapToDouble(d -> d).average().orElse(0)));
        m.put("p50Ms", round(pct(values, 50)));
        m.put("p95Ms", round(pct(values, 95)));
        m.put("p99Ms", round(pct(values, 99)));
        m.put("maxMs", round(values.get(values.size() - 1)));
        return m;
    }

    private static double pct(List<Double> sorted, int p) {
        int k = Math.max(0, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(k);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
