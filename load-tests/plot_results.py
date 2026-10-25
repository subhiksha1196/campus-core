"""
Turns the CSV files in results/ into tables (results/summary.md) and graphs (results/*.png).
Run after run_load_tests.py (and optionally crawler_benchmark.py).
"""
import glob
import json
import os

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

RES = "results"
LEVELS = ["low", "medium", "high"]
USERS = {"low": 10, "medium": 50, "high": 200}
ROW = {"shortener": "GET /s/{code}", "baseline": "GET /baseline/s/{code}", "notifications": "Aggregated",
       "academic": "Aggregated", "registration": "POST /api/academic/registrations",
       "writes": "POST /api/academic/courses/{id}/marks", "auth": "POST /api/auth/login"}
LABEL = {"shortener": "Redis-cached redirect", "baseline": "DB-only redirect (baseline)",
         "notifications": "Notifications API", "academic": "Academic reads",
         "registration": "Course registration", "writes": "Marks save", "auth": "Login"}


def load_stats():
    rows = []
    for tag, name in ROW.items():
        for level in LEVELS:
            p = f"{RES}/{tag}_{level}_stats.csv"
            if not os.path.exists(p):
                continue
            df = pd.read_csv(p)
            r = df[df["Name"] == name]
            if r.empty:
                continue
            r = r.iloc[0]
            req, fail = int(r["Request Count"]), int(r["Failure Count"])
            rows.append({"scenario": LABEL[tag], "level": level, "users": USERS[level], "requests": req,
                         "failures": fail, "error_pct": round(100 * fail / max(req, 1), 2),
                         "avg_ms": round(r["Average Response Time"], 1), "p95_ms": round(r["95%"], 1),
                         "rps": round(r["Requests/s"], 1)})
    return pd.DataFrame(rows)


def load_resources():
    rows = []
    for p in sorted(glob.glob(f"{RES}/resources_*.csv")):
        tag, level = os.path.basename(p)[len("resources_"):-4].rsplit("_", 1)
        df = pd.read_csv(p)
        if df.empty:
            continue
        rows.append({"scenario": LABEL.get(tag, tag), "level": level,
                     "avg_system_cpu_pct": round(df.cpu_pct.mean(), 1),
                     "avg_java_cpu_pct": round(df.java_cpu_pct.mean(), 1),
                     "max_java_rss_mb": round(df.java_rss_mb.max(), 1)})
    return pd.DataFrame(rows)


def outcomes_section(parts):
    """What the server answered (registered vs course full, allowed vs rejected with 429, ...)."""
    rows = []
    for p in sorted(glob.glob(f"{RES}/*_outcomes.csv")):
        tag, level = os.path.basename(p)[:-len("_outcomes.csv")].rsplit("_", 1)
        df = pd.read_csv(p)
        for _, r in df.iterrows():
            rows.append({"scenario": tag, "level": level, "outcome": r["outcome"], "count": int(r["count"]),
                         "avg_ms": r["avg_ms"] if pd.notna(r["avg_ms"]) else ""})
    if rows:
        parts.append("## Server outcomes (registration results, rate-limiter rejections, sign-ups)\n\n"
                     + md_table(pd.DataFrame(rows)) + "\n")


def md_table(df):
    cols = list(df.columns)
    lines = ["| " + " | ".join(cols) + " |", "|" + "|".join("---" for _ in cols) + "|"]
    for _, r in df.iterrows():
        lines.append("| " + " | ".join(str(r[c]) for c in cols) + " |")
    return "\n".join(lines)


def grouped_bar(df, value, ylabel, title, fname):
    pivot = df.pivot(index="level", columns="scenario", values=value).reindex(LEVELS)
    ax = pivot.plot(kind="bar", figsize=(7, 4), rot=0)
    ax.set_xlabel("workload level (low=10, medium=50, high=200 users)")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    plt.savefig(f"{RES}/{fname}", dpi=150)
    plt.close()


def crawler_section(parts):
    p = f"{RES}/crawler_benchmark.csv"
    if not os.path.exists(p):
        return
    df = pd.read_csv(p)
    df["config"] = df.apply(lambda r: "sequential" if r["mode"] == "sequential" else f"{r['threads']} threads", axis=1)
    g = df.groupby("config", sort=False)[["pagesPerSec", "listingsPerSec", "fetchMs"]].mean().round(2).reset_index()
    parts.append("## Crawl throughput (mean of repeats)\n\n" + md_table(g) + "\n\n![crawler](crawler_throughput.png)\n")
    ax = g.plot(kind="bar", x="config", y="pagesPerSec", legend=False, figsize=(7, 4), rot=0)
    ax.set_ylabel("pages / second")
    ax.set_title("Crawler throughput: sequential baseline vs parallel")
    ax.grid(axis="y", alpha=0.3)
    plt.tight_layout()
    plt.savefig(f"{RES}/crawler_throughput.png", dpi=150)
    plt.close()


