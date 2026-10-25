package com.sis.crawler;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface CrawlRunRepository extends MongoRepository<CrawlRun, String> {
    List<CrawlRun> findTop20ByOrderByStartedAtDesc();
}
