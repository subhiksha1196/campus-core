package com.sis.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Load of THIS application server: requests served, latency, in-flight, CPU, heap. Call it on each server directly. */
@RestController
@RequestMapping("/api/instance")
public class InstanceController {
    private final InstanceFilter filter;
    private final MeterRegistry registry;

    public InstanceController(InstanceFilter filter, MeterRegistry registry) {
        this.filter = filter;
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>(filter.snapshot());
        Gauge cpu = registry.find("process.cpu.usage").gauge();
        out.put("processCpuPercent", cpu == null ? 0.0 : Math.round(cpu.value() * 1000.0) / 10.0);
        double heap = registry.find("jvm.memory.used").tag("area", "heap").gauges().stream()
                .mapToDouble(Gauge::value).sum();
        out.put("jvmHeapUsedMb", Math.round(heap / 1048576.0 * 10.0) / 10.0);
        return out;
    }
}
