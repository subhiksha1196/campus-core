"""
Runs every scenario at three workload levels (low / medium / high number of concurrent users)
and records CPU / memory while each run is in progress.

  python run_load_tests.py                      # defaults: 30 s per run against localhost:8080
  python run_load_tests.py --duration 60 --host http://localhost:8080
  python run_load_tests.py --ratelimit          # ONLY the rate-limiter scenario (one run, always >= 75 s)
  python run_load_tests.py --skip-auth          # leave out the sign-up/login scenario

Scenarios run by default: shortener, baseline, notifications, academic, registration, writes, auth.

Server settings these scenarios need (set them when you start the app, then restart it):
  auth        SIS_AUTH_LIMIT=1000000   (default is 20/min per IP, which would answer almost everything with 429)
  registration, writes, academic, ...   SIS_API_LIMIT=1000000  (default 600/min per account would throttle the admin token)
  ratelimit   leave SIS_API_LIMIT and SIS_AUTH_LIMIT at their defaults (or lower SIS_API_LIMIT, e.g. 100) so the
              limits are actually reached

Output goes to results/:
  <scenario>_<level>_stats.csv       (Locust)
  <scenario>_<level>_outcomes.csv    (what the server answered: registered / course full / 429 allowed vs rejected ...)
  resources_<scenario>_<level>.csv
Then run:  python plot_results.py
"""
import argparse
import csv
import os
import subprocess
import sys
import threading
import time

import psutil
import requests

LEVELS = {"low": 10, "medium": 50, "high": 200}      # concurrent virtual users
SCENARIOS = ["shortener", "baseline", "notifications", "academic", "registration", "writes", "auth"]
RATELIMIT_USERS = 20          # enough to exceed the default 600 requests/minute quickly
RATELIMIT_MIN_SECONDS = 75    # the limit is per minute, so the run has to cover more than one window


def ensure_token(host):
    """Every /api call needs a login now. Reuse SIS_LOAD_TOKEN, or log in as the seeded admin."""
    if os.environ.get("SIS_LOAD_TOKEN"):
        return
    pw = os.environ.get("SIS_DEMO_PASSWORD")
    if not pw:
        sys.exit("Set SIS_DEMO_PASSWORD (same value the app started with) or SIS_LOAD_TOKEN.")
    r = requests.post(f"{host}/api/auth/login", timeout=15,
                      json={"email": os.environ.get("SIS_ADMIN_EMAIL", "admin@ssn.edu.in"), "password": pw})
    r.raise_for_status()
    os.environ["SIS_LOAD_TOKEN"] = r.json()["token"]
    print("Logged in as admin; token acquired.")


def docker_usage():
    """Sum CPU % and memory (MiB) of the sis-app* containers (the application servers)."""
    try:
        out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}"],
                             capture_output=True, text=True, timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return 0.0, 0.0
    cpu = mem = 0.0
    for line in out.splitlines():
        name, c, m = (line.split("|") + ["", "", ""])[:3]
        if not name.startswith("sis-app"):
            continue
        cpu += float(c.strip("%") or 0)
        used = m.split("/")[0].strip()
        num = float("".join(ch for ch in used if ch in "0123456789.") or 0)
        mem += num * (1024 if "GiB" in used else 1 if "MiB" in used else 1 / 1024)
    return cpu, mem


class ResourceSampler(threading.Thread):
    """Samples system CPU/memory and the Java process (the Spring Boot app) once a second."""

    def __init__(self, path, docker=False):
        super().__init__(daemon=True)
        self.path = path
        self.docker = docker
        self.stop_evt = threading.Event()

    def run(self):
        psutil.cpu_percent(None)
        with open(self.path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["ts", "cpu_pct", "mem_pct", "java_cpu_pct", "java_rss_mb"])
            while not self.stop_evt.wait(1.0):
                java_cpu, java_rss = 0.0, 0.0
                if self.docker:   # app servers run in containers: read their usage from docker stats
                    java_cpu, java_rss = docker_usage()
                for p in ([] if self.docker else psutil.process_iter(["name"])):
                    if "java" in (p.info["name"] or "").lower():
                        try:
                            java_cpu += p.cpu_percent(None)       # can exceed 100 on multi-core machines
                            java_rss += p.memory_info().rss / 1048576
                        except psutil.Error:
                            pass
                w.writerow([round(time.time(), 1), psutil.cpu_percent(None),
                            psutil.virtual_memory().percent, round(java_cpu, 1), round(java_rss, 1)])


def run_one(args, scenario, level, users, duration, docker):
    print(f"\n=== {scenario} / {level} ({users} users, {duration}s) ===", flush=True)
    sampler = ResourceSampler(os.path.join(args.out, f"resources_{scenario}_{level}.csv"), docker)
    sampler.start()
    cmd = [sys.executable, "-m", "locust", "-f", "locustfile.py", "--headless",
           "-u", str(users), "-r", str(max(5, users // 5)), "-t", f"{duration}s",
           "--host", args.host, "--tags", scenario,
           "--csv", os.path.join(args.out, f"{scenario}_{level}"), "--only-summary"]
    try:
        # no check=True: some scenarios (auth, ratelimit) are expected to record failures and exit non-zero
        subprocess.run(cmd)
    finally:
        sampler.stop_evt.set()
        sampler.join(timeout=3)
    time.sleep(3)   # let the server settle between runs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:8080")
    ap.add_argument("--duration", type=int, default=30, help="seconds per run")
    ap.add_argument("--out", default="results")
    ap.add_argument("--docker", action="store_true", help="app servers run in Docker: sample them with docker stats")
    ap.add_argument("--ratelimit", action="store_true",
                    help="run ONLY the rate-limiter scenario (pushes one account past the per-minute limit)")
    ap.add_argument("--skip-auth", action="store_true",
                    help="skip the sign-up/login scenario (it needs SIS_AUTH_LIMIT raised on the server)")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ensure_token(args.host)

    if args.ratelimit:
        run_one(args, "ratelimit", "high", RATELIMIT_USERS, max(args.duration, RATELIMIT_MIN_SECONDS), args.docker)
        return

    for scenario in SCENARIOS:
        if scenario == "auth" and args.skip_auth:
            continue
        for level, users in LEVELS.items():
            run_one(args, scenario, level, users, args.duration, args.docker)


if __name__ == "__main__":
    main()
