"""
Crawl-throughput benchmark: sequential baseline vs parallel crawler with 2..32 threads.
Each configuration is repeated (default 3x); listings are cleared before every run so the work is identical.

  python crawler_benchmark.py --host http://localhost:8080 --repeats 3
Writes results/crawler_benchmark.csv  (plotted by plot_results.py)
"""
import argparse
import csv
import os

import requests
import os
_original_request = requests.sessions.Session.request
def _authenticated(self, method, url, **kwargs):
    kwargs.setdefault("headers", {})["Authorization"] = "Bearer " + os.environ.get("SIS_LOAD_TOKEN", "")
    return _original_request(self, method, url, **kwargs)
requests.sessions.Session.request = _authenticated

def ensure_token(host):
    if os.environ.get("SIS_LOAD_TOKEN"):
        return
    pw = os.environ.get("SIS_DEMO_PASSWORD")
    if not pw:
        raise SystemExit("Set SIS_DEMO_PASSWORD or SIS_LOAD_TOKEN")
    r = requests.post(f"{host}/api/auth/login", timeout=15,
                      json={"email": os.environ.get("SIS_ADMIN_EMAIL", "admin@ssn.edu.in"), "password": pw})
    r.raise_for_status()
    os.environ["SIS_LOAD_TOKEN"] = r.json()["token"]


CONFIGS = [("sequential", 1)] + [("parallel", t) for t in (2, 4, 8, 16, 32)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="http://localhost:8080")
    ap.add_argument("--repeats", type=int, default=3)
    ap.add_argument("--out", default="results")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    ensure_token(args.host)

    path = os.path.join(args.out, "crawler_benchmark.csv")
    fields = ["mode", "threads", "pagesFetched", "pageErrors", "listingsFound", "fetchMs", "pagesPerSec", "listingsPerSec"]
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for mode, threads in CONFIGS:
            for i in range(args.repeats):
                requests.delete(f"{args.host}/api/listings", timeout=30)
                r = requests.post(f"{args.host}/api/crawl", json={"mode": mode, "threads": threads}, timeout=900)
                r.raise_for_status()
                run = r.json()
                w.writerow({k: run[k] for k in fields})
                f.flush()
                print(f"{mode:10s} threads={threads:2d} run {i + 1}: {run['pagesPerSec']} pages/s "
                      f"({run['pagesFetched']} pages, {run['pageErrors']} errors, {run['fetchMs']} ms)")
    print("Saved", path)


if __name__ == "__main__":
    main()
