package com.sis.crawler;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.sis.metrics.AppMetrics;
import com.sis.notification.NotificationService;
import com.sis.shortener.ShortenerService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Level-by-level (BFS) crawler. Level 0 = source index pages, level 1 = listing pages.
 * Every level is fetched by a fixed thread pool ("parallel") or one page at a time ("sequential" baseline).
 * Pages are retried up to 3 times, listings are de-duplicated by URL and every listing gets a short link.
 */
@Service
public class CrawlerService {
    private static final int MAX_ATTEMPTS = 3;

    record Task(String url, String source) {
    }

    record RawListing(String source, String title, String organization, String deadline, String url) {
    }

    record FetchResult(Task task, boolean ok, List<String> links, List<RawListing> listings) {
    }

    private final CrawledListingRepository listings;
    private final CrawlRunRepository runs;
    private final ShortenerService shortener;
    private final NotificationService notifications;
    private final AppMetrics metrics;
    private final String baseUrl;
    private final int defaultThreads;
    private final int timeoutMs;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public CrawlerService(CrawledListingRepository listings, CrawlRunRepository runs, ShortenerService shortener,
                          NotificationService notifications, AppMetrics metrics,
                          @Value("${sis.crawler.base-url}") String baseUrl,
                          @Value("${sis.crawler.default-threads}") int defaultThreads,
                          @Value("${sis.crawler.request-timeout-ms}") int timeoutMs) {
        this.listings = listings;
        this.runs = runs;
        this.shortener = shortener;
        this.notifications = notifications;
        this.metrics = metrics;
        this.baseUrl = baseUrl;
        this.defaultThreads = defaultThreads;
        this.timeoutMs = timeoutMs;
    }

    public CrawlRun crawl(CrawlRequest req) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("A crawl is already running");
        }
        try {
            return doCrawl(req);
        } finally {
            running.set(false);
        }
    }

    private CrawlRun doCrawl(CrawlRequest req) {
        List<String> sources = (req == null || req.sources() == null || req.sources().isEmpty())
                ? Sources.ALL : req.sources();
        for (String s : sources) {
            if (!Sources.ALL.contains(s)) {
                throw new IllegalArgumentException("Unknown source: " + s);
            }
        }
        boolean parallel = req == null || req.mode() == null || !req.mode().equalsIgnoreCase("sequential");
        int threads = 1;
        if (parallel) {
            int wanted = (req != null && req.threads() != null) ? req.threads() : defaultThreads;
            threads = Math.max(1, Math.min(wanted, 64));
        }

        Instant startedAt = Instant.now();
        long t0 = System.nanoTime();
        ExecutorService pool = parallel ? Executors.newFixedThreadPool(threads) : null;
        Set<String> visited = new HashSet<>();
        List<Task> frontier = new ArrayList<>();
        for (String s : sources) {
            String seed = baseUrl + "/sim/" + s + "/index";
            visited.add(seed);
            frontier.add(new Task(seed, s));
        }
        Map<String, RawListing> found = new LinkedHashMap<>();
        int pages = 0;
        int errors = 0;
        try {
            while (!frontier.isEmpty()) {
                List<FetchResult> results = fetchAll(frontier, pool);
                List<Task> next = new ArrayList<>();
                for (FetchResult r : results) {
                    if (!r.ok()) {
                        errors++;
                        continue;
                    }
                    pages++;
                    for (RawListing l : r.listings()) {
                        found.putIfAbsent(l.url(), l);
                    }
                    for (String link : r.links()) {
                        if (visited.add(link)) {
                            next.add(new Task(link, r.task().source()));
                        }
                    }
                }
                frontier = next;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Crawl interrupted");
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
        long fetchMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

        // ---- store results ----
        long t1 = System.nanoTime();
        Map<String, Integer> newBySource = new HashMap<>();
        Map<String, String> firstNewLink = new HashMap<>();
        int newCount = 0;
        int updated = 0;
        List<RawListing> all = new ArrayList<>(found.values());
        List<String> idList = all.stream().map(l -> idOf(l.url())).toList();
        Map<String, CrawledListing> existing = listings.findAllById(idList).stream()
                .collect(Collectors.toMap(CrawledListing::id, c -> c));
        List<CrawledListing> toSave = new ArrayList<>();
        for (RawListing l : all) {
            String id = idOf(l.url());
            CrawledListing old = existing.get(id);
            String shortUrl;
            if (old != null && old.shortUrl() != null) {
                shortUrl = old.shortUrl();
                updated++;
            } else {
                shortUrl = shortener.shortLink(shortener.create(l.url(), null).code());
                newCount++;
                newBySource.merge(l.source(), 1, Integer::sum);
                firstNewLink.putIfAbsent(l.source(), shortUrl);
            }
            toSave.add(new CrawledListing(id, l.source(), l.title(), l.organization(), l.deadline(),
                    l.url(), shortUrl, Instant.now()));
        }
        listings.saveAll(toSave);
        long storeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t1);

        double fetchSec = Math.max(fetchMs, 1) / 1000.0;
        CrawlRun run = new CrawlRun(UUID.randomUUID().toString(), startedAt, parallel ? "parallel" : "sequential",
                threads, sources, pages, errors, all.size(), newCount, updated, fetchMs, storeMs,
                round(pages / fetchSec), round(all.size() / fetchSec));
        runs.save(run);

        if (req != null && req.notifyStudentIds() != null && !req.notifyStudentIds().isEmpty()) {
            newBySource.forEach((source, count) -> notifications.broadcast(req.notifyStudentIds(),
                    Sources.notificationType(source), "New " + source + " listings",
                    count + " new " + source + " listings were found. Start here: " + firstNewLink.get(source)));
        }
        return run;
    }

    private List<FetchResult> fetchAll(List<Task> tasks, ExecutorService pool) throws InterruptedException {
        List<FetchResult> out = new ArrayList<>();
        if (pool == null) {
            for (Task t : tasks) {
                out.add(fetch(t));
            }
            return out;
        }
        List<Callable<FetchResult>> calls = new ArrayList<>();
        for (Task t : tasks) {
            calls.add(() -> fetch(t));
        }
        List<Future<FetchResult>> futures = pool.invokeAll(calls);
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get());
            } catch (ExecutionException e) {
                out.add(new FetchResult(tasks.get(i), false, List.of(), List.of()));
            }
        }
        return out;
    }

    private FetchResult fetch(Task t) {
        long start = System.nanoTime();
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(t.url()))
                            .timeout(Duration.ofMillis(timeoutMs)).GET().build();
                    HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        return parse(t, resp.body());
                    }
                } catch (IOException e) {
                    // network error or timeout: retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new FetchResult(t, false, List.of(), List.of());
        } finally {
            metrics.crawlFetch.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private FetchResult parse(Task t, String html) {
        Document doc = Jsoup.parse(html, t.url());
        List<String> links = new ArrayList<>();
        for (Element a : doc.select("a.page-link")) {
            links.add(a.absUrl("href"));
        }
        List<RawListing> found = new ArrayList<>();
        for (Element el : doc.select("div.listing")) {
            Element apply = el.selectFirst("a.apply");
            if (apply == null) {
                continue;
            }
            found.add(new RawListing(t.source(), text(el, ".title"), text(el, ".org"), text(el, ".deadline"),
                    apply.absUrl("href")));
        }
        return new FetchResult(t, true, links, found);
    }

    private static String text(Element el, String selector) {
        Element e = el.selectFirst(selector);
        return e == null ? "" : e.text();
    }

    private static String idOf(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
