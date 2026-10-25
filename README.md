# CampusCore: Scalable Student Information System

A horizontally scalable student information platform for a university. Students register for courses and track attendance, grades and timetables; faculty take attendance and publish marks; admins manage people, shards and system health. The backend is **Spring Boot 3 + MongoDB (application-level sharding) + Redis**, load-balanced with **nginx**, with a **React** frontend, and it was evaluated with real load tests.

## Contents

1. [Features](#features)
2. [Engineering highlights](#engineering-highlights)
3. [Architecture](#architecture)
4. [Tech stack](#tech-stack)
5. [Project structure](#project-structure)
6. [Getting started](#getting-started)
7. [Configuration](#configuration)
8. [API overview](#api-overview)
9. [Security model](#security-model)
10. [Testing](#testing)
11. [Performance results](#performance-results)
12. [Design trade-offs and limitations](#design-trade-offs-and-limitations)
13. [Documentation](#documentation)

## Features

| Area | What it does |
|---|---|
| **Accounts and roles** | Student sign-up, login and logout, profile editing. Three roles (Student, Faculty, Admin) enforced on the server. Staff accounts cannot be self-created. |
| **Courses and registration** | Course catalog with seats left, registration and drop with seat, 24-credit limit and timetable-clash checks. |
| **Attendance** | Faculty mark absent / on-duty per class; students see attendance %, with a 75% rule plus "can miss N more" and "attend N in a row" hints. |
| **Grades** | Faculty enter marks per exam; course result, letter grade (10-point scale) and credit-weighted GPA; class grade distribution. |
| **Timetable** | Weekly timetable for students and instructors, with a live "Today" panel. |
| **Search** | Instant autocomplete for students, faculty, admins and courses, tolerant of one typo. |
| **Notifications** | Inbox with unread badge; automatic alerts for registration, attendance below 75% and posted marks; broadcast to many students. |
| **URL shortener** | Short links with click stats; links inside notifications are shortened automatically. |
| **Web crawler** | Pulls scholarship, internship and portal listings from simulated external sites, sequentially or in parallel. |
| **Admin and ops** | Shard add / remove / down / up with data migration, per-server load view, metrics dashboard. |

## Engineering highlights

- **Application-level sharding.** A `ShardRouter` places student, registration, attendance and grade records on 3 logical MongoDB shards using a **consistent-hash ring (150 virtual nodes per shard)**. Single-student operations touch one shard; class-wide queries scatter-gather. A `ShardRebalancer` migrates records when shards are added or removed.
- **Horizontal scaling behind nginx.** Stateless Spring Boot servers share sessions, caches and rate-limit state through Redis. With `least_conn`, going from 1 to 3 servers raised throughput from **162 to 328 req/s (+102%)** and cut average latency from **459 ms to 137 ms**, with 0% errors.
- **Redis cache-aside.** Cached redirects served **310 req/s vs 189 req/s** for the MongoDB-only baseline at 200 users, and halved average latency (**462 ms to 245 ms**).
- **Concurrency-safe registration.** A durable Mongo mutex serializes seat and credit checks so two students cannot take the last seat. Under 200 concurrent users every outcome was correct (registered, course full, timetable clash, already registered).
- **Distributed token-bucket rate limiter.** An atomic Redis Lua script using Redis server time, so all app servers share one bucket per client and clock drift cannot affect refill. Per-IP for login/sign-up, per-account for the API; returns HTTP 429 with `Retry-After`. Avoids the double-burst problem of fixed windows.
- **Durable unique IDs.** An atomic Mongo counter, plus Base62 for short codes, survives Redis loss. Shared by student IDs, course IDs and short links.
- **In-memory autocomplete.** A prefix trie per entity type rebuilt from all shards every minute, so there is no database call per keystroke. Suggestion accuracy is measured (top-5 hit rate, mean reciprocal rank).
- **Parallel crawler.** Level-by-level crawl on a thread pool, 3 retries and URL-hash deduplication, benchmarked against a sequential baseline.
- **Observability.** Micrometer metrics, a Prometheus endpoint and an in-app Metrics tab (latency percentiles, cache hit ratio, ID rate, rate-limit rejections, per-server load).

## Architecture

```
                         React (Vite) :5173
                                |
                          nginx :8080     least_conn | consistent hash
                        /       |       \
                   app1       app2       app3      Spring Boot 3, Java 17, stateless
                     \          |          /
          +-----------+---------+---------+-----------+
          | Redis 7: sessions, rate-limit buckets,    |
          |          caches, redirects, unread counts |
          +-------------------------------------------+
          | MongoDB 7:  shard0 | shard1 | shard2      |
          |             (ShardRouter, consistent hash)|
          |             primary DB: ID counters,      |
          |             email reservations, locks     |
          +-------------------------------------------+
```

| Backend package (`com.sis.*`) | Responsibility |
|---|---|
| `auth` | register, login, sessions, role checks, PBKDF2 hashing, token-bucket rate limiter |
| `academic` | courses, registration (+ mutex), attendance, grades, timetable, autocomplete search, demo seeder |
| `sharding` | `ShardRouter`, `ConsistentHashRing`, `ShardRebalancer`, shard admin APIs |
| `schema` | entity records (Student, Course, Registration, Attendance, Grade, TimetableEntry) and indexes |
| `notification` | notification storage, broadcast, Redis-cached unread counts |
| `shortener` | short links, Redis cache-aside redirect and a DB-only baseline endpoint |
| `crawler` | crawler service and simulated external sources (`/sim/**`) |
| `kv` | Redis-backed key-value store used for sessions and caches |
| `common`, `config`, `metrics` | error handling, Base62, ID source, CORS, Micrometer meters, instance info |
| `demo` | optional startup setup of ready-made admin, teacher and student accounts |

## Tech stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Spring Boot 3.2, Spring Data MongoDB and Redis, Maven |
| Data | MongoDB 7 (3 logical shards), Redis 7 (AOF persistence) |
| Frontend | React 18, Vite, Font Awesome (bundled, no CDN) |
| Infra | Docker Compose, nginx 1.27 load balancer |
| Testing and metrics | JUnit, Mockito, Playwright, jsdom, Locust, Micrometer, Prometheus, Python (pandas, matplotlib) |

## Project structure

```
backend/                 Spring Boot service (Dockerfile, pom.xml, src/main, src/test)
frontend/                React app: pages/, components/, session and API helpers, design.md, tests/
nginx/                   Load balancer config template
load-tests/              Locust workloads, crawler benchmark, scaling experiment, plotting, results/
docs/                    Design notes, API reference, verification record
docker-compose.yml       mongo, redis, app1, app2 (app3 optional), nginx
docker-compose.demo.yml  Override that creates the demo admin / teacher / student accounts
.env.example             Environment template
```

Frontend pages: Landing, Auth, Courses, Timetable, Attendance, Grades, Faculty, Search, Notifications, URL Shortener, Crawler, Metrics, Profile.

## Getting started

**Prerequisites:** Docker Compose and Node 18+. Java 17 and Maven 3.9+ are only needed to run the backend outside Docker.

### 1. Run the backend stack (Docker)

```bash
cp .env.example .env          # then set SIS_DEMO_PASSWORD to a password you choose
docker compose up -d --build  # mongo, redis, app1, app2, nginx  ->  http://localhost:8080
curl -i http://localhost:8080/actuator/health   # the X-Served-By header alternates app1 / app2
```

On first start `app1` seeds demo data: 120 students, 7 faculty, 24 courses, timetable, registrations, attendance and marks, spread across the three shards. Seeding only runs when there are no courses.

### 2. Run the frontend

```bash
cd frontend
npm ci
npm run dev                   # http://localhost:5173 (proxies /api, /s, /sim, /baseline to :8080)
```

### 3. Sign in

- **Create a student account** with *New student? Create account* on the sign-up page.
- **Use ready-made demo accounts** (admin, teacher, student) by starting the stack with the demo override:

```bash
# Linux / macOS
SIS_DEFAULT_DEMO_PASSWORD='choose-a-password' \
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --force-recreate app1 app2 nginx
# PowerShell
$env:SIS_DEFAULT_DEMO_PASSWORD = 'choose-a-password'
docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d --build --force-recreate app1 app2 nginx
```

| Role | Email |
|---|---|
| Admin | `demo.admin@ssn.edu.in` |
| Teacher | `demo.teacher@ssn.edu.in` |
| Student | `demo.student@ssn.edu.in` |

The demo teacher teaches three extra courses (DEMO101 to DEMO103), the demo student is registered for all three, and attendance, marks and notifications are pre-filled. Wait for `Default demo setup ready` in `docker compose logs app1`. Changing the password variable later does not reset accounts that already exist. These accounts are for local evaluation only.

### Run without Docker for the app servers

```bash
docker compose up -d mongo redis
cd backend && mvn spring-boot:run          # API on :8080
```

### Add or remove application servers

```bash
docker compose --profile extra up -d app3  # then add it to APP_SERVERS for nginx
docker compose stop app2                   # remove a server
```

Shard add/remove/down keep their state in each server's memory, so run those operations with a single app server (`docker compose stop app2`) and restart the others afterwards so they reload the saved topology.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SIS_DEMO_PASSWORD` | required in Docker | Password for seeded accounts (read by the backend process) |
| `SIS_DEFAULT_DEMO_PASSWORD` | none | Password for the demo admin / teacher / student accounts |
| `SIS_API_LIMIT` | 600 | API requests per minute per account (use 1000000 for load tests) |
| `SIS_AUTH_LIMIT` | 20 | Login/sign-up requests per minute per IP |
| `MONGO_URI`, `REDIS_HOST`, `REDIS_PORT` | localhost | Datastore locations when running outside Docker |
| `SHORT_BASE_URL`, `CRAWLER_BASE_URL` | `http://localhost:8080` | Base URLs for short links and the crawler |
| `APP_CPUS`, `APP_MEM` | 1.0, 768m | Fixed capacity per app server, so scaling results are meaningful |
| `LB_DIRECTIVE` | `least_conn;` | nginx method; or `hash $request_uri consistent;` |

Other settings (shard names, virtual nodes, crawler threads, simulator latency and error rate, search refresh interval) are in `backend/src/main/resources/application.yml`.

## API overview

All routes except login/register, health, redirects and simulated sources need `Authorization: Bearer <token>`. Errors use one JSON format with status 400 (validation), 401, 403, 409 (conflict or busy lock), 429 (throttled) or 503 (infrastructure or shard unavailable). The complete endpoint tables with request bodies are in [`docs/API-REFERENCE.md`](docs/API-REFERENCE.md).

| Group | Endpoints |
|---|---|
| Auth | `POST /api/auth/register`, `/login`, `/logout`; `GET`/`PUT /api/auth/me` |
| Courses and registration | `GET /api/academic/courses`, `POST /api/academic/registrations`, `DELETE /api/academic/registrations/{studentId}/{courseId}` |
| Student views | `GET /api/academic/students/{id}/enrollments`, `/attendance`, `/grades`; `GET /api/academic/timetable` |
| Faculty tools | `GET`/`POST /api/academic/courses/{id}/attendance`, `POST .../marks`, `GET .../grades`, `GET .../roster` |
| Search | `GET /api/academic/search?type=&q=` (trie-backed), `POST /api/academic/search/feedback` |
| Notifications | `POST /api/notifications`, `/broadcast`; `GET /api/notifications`, `/unread-count`; `PATCH .../{id}/read` |
| Shortener | `POST /api/urls`, `GET /s/{code}` (cached), `GET /baseline/s/{code}` (DB only), `GET /api/urls/{code}/stats` |
| Crawler | `POST /api/crawl` (`mode: parallel\|sequential`), `GET /api/crawl/runs`, `GET /api/listings`, `GET /sim/**` |
| Sharding (admin) | `GET /api/shards/locate?key=`, `POST /api/shards/admin/down`, `/up`, add and remove |
| Metrics | `GET /api/metrics/summary`, `GET /api/instance`, `GET /actuator/prometheus`, `GET /actuator/health` |

## Security model

- Passwords: salted PBKDF2-HMAC-SHA256, 210,000 iterations; hashes are never returned in responses.
- Sessions: random 256-bit bearer tokens stored in Redis with an 8-hour TTL, revoked on logout; kept in `sessionStorage` and sent in headers (not cookies).
- Authorization is server-side only. Browser-supplied roles, IDs and `X-User-Id` are ignored. New accounts always get STUDENT.
- Students can read only their own records. Course rosters, attendance sheets and marks require the course instructor or an admin. Shard operations and raw tools are admin-only.
- Email uniqueness across shards is enforced by a central reservation document.
- Login, sign-up and API calls are rate-limited; the limiter fails closed with 503 if Redis is unreachable.
- Use HTTPS outside local development. CSP and XSS hardening are deployment responsibilities.

## Testing

```bash
# Backend unit tests
cd backend && mvn test

# Opt-in HTTP workflow test (use disposable databases: it seeds data and changes shard topology)
SIS_INTEGRATION=true mvn -Dtest=IntegrationTest test
SIS_INTEGRATION=true SIS_MONGO_EMULATOR=true mvn -Dtest=IntegrationTest test   # in-memory Mongo emulator, real Redis still required

# Frontend
cd frontend
npm run build
npm run test:autocomplete      # jsdom tests: suggestions, keyboard use, error display
npm run test:ui                # Playwright smoke test with mocked API (needs: npx playwright install chromium)
```

Backend tests cover grading and attendance rules, overlap detection, the autocomplete trie and accuracy benchmark, Base62, consistent hashing, authentication and password handling, and the demo setup. The integration workflow covers unauthorized access, sign-up and login, duplicate and wrong-password rejection, profile edit, role-based denial, enrollment and drop, timetable and grades, logout revocation, shard add/remove/down/up, notifications, short links and login throttling. See [`docs/verification.md`](docs/verification.md) for exactly what was run and what is not claimed.

## Performance results

Measured with Locust at 10 / 50 / 200 concurrent users on a single development machine. Full tables and graphs are in [`load-tests/results/summary.md`](load-tests/results/summary.md).

**Server scaling** (100 users, 60 s, nginx in front of N servers, 0% errors)

| Load balancing | Servers | Throughput (req/s) | Avg (ms) | p95 (ms) |
|---|---|---|---|---|
| least_conn | 1 | 162 | 459 | 880 |
| least_conn | 2 | 270 | 190 | 390 |
| least_conn | 3 | 328 | 137 | 300 |
| consistent hash | 3 | 362 | 179 | 710 |

**Cache vs database baseline** (200 users)

| Path | Throughput (req/s) | Avg (ms) | p95 (ms) |
|---|---|---|---|
| Redis cache-aside redirect | 310 | 245 | 400 |
| MongoDB-only redirect | 189 | 462 | 810 |

At 200 users the other workloads also ran with 0 failures: academic reads 297 req/s, notifications 306 req/s, course registration 246 req/s.

Reproduce:

```bash
cd load-tests
pip install -r requirements.txt
python run_load_tests.py --duration 30   # scenarios x 3 levels, plus CPU and memory sampling
python scale_experiment.py               # 1 / 2 / 3 servers, remove and re-add a server under load
python crawler_benchmark.py --repeats 3  # sequential baseline vs 2..32 crawler threads
python plot_results.py                   # writes results/summary.md and the PNG graphs
```

## Design trade-offs and limitations

- **Login is intentionally slow.** PBKDF2 at 210,000 iterations costs about 655 ms per login at 10 users and is CPU-bound at 200. That is the price of strong password hashing.
- **Registration favors correctness over throughput.** The global mutex returns HTTP 409 to competing writers, who retry. A per-course atomic seat reservation would scale better.
- **Shards are logical.** The three shards are databases on one MongoDB server: there is no replication or automatic failover, and "shard down" is simulated.
- **Topology changes are not fully online.** Add/remove migrates records and needs other app servers drained first; an interrupted migration needs operator recovery.
- **Cached data can be stale.** Autocomplete refreshes every minute; click counts live only in Redis.
- **Email login scans all shards**, so login latency grows with the number of shards.

Recovery steps and rationale for each of these are in [`docs/auth-and-platform-design.md`](docs/auth-and-platform-design.md).

## Documentation

| File | Contents |
|---|---|
| [`docs/API-REFERENCE.md`](docs/API-REFERENCE.md) | full endpoint tables, configuration, operations and load-test commands |
| [`docs/auth-and-platform-design.md`](docs/auth-and-platform-design.md) | requirements, security, consistency, permission matrix, registration mutex, shard recovery |
| [`docs/academic-module-design.md`](docs/academic-module-design.md) | academic module, data placement per operation, cache keys, known limits |
| [`docs/notifications-shortener-crawler-design.md`](docs/notifications-shortener-crawler-design.md) | notifications, shortener, crawler, evaluation plan |
| [`docs/verification.md`](docs/verification.md) | what was verified and what is not claimed |
| [`frontend/design.md`](frontend/design.md) | CampusCore UI design system (tokens, layout, accessibility) |
