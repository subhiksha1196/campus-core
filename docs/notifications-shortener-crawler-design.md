> Integration update: the original notes below describe the supplied modules. Authentication, permissions, ID allocation and registration concurrency are superseded by `auth-and-platform-design.md` and the root README. There is no identity picker or trusted X-User-Id header now.

# Notifications, URL shortener and crawler: requirement analysis and component mapping

| SIS functionality | Challenge | Component | Why |
|---|---|---|---|
| Academic notifications | Many writes (broadcasts), a read-heavy unread badge | MongoDB + Redis counter cache | Compound index `(studentId, createdAt)`; the badge count is cache-aside with invalidation on every change |
| Links inside notifications / listings | Long URLs, many repeated redirects | URL Shortener | Unique id -> base62 code; Redis cache-aside makes hot redirects O(1) |
| External scholarships, internships, portal notices | Data lives outside the system, many slow pages | Web Crawler over simulated sources | Level-by-level crawl with thread pool, retries (3x), dedupe by URL hash |
| Evaluation | Prove the design scales | Locust + benchmark scripts | 3 workload levels, baselines, tables and graphs |

## Metrics collected
- Shortener: redirect latency (server side p50/p95/p99, cached vs baseline), cache hit ratio, client-side latency/throughput/error rate (Locust)
- Crawler: pages/s, listings/s, page errors, fetch latency, sequential vs N threads
- Notifications: write latency, API response time / throughput / error rate
- Overall: response time, throughput, error rate, CPU and memory (JVM + system) per workload level

## Trade-offs to discuss in the report
- Cache TTL vs stale data; click counts live in Redis only (fast, but lost if Redis is flushed)
- Sequential counter ids are predictable (could be scrambled); a Snowflake-style generator can replace it
- Crawler parallelism helps until the source or Tomcat thread pool saturates; politeness delay per host is a possible extension
- The simulated sources run inside the same app, so benchmark crawls compete with the crawler for CPU; point `CRAWLER_BASE_URL` at a second instance for cleaner numbers
