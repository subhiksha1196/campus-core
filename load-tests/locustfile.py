"""
Locust workload for the notification, shortener and academic modules.

Scenarios (selected with --tags):
  shortener      Redis-cached redirects (90%) + creating new short links (10%)   <- proposed design
  baseline       MongoDB-only redirects                                           <- baseline to compare against
  notifications  create / list / unread-count
  academic       reads: autocomplete search, course list, timetable, attendance, grades, roster (needs demo data)
  registration   course registration: POST /api/academic/registrations, then DELETE to undo it
  writes         attendance sheet save + exam marks save (a LOADTEST exam, removed when the test stops)
  auth           OPT-IN. Sign-up (exercises the ID generator + global email reservation), login / me / logout,
                 wrong-password login. Needs SIS_AUTH_LIMIT raised on the server (default is 20/min per IP).
  ratelimit      OPT-IN. Pushes one throwaway account past the per-minute API limit and the per-IP auth limit,
                 and checks that rejections are HTTP 429 with Retry-After: 60.

"auth" and "ratelimit" only run when selected explicitly with --tags (they need special server settings).

Run one scenario by hand:
  locust -f locustfile.py --host http://localhost:8080 --tags shortener
Run all scenarios at 3 workload levels and collect CSVs:
  python run_load_tests.py                 # everything except ratelimit
  python run_load_tests.py --ratelimit     # only the rate-limiter scenario

Besides Locust's own CSVs, each run writes <csv-prefix>_outcomes.csv (what the server answered, e.g. how many
registrations succeeded vs. were rejected as "course full", and how many requests were allowed vs. rejected with 429).

Data these scenarios leave behind (all local/demo data):
  auth          students with e-mail @loadtest.example (named "Zload Test ...")
  registration  nothing (every successful registration is dropped again; the row stays with status DROPPED)
  writes        one extra attendance day per course (date SIS_LOAD_ATT_DATE, default today-400d). The LOADTEST exam is deleted.
"""
import csv
import os
import random
import uuid
from collections import defaultdict
from datetime import date, timedelta

import gevent
import requests
from locust import HttpUser, between, events, tag, task

TOKEN = os.environ.get("SIS_LOAD_TOKEN", "")
HEADERS = {"Authorization": "Bearer " + TOKEN}
NO_AUTH = {"Authorization": None}      # per-request override: send the call without the shared admin token

STUDENTS = []  # student IDs found through the autocomplete API (academic scenario)
COURSES = []   # course IDs for the same scenario
CODES = []   # short codes created before the test starts
HOT = []     # a small "popular" subset: 80% of redirects hit these (realistic skew)

ROSTERS = {}      # writes scenario: courseId -> [studentId, ...] taken from the real rosters
ISSUED_IDS = set()  # auth scenario: every student ID the server handed out (a repeat means the ID generator is broken)
ACCOUNT = {}      # auth scenario: throwaway student {email, password}
RL = {}           # ratelimit scenario: throwaway student {email, password, token}
SERVER_RL_BEFORE = {}   # server-side rate-limit counters when the test started
OUTCOMES = defaultdict(lambda: [0, 0.0])   # outcome -> [count, total response ms]

EXAM = "LOADTEST"
ATT_DATE = (date.fromisoformat(os.environ["SIS_LOAD_ATT_DATE"]) if os.environ.get("SIS_LOAD_ATT_DATE")
            else date.today() - timedelta(days=400))
AUTH_LIMIT_HINT = ("429: the per-IP login/signup limit was hit. Start the server with SIS_AUTH_LIMIT=1000000 "
                   "(default is 20/min) for this scenario.")


# ------------------------------------------------------------------ helpers
def selected_tags(environment):
    tags = getattr(environment, "tags", None)
    if tags is None:
        tags = getattr(getattr(environment, "parsed_options", None), "tags", None)
    return set(tags or [])


