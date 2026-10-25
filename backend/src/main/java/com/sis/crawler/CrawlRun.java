package com.sis.crawler;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Result of one crawl; stored so benchmark runs can be turned into tables and graphs. */
@Document("crawl_runs")
public record CrawlRun(@Id String id, Instant startedAt, String mode, int threads, List<String> sources,
                       int pagesFetched, int pageErrors, int listingsFound, int newListings, int updatedListings,
                       long fetchMs, long storeMs, double pagesPerSec, double listingsPerSec) {
}
