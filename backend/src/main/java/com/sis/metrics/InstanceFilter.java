package com.sis.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-application-server load metric. Counts every request this server handles, tags the response with
 * "X-Served-By: <instance id>", and keeps latency / in-flight / 5xx counts for GET /api/instance.
 * Monitoring calls themselves (/api/instance, /actuator) are not counted.
 */
@Component
@Order(-10)
public class InstanceFilter extends OncePerRequestFilter {
    private final String instanceId;
    private final LongAdder served = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter counter;

    public InstanceFilter(@Value("${sis.instance-id:local}") String instanceId, MeterRegistry registry) {
        this.instanceId = instanceId;
        this.counter = Counter.builder("sis.instance.requests").tag("instance", instanceId).register(registry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String p = req.getRequestURI();
        return p.startsWith("/api/instance") || p.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Served-By", instanceId);
        long t0 = System.nanoTime();
        inFlight.incrementAndGet();
        try {
            chain.doFilter(req, res);
        } finally {
            inFlight.decrementAndGet();
            served.increment();
            counter.increment();
            totalNanos.add(System.nanoTime() - t0);
            if (res.getStatus() >= 500) {
                errors.increment();
            }
        }
    }

    public Map<String, Object> snapshot() {
        long n = served.sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", instanceId);
        out.put("requestsServed", n);
        out.put("errors5xx", errors.sum());
        out.put("inFlight", inFlight.get());
        out.put("meanMs", n == 0 ? 0.0 : Math.round(totalNanos.sum() / (double) n / TimeUnit.MILLISECONDS.toNanos(1) * 1000.0) / 1000.0);
        out.put("uptimeSeconds", java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return out;
    }
}
