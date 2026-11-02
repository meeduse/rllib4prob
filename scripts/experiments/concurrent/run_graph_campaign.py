#!/usr/bin/env python3
"""Run an isolated, fail-closed model-by-model graph-construction campaign.

Each model is a SEPARATE JVM. Within that JVM, one untimed reference graph is
built, every variant has an untimed warm-up, then the measured variants are
rotated by round to reduce a fixed ordering bias. No RL/planning work is run.

No timeout, mismatch, interrupted run or unfinished CSV is counted as success.
"""

from __future__ import annotations

import argparse
import csv
import datetime as dt
import hashlib
import json
import os
import platform
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MODELS = (
    "tictactoe", "frozenlake4", "frozenlake10", "taxi5", "taxi10",
    "taxi30", "autov2", "mountaincar", "interlocking", "puzzle",
)
MACHINES = {
    "tictactoe": "TicTacToe/tictac.mch",
    "frozenlake4": "frozen_lake/FrozenLakeGrid_4_4.mch",
    "frozenlake10": "frozen_lake/FrozenLakeGrid_10_10.mch",
    "taxi5": "taxi_driver/SafeTaxiDriver_5_5.mch",
    "taxi10": "taxi_driver/SafeTaxiDriver_10_10.mch",
    "taxi30": "taxi_driver/SafeTaxiDriver_30_30.mch",
    "autov2": "autoV2/HighwayPerception_Q.mch",
    "mountaincar": "Mountain_Car/MountainCarSmall.mch",
    "interlocking": "interlocking/interlocking.mch",
    "puzzle": "puzzle/Puzzle8.mch",
}


def invoke_short(command: list[str]) -> str:
    try:
        run = subprocess.run(command, cwd=ROOT, text=True, capture_output=True,
                             timeout=20, check=False)
        return (run.stdout or run.stderr).strip().splitlines()[0] if run.returncode == 0 else "unavailable"
    except (OSError, subprocess.TimeoutExpired, IndexError):
        return "unavailable"


def git_info() -> dict[str, str | bool]:
    try:
        diff = subprocess.run(["git", "diff", "--quiet"], cwd=ROOT,
                              capture_output=True, timeout=20, check=False)
        dirty = diff.returncode != 0
    except (OSError, subprocess.TimeoutExpired):
        dirty = True
    return {
        "commit": invoke_short(["git", "rev-parse", "HEAD"]),
        "branch": invoke_short(["git", "branch", "--show-current"]),
        "tracked_diff": dirty,
    }


def hash_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1048576), b""):
            h.update(part)
    return h.hexdigest()


def version_of(tool: str) -> str:
    command = {"java": ["java", "-version"], "maven": ["mvn", "-version"]}[tool]
    try:
        cp = subprocess.run(command, cwd=ROOT, capture_output=True, text=True,
                            timeout=30, check=False)
        return ((cp.stdout or "") + "\n" + (cp.stderr or "")).strip().splitlines()[0]
    except (IndexError, OSError, subprocess.TimeoutExpired):
        return "unavailable"


def machine_manifest() -> dict[str, str]:
    tree = ROOT / "src/main/resources"
    result = {}
    for path in sorted(tree.rglob("*")):
        if path.is_file():
            result[str(path.relative_to(tree))] = hash_file(path)
    return result


def process_rss_kib(root_pid: int) -> int | None:
    """Approximate live RSS of Maven/Java/ProB descendants; diagnostic only."""
    try:
        output = subprocess.check_output(["ps", "-axo", "pid=,ppid=,rss="],
                                         text=True, timeout=8)
        children: dict[int, list[int]] = {}
        usage: dict[int, int] = {}
        for line in output.splitlines():
            pieces = line.split()
            if len(pieces) != 3:
                continue
            pid, ppid, rss = map(int, pieces)
            children.setdefault(ppid, []).append(pid)
            usage[pid] = rss
        if root_pid not in usage:
            return None
        active = [root_pid]
        visited: set[int] = set()
        while active:
            pid = active.pop()
            if pid in visited:
                continue
            visited.add(pid)
            active.extend(children.get(pid, ()))
        return sum(usage.get(pid, 0) for pid in visited)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def run_with_log(cmd: list[str], log: Path, timeout_seconds: int) -> tuple[int, int | None]:
    peak_rss = [None]
    stopped = threading.Event()
    with log.open("w", encoding="utf-8") as sink:
        process = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True,
                                   bufsize=1, start_new_session=True)

        def print_output() -> None:
            assert process.stdout is not None
            for line in process.stdout:
                print(line, end="", flush=True)
                sink.write(line)
                sink.flush()

        def sample_memory() -> None:
            while not stopped.wait(1.0):
                current = process_rss_kib(process.pid)
                if current is not None:
                    peak_rss[0] = max(peak_rss[0] or 0, current)

        reader = threading.Thread(target=print_output, daemon=True)
        monitor = threading.Thread(target=sample_memory, daemon=True)
        reader.start()
        monitor.start()
        timed_out = False
        try:
            code = process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            timed_out = True
            code = 124
            print(f"TIMEOUT: exceeded {timeout_seconds} s; terminating process group")
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait()
        except KeyboardInterrupt:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            process.wait(timeout=10)
            raise
        finally:
            stopped.set()
            reader.join(timeout=10)
            monitor.join(timeout=3)
        if timed_out:
            sink.write("TIMEOUT: run invalid; no completion marker accepted\n")
        return code, peak_rss[0]


