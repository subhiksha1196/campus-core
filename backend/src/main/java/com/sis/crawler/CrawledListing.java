package com.sis.crawler;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** id = hash of the listing URL, so re-crawling updates instead of duplicating. */
@Document("crawled_listings")
public record CrawledListing(@Id String id, @Indexed String source, String title, String organization,
                             String deadline, String url, String shortUrl, Instant crawledAt) {
}