def scaling_section(parts):
    p = f"{RES}/scaling_summary.csv"
    if not os.path.exists(p):
        return
    df = pd.read_csv(p).drop_duplicates(["lb", "servers"], keep="last").sort_values(["lb", "servers"])
    parts.append("## Application-server scaling (nginx in front of N servers)\n\n" + md_table(df) +
                 "\n\n![scaling](scaling_throughput.png) ![latency](scaling_latency.png)\n")
    for value, ylabel, fname in (("rps", "requests / second", "scaling_throughput.png"),
                                 ("p95_ms", "95th percentile (ms)", "scaling_latency.png")):
        pivot = df.pivot(index="servers", columns="lb", values=value)
        ax = pivot.plot(kind="bar", figsize=(7, 4), rot=0)
        ax.set_xlabel("application servers")
        ax.set_ylabel(ylabel)
        ax.set_title("Effect of adding application servers")
        ax.grid(axis="y", alpha=0.3)
        plt.tight_layout()
        plt.savefig(f"{RES}/{fname}", dpi=150)
        plt.close()
    q = f"{RES}/scaling_instances.csv"
    if os.path.exists(q):
        inst = pd.read_csv(q).drop_duplicates(["lb", "servers", "instance"], keep="last")
        parts.append("### Load per application server\n\n" + md_table(inst) + "\n\n![distribution](scaling_distribution.png)\n")
        for lb, g in inst.groupby("lb"):
            pivot = g.pivot(index="servers", columns="instance", values="share_pct")
            ax = pivot.plot(kind="bar", figsize=(7, 4), rot=0)
            ax.set_xlabel("application servers")
            ax.set_ylabel("share of requests (%)")
            ax.set_title(f"Request distribution per server ({lb})")
            ax.grid(axis="y", alpha=0.3)
            plt.tight_layout()
            plt.savefig(f"{RES}/scaling_distribution{'' if lb == 'least_conn' else '_' + lb}.png", dpi=150)
            plt.close()


def failover_section(parts):
    h, e = f"{RES}/failover_stats_history.csv", f"{RES}/failover_events.json"
    if not (os.path.exists(h) and os.path.exists(e)):
        return
    df = pd.read_csv(h)
    df = df[df["Name"] == "Aggregated"].copy()
    events = json.load(open(e))
    t0 = df["Timestamp"].min()
    df["t"] = df["Timestamp"] - t0
    fig, ax = plt.subplots(figsize=(8, 4))
    ax.plot(df["t"], df["Requests/s"], label="requests/s")
    ax.plot(df["t"], df["Failures/s"], label="failures/s")
    for name, ts in events.items():
        ax.axvline(ts - t0, linestyle="--", color="gray")
        ax.text(ts - t0, ax.get_ylim()[1] * 0.98, " " + name, rotation=90, va="top")
    ax.set_xlabel("seconds since start")
    ax.set_ylabel("per second")
    ax.set_title("Removing and re-adding an application server under load")
    ax.legend()
    ax.grid(alpha=0.3)
    plt.tight_layout()
    plt.savefig(f"{RES}/failover_timeline.png", dpi=150)
    plt.close()
    parts.append("## Removing / re-adding an application server\n\n![failover](failover_timeline.png)\n")


def main():
    parts = ["# Performance results\n"]
    stats = load_stats()
    if not stats.empty:
        parts.append("## Load test results\n\n" + md_table(stats) + "\n")
        grouped_bar(stats, "avg_ms", "average response time (ms)", "Average response time", "avg_response_time.png")
        grouped_bar(stats, "p95_ms", "95th percentile (ms)", "95th percentile response time", "p95_response_time.png")
        grouped_bar(stats, "rps", "requests / second", "Throughput", "throughput.png")
        grouped_bar(stats, "error_pct", "failed requests (%)", "Error rate", "error_rate.png")
        parts.append("![avg](avg_response_time.png) ![p95](p95_response_time.png) "
                     "![rps](throughput.png) ![err](error_rate.png)\n")
    res = load_resources()
    if not res.empty:
        parts.append("## Resource usage\n\n" + md_table(res) + "\n")
        pivot = res.pivot(index="level", columns="scenario", values="avg_java_cpu_pct").reindex(LEVELS)
        ax = pivot.plot(kind="bar", figsize=(7, 4), rot=0)
        ax.set_ylabel("Java process CPU (%, summed over cores)")
        ax.set_title("Resource usage during load test")
        ax.grid(axis="y", alpha=0.3)
        plt.tight_layout()
        plt.savefig(f"{RES}/resource_usage.png", dpi=150)
        plt.close()
        parts.append("![cpu](resource_usage.png)\n")
    outcomes_section(parts)
    scaling_section(parts)
    failover_section(parts)
    crawler_section(parts)
    with open(f"{RES}/summary.md", "w") as f:
        f.write("\n".join(parts))
    print("Wrote", f"{RES}/summary.md", "and PNG graphs")


if __name__ == "__main__":
    main()
