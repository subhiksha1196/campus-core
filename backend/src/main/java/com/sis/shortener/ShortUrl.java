package com.sis.shortener;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The short code is the Mongo _id, so lookups are a primary-key read. */
@Document("short_urls")
public record ShortUrl(@Id String code, String url, Instant createdAt) {
}
