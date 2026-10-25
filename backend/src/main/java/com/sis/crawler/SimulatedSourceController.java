package com.sis.crawler;

import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Simulated external websites (university portal, scholarship board, internship board).
 * Each source has an index page linking to N listing pages; every listing page holds M listings.
 * Latency and an optional error rate are configurable so crawl throughput can be measured.
 */
@RestController
@RequestMapping("/sim")
public class SimulatedSourceController {
    private static final String[] PORTAL_TOPICS = {"Exam timetable released", "Fee payment deadline extended",
            "Library timings revised", "Hostel allotment notice", "Convocation registration open",
            "Semester registration reminder"};
    private static final String[] PORTAL_ORGS = {"Academic Office", "Examination Cell", "Library", "Hostel Office"};
    private static final String[] SCHOLARSHIPS = {"Merit", "Women in STEM", "Research Excellence", "Sports Quota",
            "First Generation", "Alumni Endowment"};
    private static final String[] SCHOLARSHIP_ORGS = {"University Trust", "State Education Board",
            "Alumni Association", "Industry Consortium"};
    private static final String[] ROLES = {"Backend Developer", "Data Analyst", "ML Research", "DevOps",
            "Frontend Developer", "Security Analyst"};
    private static final String[] COMPANIES = {"Nimbus Labs", "Orbit Systems", "Helix Data", "Kavach Security",
            "Lumen Cloud", "Vertex AI Works"};

    private final long latencyMs;
    private final int pagesPerSource;
    private final int listingsPerPage;
    private final double errorRate;

    public SimulatedSourceController(@Value("${sis.simulator.latency-ms}") long latencyMs,
                                     @Value("${sis.simulator.pages-per-source}") int pagesPerSource,
                                     @Value("${sis.simulator.listings-per-page}") int listingsPerPage,
                                     @Value("${sis.simulator.error-rate}") double errorRate) {
        this.latencyMs = latencyMs;
        this.pagesPerSource = pagesPerSource;
        this.listingsPerPage = listingsPerPage;
        this.errorRate = errorRate;
    }

    @GetMapping(value = "/{source}/index", produces = MediaType.TEXT_HTML_VALUE)
    public String index(@PathVariable String source) {
        enter(source);
        StringBuilder sb = new StringBuilder("<html><body><h1>").append(source).append("</h1><ul>");
        for (int p = 1; p <= pagesPerSource; p++) {
            sb.append("<li><a class=\"page-link\" href=\"/sim/").append(source).append("/page/").append(p)
                    .append("\">Page ").append(p).append("</a></li>");
        }
        return sb.append("</ul></body></html>").toString();
    }

    @GetMapping(value = "/{source}/page/{n}", produces = MediaType.TEXT_HTML_VALUE)
    public String page(@PathVariable String source, @PathVariable int n) {
        enter(source);
        if (n < 1 || n > pagesPerSource) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        StringBuilder sb = new StringBuilder("<html><body>");
        for (int i = 0; i < listingsPerPage; i++) {
            int id = (n - 1) * listingsPerPage + i + 1;
            sb.append("<div class=\"listing\"><h2 class=\"title\">").append(title(source, id)).append("</h2>")
                    .append("<span class=\"org\">").append(org(source, id)).append("</span>")
                    .append("<span class=\"deadline\">").append(LocalDate.of(2026, 12, 1).plusDays(id % 90))
                    .append("</span><a class=\"apply\" href=\"/sim/").append(source).append("/apply/").append(id)
                    .append("\">Apply</a></div>");
        }
        return sb.append("</body></html>").toString();
    }

    @GetMapping(value = "/{source}/apply/{id}", produces = MediaType.TEXT_HTML_VALUE)
    public String apply(@PathVariable String source, @PathVariable int id) {
        enter(source);
        return "<html><body><h1>" + title(source, id) + "</h1><p>Application form (simulated)</p></body></html>";
    }

    private void enter(String source) {
        if (!Sources.ALL.contains(source)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown source");
        }
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (errorRate > 0 && ThreadLocalRandom.current().nextDouble() < errorRate) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulated outage");
        }
    }

    private static String title(String source, int id) {
        return switch (source) {
            case "scholarships" -> SCHOLARSHIPS[id % SCHOLARSHIPS.length] + " Scholarship #" + id;
            case "internships" -> ROLES[id % ROLES.length] + " Intern #" + id;
            default -> PORTAL_TOPICS[id % PORTAL_TOPICS.length] + " (Notice #" + id + ")";
        };
    }

    private static String org(String source, int id) {
        return switch (source) {
            case "scholarships" -> SCHOLARSHIP_ORGS[id % SCHOLARSHIP_ORGS.length];
            case "internships" -> COMPANIES[id % COMPANIES.length];
            default -> PORTAL_ORGS[id % PORTAL_ORGS.length];
        };
    }
}
