"""
Application-server scaling experiments (needs Docker + the compose stack from the project root).

  # 1) Throughput / latency / load distribution with 1, 2 and 3 application servers
  python scale_experiment.py scale --lb least_conn
  python scale_experiment.py scale --lb consistent          # nginx consistent-hash routing on the request URI

  # 2) Remove and re-add a server while traffic is running (failover impact)
  python scale_experiment.py failover --lb least_conn

Run from the load-tests folder with SIS_DEMO_PASSWORD set (same value as in .env) and SIS_API_LIMIT=1000000 in .env.
Writes results/scaling_summary.csv, results/scaling_instances.csv, results/failover_*.  Then: python plot_results.py
"""
import argparse
import csv
import json
import os
import subprocess
import sys
import time

import requests

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
APPS = ["app1", "app2", "app3"]
PORTS = {"app1": 8081, "app2": 8082, "app3": 8083}
LB = {"least_conn": "least_conn;", "consistent": "hash $request_uri consistent;", "roundrobin": "# round-robin"}
LB_URL = "http://localhost:8080"


def run(cmd, env=None, check=True):
    print("$", " ".join(cmd), flush=True)
    return subprocess.run(cmd, cwd=ROOT, env=env, check=check)


def compose_env(lb, apps):
    env = os.environ.copy()
    env["LB_DIRECTIVE"] = LB[lb]
    env["APP_SERVERS"] = " ".join(f"server {a}:8080 max_fails=2 fail_timeout=5s;" for a in apps)
    return env