def wants(environment, *names):
    """True when no --tags were given (everything runs) or one of the named scenarios was selected."""
    tags = selected_tags(environment)
    return not tags or any(n in tags for n in names)


def explicit(environment, name):
    """True only when the scenario was selected with --tags (used for the opt-in scenarios)."""
    return name in selected_tags(environment)


def opted_in(user, name):
    if explicit(user.environment, name):
        return True
    gevent.sleep(1)     # opt-in scenario without --tags: stay idle instead of spinning
    return False


def note(outcome, resp=None):
    ms = 0.0
    elapsed = getattr(resp, "elapsed", None)
    if elapsed is not None:
        ms = elapsed.total_seconds() * 1000
    row = OUTCOMES[outcome]
    row[0] += 1
    row[1] += ms


def error_message(resp):
    try:
        body = resp.json()
        return str(body.get("message", "")) if isinstance(body, dict) else ""
    except Exception:
        return ""


def create_account(host, label):
    """Create a throwaway student through the public sign-up API."""
    email = f"{label}-{uuid.uuid4().hex[:12]}@loadtest.example"
    password = "Lt" + uuid.uuid4().hex[:16] + "!"
    r = requests.post(f"{host}/api/auth/register", timeout=30, json={
        "name": "Zload Test", "email": email, "department": "LOADTEST", "year": 1, "password": password})
    if r.status_code != 201:
        raise RuntimeError(f"Could not create the {label} test account (HTTP {r.status_code}). "
                           f"If this is 429, wait a minute or raise SIS_AUTH_LIMIT. {r.text[:200]}")
    return email, password


def login_token(host, email, password):
    r = requests.post(f"{host}/api/auth/login", json={"email": email, "password": password}, timeout=30)
    r.raise_for_status()
    return r.json()["token"]


def server_rate_counts(host):
    """Server-side sis.ratelimit.requests counters (admin only). With several app servers this reads one of them."""
    out = {}
    for scope in ("api", "auth"):
        for result in ("allowed", "rejected"):
            value = None
            try:
                r = requests.get(f"{host}/actuator/metrics/sis.ratelimit.requests", headers=HEADERS, timeout=10,
                                 params=[("tag", f"result:{result}"), ("tag", f"scope:{scope}")])
                value = 0.0     # the meter does not exist yet (404) until the first such request
                if r.ok:
                    for m in r.json().get("measurements", []):
                        if m.get("statistic") == "COUNT":
                            value = float(m.get("value", 0))
            except (requests.RequestException, ValueError):
                pass
            out[f"{scope} {result}"] = value
    return out


def classify_rejection(message):
    m = message.lower()
    if "another registration" in m:
        return "rejected: registration lock busy (409)"
    if "full" in m:
        return "rejected: course full"
    if "clash" in m:
        return "rejected: timetable clash"
    if "credit limit" in m:
        return "rejected: credit limit"
    if "already registered" in m:
        return "rejected: already registered"
    return "rejected: other"


def check_write(resp):
    if resp.status_code == 200:
        resp.success()
    elif resp.status_code == 429:
        resp.failure("429: API rate limit hit (raise SIS_API_LIMIT for load tests)")
    else:
        resp.failure(f"HTTP {resp.status_code}: {error_message(resp)[:80]}")


# ------------------------------------------------------------------ test setup / teardown
@events.test_start.add_listener
def reset_state(environment, **kwargs):
    OUTCOMES.clear()
    ISSUED_IDS.clear()
    ACCOUNT.clear()
    RL.clear()
    ROSTERS.clear()
    SERVER_RL_BEFORE.clear()


@events.test_start.add_listener
def seed_short_links(environment, **kwargs):
    """Create short links up front so every virtual user has something to redirect to."""
    CODES.clear()
    HOT.clear()
    if not wants(environment, "shortener", "baseline"):
        return
    host = environment.host
    for i in range(200):
        r = requests.post(f"{host}/api/urls",
                          json={"url": f"https://university.example/resource/{random.randint(1, 10**9)}"},
                          timeout=10, headers=HEADERS)
        r.raise_for_status()
        CODES.append(r.json()["code"])
    HOT.extend(CODES[:20])


