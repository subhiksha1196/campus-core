package com.sis.crawler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CrawlerController {
    private final CrawlerService crawler;
    private final CrawledListingRepository listings;
    private final CrawlRunRepository runs;

    public CrawlerController(CrawlerService crawler, CrawledListingRepository listings, CrawlRunRepository runs) {
        this.crawler = crawler;
        this.listings = listings;
        this.runs = runs;
    }

    /** Start a crawl and wait for it to finish. Body is optional. */
    @PostMapping("/crawl")
    public CrawlRun crawl(@RequestBody(required = false) CrawlRequest req) {
        return crawler.crawl(req);
    }

    @GetMapping("/crawl/runs")
    public List<CrawlRun> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc();
    }

    @GetMapping("/listings")
    public Map<String, Object> listings(@RequestParam(required = false) String source,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        Pageable p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("title"));
        Page<CrawledListing> result = (source == null || source.isBlank())
                ? listings.findAll(p) : listings.findBySource(source, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", result.getTotalElements());
        out.put("items", result.getContent());
        return out;
    }

    /** Dev/benchmark helper: forget all crawled listings so the next crawl starts from scratch. */
    @DeleteMapping("/listings")
    public Map<String, Object> clearListings() {
        long n = listings.count();
        listings.deleteAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", n);
        return out;
    }
}
