package com.sis.crawler;

import java.util.List;

/**
 * All fields optional. mode: "parallel" (default) or "sequential" (baseline).
 * notifyStudentIds: if given, those students get a notification for each source with new listings.
 */
public record CrawlRequest(List<String> sources, String mode, Integer threads, List<String> notifyStudentIds) {
}
