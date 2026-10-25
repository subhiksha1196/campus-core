# Verification record — 19 October 2026

## Completed checks

- Real Maven compilation against Spring Boot 3.2.5 / Java 17 dependencies (not API stubs).
- 30 unit tests passed: original academic/routing/Base62 tests plus nine authentication/security tests.
- One opt-in Spring Boot HTTP integration test passed, using real Redis 7.2.7 and mongo-java-server 1.45.0
  (an in-memory Mongo wire-protocol emulator). No application service mocks were used in this workflow.
- Frontend production build passed with Vite; 47 modules transformed.
- Python load-test scripts passed syntax compilation.

The integration workflow verifies: unauthorized access rejection; student signup; duplicate email rejection;
wrong-password rejection; login; public profile output without hashes; profile editing; forbidden shard
administration; cross-student grade/notification denial; course listing; enrollment/drop; timetable and grades;
logout revocation; admin login/metrics/search rebuild; shard add/remove/down/up; notification creation/read;
short-link creation; faculty attendance/marks access; student marks-write denial; and login throttling.

## What is not claimed

- Native MongoDB 7.0.16 could not start in this sandbox (internal numPartitions invariant on startup).
  The emulator does not implement all Mongo indexes, query plans, disk persistence or failure behavior.
  Run the included opt-in integration test against disposable real Mongo/Redis before demonstration.
- No new full-browser end-to-end run was performed. The frontend build was verified; the supplied teammate's
  earlier browser checks describe the pre-authentication version, not this final version.
- No production-scale performance measurements or graphs are claimed. Run the included three-level Locust
  scripts against your actual deployment to produce the performance section.
- The existing logical sharding prototype does not implement automatic replica failover, fully online
  multi-server migration, transactional notifications, or crash-safe distributed enrollment transactions.
  Recovery and maintenance requirements are described in auth-and-platform-design.md.

## Repeatable commands

From backend: `mvn test` (unit tests; opt-in integration skipped by default).
With disposable Docker Mongo/Redis running: `SIS_INTEGRATION=true mvn -Dtest=IntegrationTest test`.
For the exact emulator route: `SIS_INTEGRATION=true SIS_MONGO_EMULATOR=true mvn test`.
From frontend: `npm ci && npm run build`.

The integration test seeds academic data and temporarily changes shard topology. Do not run it against
an existing valuable database. Use fresh Docker volumes or an isolated MongoDB instance.