def wait_ready(url, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if requests.get(url + "/actuator/health", timeout=3).ok:
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    sys.exit(f"Timed out waiting for {url}")


def set_topology(apps, lb):
    """Run exactly `apps` behind nginx, using load-balancing method `lb`."""
    env = compose_env(lb, apps)
    run(["docker", "compose", "up", "-d", "--wait", "mongo", "redis"], env)
    run(["docker", "compose", "up", "-d", "--no-deps", "--build", APPS[0]], env)   # app1 first: it seeds the demo data
    wait_ready(f"http://localhost:{PORTS[APPS[0]]}")
    others = [a for a in apps if a != APPS[0]]
    if others:
        run(["docker", "compose", "up", "-d", "--no-deps", *others], env)
    unused = [a for a in APPS if a not in apps]
    if unused:
        run(["docker", "compose", "stop", *unused], env, check=False)
    for a in apps:
        wait_ready(f"http://localhost:{PORTS[a]}")
    run(["docker", "compose", "up", "-d", "--no-deps", "--force-recreate", "nginx"], env)
    time.sleep(3)
    wait_ready(LB_URL)


def login():
    pw = os.environ.get("SIS_DEMO_PASSWORD")
    if not pw:
        sys.exit("Set SIS_DEMO_PASSWORD (same value as in .env).")
    r = requests.post(f"{LB_URL}/api/auth/login", timeout=15,
                      json={"email": os.environ.get("SIS_ADMIN_EMAIL", "admin@ssn.edu.in"), "password": pw})
    r.raise_for_status()
    os.environ["SIS_LOAD_TOKEN"] = r.json()["token"]
    return os.environ["SIS_LOAD_TOKEN"]


def instance_snapshot(apps, token):
    out = {}
    for a in apps:
        r = requests.get(f"http://localhost:{PORTS[a]}/api/instance", headers={"Authorization": "Bearer " + token}, timeout=10)
        r.raise_for_status()
        out[a] = r.json()
    return out


def locust_cmd(users, duration, scenario, prefix):
    return [sys.executable, "-m", "locust", "-f", "locustfile.py", "--headless", "-u", str(users),
            "-r", str(max(5, users // 5)), "-t", f"{duration}s", "--host", LB_URL, "--tags", scenario,
            "--csv", prefix, "--only-summary"]


def aggregated(prefix):
    with open(prefix + "_stats.csv", newline="") as f:
        for row in csv.DictReader(f):
            if row["Name"] == "Aggregated":
                return row
    sys.exit("No Aggregated row in " + prefix + "_stats.csv")


def append(path, fields, row):
    new = not os.path.exists(path)
    with open(path, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        if new:
            w.writeheader()
        w.writerow(row)


def scale(args):
    os.makedirs(args.out, exist_ok=True)
    for n in args.servers:
        apps = APPS[:n]
        print(f"\n===== {n} application server(s), lb={args.lb} =====", flush=True)
        set_topology(apps, args.lb)
        token = login()
        subprocess.run(locust_cmd(args.users, 10, args.scenario, os.path.join(args.out, "_warmup")), check=True)   # JIT warm-up
        before = instance_snapshot(apps, token)
        prefix = os.path.join(args.out, f"scaling_{args.lb}_{n}servers")
        subprocess.run(locust_cmd(args.users, args.duration, args.scenario, prefix), check=True)
        after = instance_snapshot(apps, token)

        row = aggregated(prefix)
        req, fail = int(row["Request Count"]), int(row["Failure Count"])
        append(os.path.join(args.out, "scaling_summary.csv"),
               ["lb", "servers", "users", "duration_s", "requests", "failures", "error_pct", "avg_ms", "p95_ms", "rps"],
               {"lb": args.lb, "servers": n, "users": args.users, "duration_s": args.duration, "requests": req,
                "failures": fail, "error_pct": round(100 * fail / max(req, 1), 2),
                "avg_ms": round(float(row["Average Response Time"]), 1), "p95_ms": round(float(row["95%"]), 1),
                "rps": round(float(row["Requests/s"]), 1)})
        total = sum(after[a]["requestsServed"] - before[a]["requestsServed"] for a in apps) or 1
        for a in apps:
            served = after[a]["requestsServed"] - before[a]["requestsServed"]
            append(os.path.join(args.out, "scaling_instances.csv"),
                   ["lb", "servers", "instance", "requests", "share_pct", "errors5xx", "mean_ms"],
                   {"lb": args.lb, "servers": n, "instance": a, "requests": served,
                    "share_pct": round(100 * served / total, 1),
                    "errors5xx": after[a]["errors5xx"] - before[a]["errors5xx"], "mean_ms": after[a]["meanMs"]})
        print(f"-> {n} server(s): {row['Requests/s']} req/s, p95 {row['95%']} ms, {fail} failures", flush=True)
        time.sleep(5)


def failover(args):
    os.makedirs(args.out, exist_ok=True)
    set_topology(["app1", "app2"], args.lb)
    login()
    prefix = os.path.join(args.out, "failover")
    proc = subprocess.Popen(locust_cmd(args.users, args.duration, args.scenario, prefix))
    events = {}
    time.sleep(args.duration / 3)
    events["app2 stopped"] = int(time.time())
    run(["docker", "compose", "stop", "app2"], compose_env(args.lb, ["app1", "app2"]))
    time.sleep(args.duration / 3)
    events["app2 started"] = int(time.time())
    run(["docker", "compose", "start", "app2"], compose_env(args.lb, ["app1", "app2"]))
    proc.wait()
    with open(os.path.join(args.out, "failover_events.json"), "w") as f:
        json.dump(events, f)
    row = aggregated(prefix)
    print(f"-> failover run: {row['Request Count']} requests, {row['Failure Count']} failures")
    print("If traffic never returns to app2 after it restarts, run: docker compose restart nginx "
          "(nginx resolves container IPs only at start-up).")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="mode", required=True)
    for name in ("scale", "failover"):
        p = sub.add_parser(name)
        p.add_argument("--lb", choices=list(LB), default="least_conn")
        p.add_argument("--users", type=int, default=100)
        p.add_argument("--duration", type=int, default=60, help="seconds per run")
        p.add_argument("--scenario", default="shortener", choices=["shortener", "baseline", "notifications", "academic"])
        p.add_argument("--out", default="results")
        if name == "scale":
            p.add_argument("--servers", type=int, nargs="+", default=[1, 2, 3])
    args = ap.parse_args()
    scale(args) if args.mode == "scale" else failover(args)


if __name__ == "__main__":
    main()