@events.test_start.add_listener
def load_academic_ids(environment, **kwargs):
    """Find real student and course IDs for the academic scenarios (the backend creates demo data on startup)."""
    STUDENTS.clear()
    COURSES.clear()
    if not wants(environment, "academic", "registration", "writes"):
        return
    for letter in "aeiou":
        for kind, bucket in (("student", STUDENTS), ("course", COURSES)):
            r = requests.get(f"{environment.host}/api/academic/search",
                             params={"type": kind, "q": letter, "limit": 50}, timeout=10, headers=HEADERS)
            if r.ok:
                bucket.extend(x["id"] for x in r.json()["results"] if x["id"] not in bucket)
    r = requests.get(f"{environment.host}/api/academic/courses", timeout=10, headers=HEADERS)
    if r.ok:
        COURSES.extend(c["id"] for c in r.json().get("courses", []) if c.get("id") and c["id"] not in COURSES)


@events.test_start.add_listener
def load_write_rosters(environment, **kwargs):
    """writes scenario: remember the real roster of up to 12 courses, so marks/attendance only name enrolled students."""
    if not wants(environment, "writes"):
        return
    for cid in list(COURSES)[:12]:
        r = requests.get(f"{environment.host}/api/academic/courses/{cid}/roster", timeout=15, headers=HEADERS)
        if r.ok:
            ids = [row["id"] for row in r.json() if row.get("id")]
            if ids:
                ROSTERS[cid] = ids
    if not ROSTERS:
        print("WARNING: no course rosters found, the writes scenario will do nothing (is the demo data seeded, "
              "and is SIS_LOAD_TOKEN an admin token?)")


@events.test_start.add_listener
def prepare_auth(environment, **kwargs):
    if explicit(environment, "auth"):
        email, password = create_account(environment.host, "login")
        ACCOUNT.update(email=email, password=password)


@events.test_start.add_listener
def prepare_ratelimit(environment, **kwargs):
    if explicit(environment, "ratelimit"):
        email, password = create_account(environment.host, "ratelimit")
        RL.update(email=email, password=password, token=login_token(environment.host, email, password))
        SERVER_RL_BEFORE.update(server_rate_counts(environment.host))