def summarize(directory: Path, expected_runs: int, models: list[str], workers: list[int]) -> Path:
    import statistics

    rows = []
    for model in models:
        source = directory / f"{model}.csv"
        if not Path(str(source) + ".complete").is_file():
            raise RuntimeError(f"No .complete marker: {source}")
        with source.open(newline="", encoding="utf-8") as file:
            data = list(csv.DictReader(file))
        configs = [("PREPROCESS", 1), ("RECURSIVE", 1)] + [
            ("CONCURRENT", n) for n in workers
        ]
        if len(data) != expected_runs * len(configs):
            raise RuntimeError(f"Bad row count {model}: {len(data)} vs {expected_runs * len(configs)}")
        for strategy, p in configs:
            group = [r for r in data if r["strategy"] == strategy and int(r["workers"]) == p]
            if len(group) != expected_runs or any(
                r["status"] != "PASS" or r["graphMatchesReference"] != "true" for r in group
            ):
                raise RuntimeError(f"Incomplete/invalid group: {model} {strategy} {p}")
            metric = lambda col: [float(r[col]) for r in group if r[col]]
            graph = metric("graphReadyMs")
            rows.append({
                "benchmark": model, "strategy": strategy, "workers": p,
                "runs": len(graph),
                "meanGraphReadyMs": round(statistics.mean(graph), 3),
                "medianGraphReadyMs": round(statistics.median(graph), 3),
                "sdGraphReadyMs": round(statistics.stdev(graph), 3) if len(graph) > 1 else "",
                "meanLoadWallMs": round(statistics.mean(metric("loadWallMs")), 3),
                "meanNativeMs": round(statistics.mean(metric("nativeExplorationMs")), 3)
                    if strategy != "CONCURRENT" else "",
                "meanExtractionMs": round(statistics.mean(metric("graphExtractionMs")), 3)
                    if strategy != "CONCURRENT" else "",
                "meanPrefixMs": round(statistics.mean(metric("prefixMs")), 3)
                    if strategy == "CONCURRENT" else "",
                "meanParallelExplorationMs": round(statistics.mean(metric("parallelExplorationMs")), 3)
                    if strategy == "CONCURRENT" else "",
            })
    output = directory / "summary_by_configuration.csv"
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("pilot", "full"), default="pilot")
    parser.add_argument("--models", default="tictactoe",
                        help="comma-separated model IDs, or 'all'")
    parser.add_argument("--workers", default="1,2,4,8")
    parser.add_argument("--prefix-depth", type=int, default=1)
    parser.add_argument("--reassignment", action="store_true", default=False)
    parser.add_argument("--java-heap", default="4g", help="e.g. 4g, 8g; JVM heap only")
    parser.add_argument("--timeout-minutes", type=int, default=120,
                        help="per model JVM (including reference and all variants)")
    parser.add_argument("--output-dir", default=None)
    args = parser.parse_args()
    models = list(MODELS) if args.models.lower() == "all" else [
        v.strip().lower() for v in args.models.split(",")
    ]
    workers = [int(x) for x in args.workers.split(",")]
    if not models or len(set(models)) != len(models) or any(m not in MODELS for m in models):
        parser.error(f"Unknown/duplicate model in {models}; valid: {', '.join(MODELS)}")
    if not workers or len(set(workers)) != len(workers) or any(p < 1 or p > 64 for p in workers):
        parser.error("Workers must be distinct positive numbers <=64")
    if args.prefix_depth < 1 or args.timeout_minutes < 1 or not re.fullmatch(r"[1-9][0-9]*[mMgG]", args.java_heap):
        parser.error("Invalid prefix, timeout or Java heap; example --java-heap 4g")
    repetitions = 1 if args.mode == "pilot" else 10
    warmups = 0 if args.mode == "pilot" else 1
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    result = Path(args.output_dir) if args.output_dir else \
        ROOT / "results/concurrent" / f"graph-{args.mode}-{stamp}-{os.getpid()}"
    result = result if result.is_absolute() else ROOT / result
    if result.exists():
        parser.error(f"Refusing to reuse existing output directory: {result}")
    result.mkdir(parents=True)
    git = git_info()
    manifest = {
        "created_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "mode": args.mode, "models": models, "workers": workers,
        "runs_per_configuration": repetitions, "warmups_per_configuration": warmups,
        "prefix_depth": args.prefix_depth, "reassignment": args.reassignment,
        "java_heap": args.java_heap, "timeout_minutes_per_model": args.timeout_minutes,
        "git": git, "java_version": version_of("java"), "maven_version": version_of("maven"),
        "os": platform.platform(), "cpu_count": os.cpu_count(),
        "mac_model": invoke_short(["sysctl", "-n", "hw.model"])
            if sys.platform == "darwin" else "not macOS",
        "mac_cpu": invoke_short(["sysctl", "-n", "machdep.cpu.brand_string"])
            if sys.platform == "darwin" else "not macOS",
        "mac_physical_memory_bytes": invoke_short(["sysctl", "-n", "hw.memsize"])
            if sys.platform == "darwin" else "not macOS",
        "proB_java_api_dependency": "de.hhu.stups:de.prob2.kernel:4.16.1 (pom.xml)",
        "machine_sha256": {m: hash_file(ROOT / "src/main/resources" / MACHINES[m]) for m in models},
        "resource_tree_sha256": machine_manifest(),
        "status": "INCOMPLETE", "results": {},
        "note": "graphReadyMs is wall-clock to complete detached Java graph, NOT sum of worker times. "
                "RSS is diagnostic process-tree sum, not exact unique physical RAM.",
    }

    def save_manifest() -> None:
        (result / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    save_manifest()
    print(f"OUTPUT DIRECTORY: {result}", flush=True)
    print(f"MODE: {args.mode}, {repetitions} measured runs/config, {warmups} warmups/config", flush=True)
    print("Compiling existing project and new benchmark sources once...", flush=True)
    compile_run = subprocess.run(["mvn", "test-compile"], cwd=ROOT, check=False)
    if compile_run.returncode != 0:
        manifest["status"] = "FAILED_COMPILATION"
        save_manifest()
        return compile_run.returncode

    for model in models:
        print(f"\n===== MODEL: {model} =====", flush=True)
        csv_path = result / f"{model}.csv"
        log_path = result / f"{model}.log"
        # For the existing exec plugin, %-classpath is expanded by Maven.
        # This launches a SEPARATE Java process for each model, excluding Maven startup
        # from the application-level timestamps.
        java_args = " ".join([
            "-Xmx" + args.java_heap, "-cp", "%classpath",
            "fr.polytech.mnia.exploration.GraphConstructionCampaign",
            model, str(repetitions), ",".join(str(p) for p in workers),
            str(args.prefix_depth), str(args.reassignment).lower(),
            str(csv_path), str(warmups),
        ])
        cmd = ["mvn", "exec:exec", "-Dexec.executable=java",
               "-Dexec.args=" + java_args, "-Dexec.classpathScope=test"]
        try:
            code, peak_rss = run_with_log(cmd, log_path, args.timeout_minutes * 60)
        except KeyboardInterrupt:
            manifest["status"] = "INTERRUPTED"
            save_manifest()
            return 130
        model_success = code == 0 and Path(str(csv_path) + ".complete").exists()
        manifest["results"][model] = {
            "exit_code": code, "completed": model_success,
            "csv": str(csv_path), "log": str(log_path),
            "peak_process_tree_rss_kib_diagnostic": peak_rss,
        }
        save_manifest()
        if not model_success:
            manifest["status"] = "FAILED_OR_INCOMPLETE"
            save_manifest()
            print(f"STOP: {model} failed or timed out. See {log_path} and manifest.json", flush=True)
            print("No other models were launched after the failure.", flush=True)
            return 1

    summary = summarize(result, repetitions, models, workers)
    manifest["status"] = "COMPLETE"
    manifest["summary"] = str(summary)
    save_manifest()
    (result / ".complete").write_text("PASS\n", encoding="utf-8")
    print(f"\nCAMPAIGN COMPLETE: {summary}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
