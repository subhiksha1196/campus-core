package com.sis.crawler;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrawledListingRepository extends MongoRepository<CrawledListing, String> {
    Page<CrawledListing> findBySource(String source, Pageable pageable);
}