@events.test_stop.add_listener
def finish(environment, **kwargs):
    host = environment.host
    for cid in ROSTERS:      # remove the LOADTEST exam again so real grades are untouched
        try:
            requests.delete(f"{host}/api/academic/courses/{cid}/exams/{EXAM}", headers=HEADERS, timeout=30)
        except requests.RequestException as e:
            print(f"WARNING: could not delete exam {EXAM} of course {cid}: {e}")
    rows = [(name, c, round(ms / c, 1) if c else "") for name, (c, ms) in sorted(OUTCOMES.items())]
    if RL:      # server-side view (approximate: includes the few actuator calls made by this script)
        after = server_rate_counts(host)
        for key, value in after.items():
            before = SERVER_RL_BEFORE.get(key)
            if value is not None and before is not None:
                rows.append((f"server counter: {key}", int(value - before), ""))
    if not rows:
        return
    print("\nOutcomes (what the server answered):")
    for name, count, avg in rows:
        print(f"  {name:<52} {count:>8}" + (f"   avg {avg} ms" if avg != "" else ""))
    prefix = getattr(getattr(environment, "parsed_options", None), "csv_prefix", None)
    if prefix:
        with open(f"{prefix}_outcomes.csv", "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["outcome", "count", "avg_ms"])
            w.writerows(rows)


def pick_code():
    return random.choice(HOT) if random.random() < 0.8 else random.choice(CODES)


class SisUser(HttpUser):
    wait_time = between(0.01, 0.1)

    def on_start(self):
        self.client.headers.update(HEADERS)
        self.student = f"S{random.randint(1, 5000):05d}"

    # ---- URL shortener (proposed: Redis cache-aside) ----
    @tag("shortener")
    @task(9)
    def redirect_cached(self):
        self.client.get(f"/s/{pick_code()}", allow_redirects=False, name="GET /s/{code}")

    @tag("shortener")
    @task(1)
    def create_short_link(self):
        self.client.post("/api/urls",
                         json={"url": f"https://university.example/new/{random.randint(1, 10**9)}"},
                         name="POST /api/urls")

    # ---- Baseline: no cache ----
    @tag("baseline")
    @task
    def redirect_baseline(self):
        self.client.get(f"/baseline/s/{pick_code()}", allow_redirects=False, name="GET /baseline/s/{code}")

    # ---- Notifications ----
    @tag("notifications")
    @task(2)
    def send_notification(self):
        self.client.post("/api/notifications", name="POST /api/notifications", json={
            "studentId": self.student, "type": "EXAM", "title": "Exam timetable",
            "message": "Timetable is out: https://university.example/exams/2026"})

    @tag("notifications")
    @task(4)
    def list_notifications(self):
        self.client.get("/api/notifications", params={"studentId": self.student}, name="GET /api/notifications")

    @tag("notifications")
    @task(6)
    def unread_badge(self):
        self.client.get("/api/notifications/unread-count", params={"studentId": self.student},
                        name="GET /api/notifications/unread-count")

    # ---- Academic features, reads ----
    @tag("academic")
    @task(6)
    def autocomplete(self):
        word = random.choice(["aar", "pri", "meer", "kar", "ani", "dist", "data", "comp", "mach", "net"])
        kind = "course" if word in ("dist", "data", "comp", "mach", "net") else "student"
        self.client.get("/api/academic/search", params={"type": kind, "q": word[: random.randint(2, len(word))]},
                        name="GET /api/academic/search")

    @tag("academic")
    @task(3)
    def course_list(self):
        self.client.get("/api/academic/courses", name="GET /api/academic/courses")

    @tag("academic")
    @task(3)
    def student_timetable(self):
        if STUDENTS:
            self.client.get("/api/academic/timetable", params={"studentId": random.choice(STUDENTS)},
                            name="GET /api/academic/timetable")

    @tag("academic")
    @task(2)
    def student_attendance_and_grades(self):
        if STUDENTS:
            sid = random.choice(STUDENTS)
            self.client.get(f"/api/academic/students/{sid}/attendance", name="GET /api/academic/students/{id}/attendance")
            self.client.get(f"/api/academic/students/{sid}/grades", name="GET /api/academic/students/{id}/grades")

    @tag("academic")
    @task(1)
    def course_roster(self):
        if COURSES:
            self.client.get(f"/api/academic/courses/{random.choice(COURSES)}/roster",
                            name="GET /api/academic/courses/{id}/roster")

    # ---- Course registration: register, then drop so the demo data does not fill up ----
    # Expected business rejections (course full, clash, credit limit, already registered, registration lock busy)
    # are not errors; they are counted per kind in the outcomes CSV. Anything else (401/403/404/5xx/429) is a failure.
    @tag("registration")
    @task
    def register_then_drop(self):
        if not STUDENTS or not COURSES:
            gevent.sleep(1)
            return
        sid, cid = random.choice(STUDENTS), random.choice(COURSES)
        registered = False
        with self.client.post("/api/academic/registrations", json={"studentId": sid, "courseId": cid},
                              name="POST /api/academic/registrations", catch_response=True) as r:
            if r.status_code == 200:
                registered = True
                note("registered", r)
                r.success()
            elif r.status_code in (400, 409):
                note(classify_rejection(error_message(r)), r)
                r.success()
            elif r.status_code == 429:
                note("rate limited (429)", r)
                r.failure("429: API rate limit hit (raise SIS_API_LIMIT for load tests)")
            else:
                note(f"unexpected HTTP {r.status_code}", r)
                r.failure(f"HTTP {r.status_code}: {error_message(r)[:80]}")
        if not registered:
            return
        attempts = 10
        for attempt in range(1, attempts + 1):
            with self.client.delete(f"/api/academic/registrations/{sid}/{cid}",
                                    name="DELETE /api/academic/registrations/{student}/{course}",
                                    catch_response=True) as d:
                if d.status_code == 200:
                    d.success()
                    return
                if d.status_code == 409 and attempt < attempts:      # registration lock busy: try again shortly
                    note("drop retried (registration lock busy)", d)
                    d.success()
                else:
                    note("drop FAILED, registration left in place", d)
                    d.failure(f"HTTP {d.status_code}: {error_message(d)[:80]}")
                    return
            gevent.sleep(random.uniform(0.05, 0.2))

    # ---- Attendance and marks writes ----
    @tag("writes")
    @task(3)
    def save_attendance(self):
        if not ROSTERS:
            gevent.sleep(1)
            return
        cid = random.choice(list(ROSTERS))
        roster = ROSTERS[cid]
        absent = random.sample(roster, len(roster) // 10)
        with self.client.post(f"/api/academic/courses/{cid}/attendance", name="POST /api/academic/courses/{id}/attendance",
                              json={"date": ATT_DATE.isoformat(), "absent": absent, "onDuty": []},
                              catch_response=True) as r:
            check_write(r)

    @tag("writes")
    @task(2)
    def save_marks(self):
        if not ROSTERS:
            gevent.sleep(1)
            return
        cid = random.choice(list(ROSTERS))
        marks = {sid: round(random.uniform(0, 50) * 2) / 2 for sid in ROSTERS[cid]}
        with self.client.post(f"/api/academic/courses/{cid}/marks", name="POST /api/academic/courses/{id}/marks",
                              json={"exam": EXAM, "maxMarks": 50, "marks": marks}, catch_response=True) as r:
            check_write(r)

    @tag("writes")
    @task(1)
    def read_back_marks(self):
        """A read of the data the writers are changing (sheet for the LOADTEST exam), so reads and writes overlap."""
        if not ROSTERS:
            gevent.sleep(1)
            return
        cid = random.choice(list(ROSTERS))
        self.client.get(f"/api/academic/courses/{cid}/exams/{EXAM}/marks",
                        name="GET /api/academic/courses/{id}/exams/{exam}/marks")

    # ---- Sign-up and sessions (opt-in): exercises the ID generator, the e-mail reservation, PBKDF2 and the auth limiter ----
    @tag("auth")
    @task(2)
    def register_student(self):
        if not opted_in(self, "auth"):
            return
        email = f"load-{uuid.uuid4().hex[:16]}@loadtest.example"
        with self.client.post("/api/auth/register", name="POST /api/auth/register", headers=NO_AUTH, catch_response=True,
                              json={"name": "Zload Test", "email": email, "department": "LOADTEST", "year": 1,
                                    "password": "Lt" + uuid.uuid4().hex[:16] + "!"}) as r:
            if r.status_code == 201:
                try:
                    sid = r.json().get("id")
                except Exception:
                    sid = None
                if not sid or not str(sid).startswith("S"):
                    note("signup: malformed response", r)
                    r.failure("201 without a valid student id")
                elif sid in ISSUED_IDS:
                    note("signup: DUPLICATE student ID", r)
                    r.failure("duplicate student ID issued by the ID generator")
                else:
                    ISSUED_IDS.add(sid)
                    note("signup: ok", r)
                    r.success()
            elif r.status_code == 429:
                note("signup: rate limited (429)", r)
                r.failure(AUTH_LIMIT_HINT)
            else:
                note(f"signup: HTTP {r.status_code}", r)
                r.failure(f"HTTP {r.status_code}: {error_message(r)[:80]}")

    @tag("auth")
    @task(6)
    def login_me_logout(self):
        if not opted_in(self, "auth"):
            return
        token = None
        with self.client.post("/api/auth/login", name="POST /api/auth/login", headers=NO_AUTH, catch_response=True,
                              json={"email": ACCOUNT["email"], "password": ACCOUNT["password"]}) as r:
            if r.status_code == 200:
                try:
                    token = r.json().get("token")
                except Exception:
                    token = None
                if token:
                    note("login: ok", r)
                    r.success()
                else:
                    note("login: malformed response", r)
                    r.failure("200 without a token")
            elif r.status_code == 429:
                note("login: rate limited (429)", r)
                r.failure(AUTH_LIMIT_HINT)
            else:
                note(f"login: HTTP {r.status_code}", r)
                r.failure(f"HTTP {r.status_code}: {error_message(r)[:80]}")
        if not token:
            return
        bearer = {"Authorization": "Bearer " + token}
        self.client.get("/api/auth/me", name="GET /api/auth/me", headers=bearer)
        self.client.post("/api/auth/logout", name="POST /api/auth/logout", headers=bearer)    # keeps Redis sessions from piling up

    @tag("auth")
    @task(1)
    def login_wrong_password(self):
        if not opted_in(self, "auth"):
            return
        with self.client.post("/api/auth/login", name="POST /api/auth/login (wrong password)", headers=NO_AUTH,
                              catch_response=True,
                              json={"email": ACCOUNT["email"], "password": "definitely-not-the-password"}) as r:
            if r.status_code == 401:
                note("bad login: 401", r)
                r.success()
            elif r.status_code == 429:
                note("bad login: rate limited (429)", r)
                r.failure(AUTH_LIMIT_HINT)
            else:
                note(f"bad login: HTTP {r.status_code}", r)
                r.failure(f"expected 401, got HTTP {r.status_code}")

    # ---- Rate limiter (opt-in): one dedicated account, so the shared admin token is never throttled ----
    @tag("ratelimit")
    @task(20)
    def api_limit_probe(self):
        """Hammer a cheap authenticated endpoint. Allowed = 200; over the limit = 429 with Retry-After: 60."""
        if not opted_in(self, "ratelimit"):
            return
        with self.client.get("/api/auth/me", name="GET /api/auth/me [api limit]", catch_response=True,
                             headers={"Authorization": "Bearer " + RL["token"]}) as r:
            if r.status_code == 200:
                note("api: allowed (200)", r)
                r.success()
            elif r.status_code == 429:
                note("api: rejected (429)", r)
                if r.headers.get("Retry-After") == "60":
                    r.success()
                else:
                    r.failure("429 without Retry-After: 60")
            else:
                note(f"api: unexpected HTTP {r.status_code}", r)
                r.failure(f"HTTP {r.status_code}")

    @tag("ratelimit")
    @task(1)
    def auth_limit_probe(self):
        """Repeated wrong-password logins from this IP: 401 until the per-IP auth limit, then 429."""
        if not opted_in(self, "ratelimit"):
            return
        with self.client.post("/api/auth/login", name="POST /api/auth/login [auth limit]", headers=NO_AUTH,
                              catch_response=True,
                              json={"email": RL["email"], "password": "definitely-not-the-password"}) as r:
            if r.status_code == 401:
                note("auth: allowed (401 wrong password)", r)
                r.success()
            elif r.status_code == 429:
                note("auth: rejected (429)", r)
                if r.headers.get("Retry-After") == "60":
                    r.success()
                else:
                    r.failure("429 without Retry-After: 60")
            else:
                note(f"auth: unexpected HTTP {r.status_code}", r)
                r.failure(f"HTTP {r.status_code}")
