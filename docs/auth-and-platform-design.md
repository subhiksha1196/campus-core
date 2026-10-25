# Authentication, platform services and integration: requirements and design

## Functional requirements

| ID | Requirement | Implementation |
|---|---|---|
| FR1 | Register a student | POST /api/auth/register; validation; globally unique canonical email; generated S-prefixed ID |
| FR2 | Authenticate and end sessions | POST /api/auth/login and /logout; random expiring bearer token in Redis |
| FR3 | Read/edit own profile | GET/PUT /api/auth/me; name, department and year editable |
| FR4 | Generate unique IDs | Shared IdSource, atomic durable Mongo counter; student and course prefixes; shortener uses same allocator |
| FR5 | Limit abusive requests | Distributed token-bucket limiter (atomic Redis Lua script, Redis server time); per-IP auth and per-user API buckets; HTTP 429 and Retry-After |
| FR6 | Report module metrics | /api/metrics/summary and protected actuator metrics: generation rate, count, collisions, allowed/rejected requests |
| FR7 | Enforce permissions | Server-side session principal; no trust in browser role or X-User-Id |

## Non-functional requirements and choices

- Security: salted PBKDF2-HMAC-SHA256 passwords, 210,000 iterations, 256-bit random session tokens,
  eight-hour TTL, logout revocation, generic invalid-credentials response. No password hashes in profile responses.
  Tokens are kept in sessionStorage and sent in Authorization headers (not automatically sent cross-site cookies).
  HTTPS is required outside local development; XSS prevention and CSP remain deployment responsibilities.
- Consistency: a central Mongo `_id=email` reservation enforces cross-shard email uniqueness. The browser
  cannot change roles, IDs or email through profile editing. Student data remains routed through ShardRouter.
- Durability: `id_sequences` in the primary Mongo database survives Redis loss. IDs start above 1 trillion
  to avoid overlap with previously allocated prototype IDs. Back up counters with application data. Never
  restore an older counter alone. Atomic increments may leave gaps; IDs are unique, not gapless.
- Availability: authentication/limiter infrastructure fails closed with 503. Shard-down simulation preserves
  control-plane session lookup so an administrator can bring the shard up. Actual Mongo failure still blocks auth.
- Scalability: sessions and rate limits are shared across instances. ID generation serializes a tiny atomic
  database update. Email login currently scans the configured shards, so login latency grows with shard count.
- Performance: the token bucket allows a burst up to the bucket capacity (default equal to the per-minute limit),
  then holds each client to the refill rate, with no window-edge double burst. The metrics
  page shows average IDs/second since process start; use counter deltas over the measurement interval for tests.

## API contracts

All requests and responses are JSON. All routes except login/register, health, redirects and simulated source
pages require a bearer token. Statuses: 400 validation, 401 missing/invalid login/session, 403 permission,
409 duplicate/conflict, 429 throttled, 503 infrastructure unavailable.

- `POST /api/auth/register`: `{name,email,department,year,password}`. Name 1–100 chars, department 1–60,
  year 1–8, password 10–128. Returns public profile, HTTP 201. Client then logs in.
- `POST /api/auth/login`: `{email,password}`. Returns `{token,user,expiresIn:28800}`.
- `POST /api/auth/logout`: revokes current token.
- `GET /api/auth/me`: `{id,name,email,department,year,role}`.
- `PUT /api/auth/me`: `{name,department,year}`; email/role/ID cannot be changed. Cached person data invalidated;
  autocomplete refreshes within 60 seconds.

## Permission matrix

| Operation | Student | Faculty | Admin |
|---|---|---|---|
| Own profile, notifications | Yes | Yes | Yes |
| Student course registration, own timetable/grades/attendance | Own student account | No enrollment | Any student |
| Course list/search and public person summary | Yes | Yes | Yes |
| Course roster, attendance sheet and exam marks | No | Own courses | All |
| Send notifications, crawl simulated sources | No | Yes | Yes |
| Shard operations, raw KV/schema tools, metric mutations | No | No | Yes |
| Short links and listings | Yes | Yes | Yes |

## Registration concurrency integration fix

A shared durable Mongo mutex serializes registration/drop validation and writes, preventing two students
from claiming the last seat and concurrent enrollments from exceeding one student's credit limit.
Competing writes receive 409 and may retry. This intentionally favors correctness over enrollment write
throughput. The lock does not expire, avoiding unsafe lease expiry during a slow write. If a process crashes
while holding it, stop all application writers, check registration records, then delete the `registration`
document in the primary database's `operation_locks` collection and restart. This is a prototype recovery
procedure, not automatic failover. A transactional reservation design is a future higher-throughput option.

## Shard maintenance and recovery

Topology changes pause HTTP requests within the serving application, migrate records, and persist the
successful active shard list for restart. For multi-instance deployment, stop/drain all other instances
before migration and restart them afterwards. Background jobs must also be quiesced for a production
migration. Use a backup and a maintenance window; interrupted migration still needs operator recovery.
All supplied shards are logical Mongo databases on one Mongo server, not independent physical servers.
The down/up endpoints simulate shard unavailability; they do not provide replication/failover.

An ambiguous failed signup retains its email reservation to avoid duplicate accounts. After verifying that
no student record exists, an operator may remove the matching `auth_emails` entry and retry. Do not remove
reservations for existing accounts. Notifications remain best-effort as in the supplied academic module.

## Metrics and load testing

- `sis.id.generated`: allocated IDs, including course seeding and short links.
- `sis.id.generation`: allocation timer.
- `sis.id.collisions`: observed student insertion ID collisions (normally zero; not a proof for all callers).
- `sis.ratelimit.requests{scope=auth|api,result=allowed|rejected}`: counts for rejection ratio.
- `/api/metrics/summary.person1`: browser-friendly snapshot.

Existing load scripts now accept `SIS_LOAD_TOKEN` (use an admin login token for cross-student evaluation).
Set `SIS_API_LIMIT` high enough for the load experiment, then separately test throttling with the default
limit. Do not mistake deliberate 429 responses for a backend capacity limit. Locust uses low/medium/high
levels of 10/50/200 users; redirects include the original DB-only baseline. Run `python plot_results.py`
after collecting data. Never report fabricated graphs or numbers; measured performance depends on your machine.

## Module mapping

Rate limiter → auth/API protection. ID generator → new students, seeded students/courses, short links.
KvStore → sessions and existing academic caches. ShardRouter → student profile persistence and academic records.
Existing academic, crawler, shortener and notification modules remain integrated with these shared services.

### Obtain a load-test token

Start the backend with a sufficiently high `SIS_API_LIMIT` for capacity testing. In a terminal with
Python and `requests` installed:

```bash
export SIS_LOAD_TOKEN=$(python -c 'import getpass,requests; r=requests.post("http://localhost:8080/api/auth/login",json={"email":"admin@ssn.edu.in","password":getpass.getpass("Demo admin password: ")}); r.raise_for_status(); print(r.json()["token"])')
cd load-tests
pip install -r requirements.txt
python run_load_tests.py
python plot_results.py
```

Tokens expire after eight hours. Use separate credentials/sessions per user when measuring realistic
per-user access; the supplied aggregate workload deliberately uses admin access to read multiple students.
