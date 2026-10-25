# API reference and operations guide

Moved out of the root README. Authentication, permissions and design rationale are in `auth-and-platform-design.md`.
All routes except login/register, health, redirects and simulated source pages require
`Authorization: Bearer <token>`.

## Academic API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/academic/search?type=student\|faculty\|admin\|course&q=&limit=` | autocomplete (in-memory trie, rebuilt from all shards every minute) |
| POST | `/api/academic/search/feedback` | `{rank}` position of the suggestion that was picked |
| GET | `/api/academic/metrics` | search latency, picked-suggestion stats, last benchmark |
| POST | `/api/academic/metrics/benchmark` | `{n}` run the suggestion-accuracy benchmark |
| GET | `/api/academic/courses?q=&department=&instructorId=&studentId=` | course list with seats left |
| GET | `/api/academic/courses/{id}` , `/roster` | one course, its students with attendance % |
| POST | `/api/academic/registrations` | `{studentId, courseId}` register (seat, credit limit, clash checks) |
| DELETE | `/api/academic/registrations/{studentId}/{courseId}` | drop |
| GET | `/api/academic/students/{id}/enrollments` , `/attendance` , `/grades` | a student's view |
| GET | `/api/academic/timetable?studentId=` or `instructorId=` | weekly timetable (student's cached in KvStore) |
| GET / POST | `/api/academic/courses/{id}/attendance` | class sheet for a date / save it `{date, absent[], onDuty[]}` |
| GET | `/api/academic/courses/{id}/exams` , `/exams/{exam}/marks` | exams of a course, marks sheet |
| POST | `/api/academic/courses/{id}/marks` | `{exam, maxMarks, marks:{studentId: number or null}}` |
| DELETE | `/api/academic/courses/{id}/exams/{exam}` | remove an exam and its marks |
| GET | `/api/academic/courses/{id}/grades` | class grade table and distribution |
| GET | `/api/academic/overview` , `/people/{id}` | admin totals, one person |
| POST | `/api/academic/seed` | create the demo data (no-op when courses exist) |

## Notifications, shortener, crawler and metrics API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/notifications` | `{studentId, type, title, message}`; links in `message` become short links |
| POST | `/api/notifications/broadcast` | `{studentIds[], type, title, message}` |
| GET | `/api/notifications?studentId=&unreadOnly=&page=&size=` | inbox |
| GET | `/api/notifications/unread-count?studentId=` | badge count (Redis-cached) |
| PATCH | `/api/notifications/{id}/read` | mark one read |
| POST | `/api/notifications/read-all?studentId=` | mark all read |
| POST | `/api/urls` | `{url, alias?}` -> short link |
| GET | `/s/{code}` | 302 redirect, Redis cache-aside (proposed design) |
| GET | `/baseline/s/{code}` | 302 redirect, MongoDB only (baseline) |
| GET | `/api/urls`, `/api/urls/{code}/stats` | recent links, click counts |
| POST | `/api/crawl` | `{sources?, mode: parallel\|sequential, threads?, notifyStudentIds?}` runs a crawl |
| GET | `/api/crawl/runs` | stored crawl results (pages/s etc.) |
| GET / DELETE | `/api/listings` | crawled scholarship / internship / portal listings |
| GET | `/sim/{portal\|scholarships\|internships}/index` | simulated external sites |
| GET | `/api/metrics/summary` | latency percentiles, cache hit ratio, resources |
| GET | `/actuator/prometheus` | all raw metrics |

```bash
curl -X POST localhost:8080/api/urls -H 'Content-Type: application/json' -d '{"url":"https://example.com/a"}'
curl -i localhost:8080/s/<code>
curl -X POST localhost:8080/api/crawl -H 'Content-Type: application/json' -d '{"mode":"parallel","threads":8}'
```

## Configuration

Environment overrides: `MONGO_URI`, `REDIS_HOST`, `REDIS_PORT`, `SHORT_BASE_URL`, `CRAWLER_BASE_URL`,
`SIS_DEMO_PASSWORD`, `SIS_API_LIMIT` (default 600 requests/minute/account), `SIS_AUTH_LIMIT`
(default 20 login/signup requests/minute/IP). Requests use the socket IP, not untrusted forwarded headers.

`SIS_DEMO_PASSWORD` is read by the backend process (not from a `.env` file when running outside Docker).
Seeded passwords are enabled only when it is explicitly set. Existing `!seeded` accounts are upgraded
automatically; accounts that already have a password are never overwritten. Changing the variable does not
reset existing passwords. New accounts always receive the STUDENT role; role selection is never accepted from
the browser, and public staff signup is intentionally unavailable.

## Running without Docker

```bash
docker compose up -d mongo redis          # databases only
cd backend && mvn spring-boot:run         # API on :8080
cd frontend && npm ci && npm run dev      # UI on :5173
```

## Build and verify

```bash
cd backend
mvn test
mvn package
# Optional full HTTP workflow test: run only against disposable databases (seeds data, changes topology).
SIS_INTEGRATION=true mvn -Dtest=IntegrationTest test
# Alternative with an in-memory Mongo wire-protocol emulator (still needs real Redis):
SIS_INTEGRATION=true SIS_MONGO_EMULATOR=true mvn -Dtest=IntegrationTest test
cd ../frontend && npm ci && npm run build
```

See `verification.md` for exactly what was run and what is not claimed.

## Multiple application servers

- Load balancer config: `nginx/default.conf.template`. Method chosen with `LB_DIRECTIVE`
  (`least_conn;` or `hash $request_uri consistent;`).
- Per-server load: `GET /api/instance` on each server (`:8081`, `:8082`, `:8083`) and the `X-Served-By` response header.
- Shard add/remove/down keep their state in each server's memory: run them with a single app server
  (`docker compose stop app2`), then start the others again so they reload the saved topology.
- Experiments: `load-tests/scale_experiment.py` (1/2/3 servers, remove/re-add a server), then `plot_results.py`.

## Load tests

```bash
cd load-tests
pip install -r requirements.txt
python run_load_tests.py --duration 30      # scenarios x 3 levels (10 / 50 / 200 users) + CPU/memory
python crawler_benchmark.py --repeats 3     # sequential baseline vs 2..32 crawler threads
python plot_results.py                      # -> results/summary.md and results/*.png
```

For meaningful numbers run the backend as a jar or in Docker. For load tests set `SIS_API_LIMIT=1000000`
so the shared token is not throttled. Failure test for the crawler: set `sis.simulator.error-rate: 0.2`.
