#!/usr/bin/env python3
"""Snapshot a built runtime and retain every paid modular execution diagnostic.

Run the targeted Gradle tests/classes first. This invokes only the new experiment;
it does not open or change any frozen benchmark assets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=2)
    parser.add_argument("--gradle-cache", type=Path, default=Path.home() / ".gradle/caches/modules-2/files-2.1")
    args = parser.parse_args()
    if args.runs < 1:
        parser.error("--runs must be positive")
    repository = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists():
        parser.error("output must be new so no previous result is overwritten")
    runtime = output / "runtime"
    runtime.mkdir(parents=True)
    classpath: list[str] = []
    modules = ("core", "egraph", "search", "validation", "math-algorithms", "experiments")
    for module in modules:
        source = repository / f"regelsuche-{module}" / "build/classes/java/main"
        if not source.is_dir():
            raise SystemExit(f"Compile module first: {source}")
        destination = runtime / module / "classes"
        shutil.copytree(source, destination)
        classpath.append(str(destination))
    versions = {"jackson-core": "2.22.2", "jackson-databind": "2.22.2", "jackson-annotations": "2.22"}
    for artifact, version in versions.items():
        matches = list((args.gradle_cache / "com.fasterxml.jackson.core" / artifact / version).glob(f"*/{artifact}-{version}.jar"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one cached dependency {artifact}:{version}, got {len(matches)}")
        destination = runtime / matches[0].name
        shutil.copy2(matches[0], destination)
        classpath.append(str(destination))
    inventory = []
    for path in sorted(runtime.rglob("*")):
        if path.is_file():
            inventory.append({"path": str(path.relative_to(runtime)), "bytes": path.stat().st_size,
                              "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repository, text=True).strip()
    (output / "runtime-inventory.json").write_text(json.dumps({"repositoryRevision": revision,
        "dirtyWorktree": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=repository, text=True)),
        "runtimeFiles": inventory}, indent=2) + "\n")
    for trial in range(1, args.runs + 1):
        result = output / f"run-{trial}.json"
        command = [str(args.java.resolve()), "-Xms128m", "-Xmx512m", "-cp", os.pathsep.join(classpath),
                   "de.regelsuche.example.modular.ModularJointPlanExperiment", str(result)]
        started = time.monotonic_ns()
        with (output / f"run-{trial}.log").open("x") as log:
            process = subprocess.Popen(command, cwd=repository, stdout=log, stderr=subprocess.STDOUT)
            _, status, usage = os.wait4(process.pid, 0)
            process.returncode = os.waitstatus_to_exitcode(status)
        outer = {"command": command, "exitCode": process.returncode,
                 "wallNanos": time.monotonic_ns() - started,
                 "userCpuSeconds": usage.ru_utime, "systemCpuSeconds": usage.ru_stime,
                 "maximumResidentSetKiB": usage.ru_maxrss,
                 "note": "Linux wait4 accounting includes JVM startup, all retained trials and report serialization."}
        (output / f"run-{trial}-process.json").write_text(json.dumps(outer, indent=2) + "\n")
        if process.returncode != 0:
            raise SystemExit(f"Trial {trial} failed; retained {output / f'run-{trial}.log'}")
        report = json.loads(result.read_text())
        if len(report["rows"]) != 24 or any(row["executedInputs"] != row["length"] or row["auditedInputs"] != row["length"] for row in report["rows"]):
            raise SystemExit(f"Trial {trial} has an incomplete execution/audit ledger")
        print(f"Retained {result} ({outer['wallNanos'] / 1e9:.3f}s, {usage.ru_maxrss} KiB peak RSS)", flush=True)


if __name__ == "__main__":
    main()
