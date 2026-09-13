#!/usr/bin/env python3
"""Run exactly one preregistered replica, serially and without automatic retries."""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import time
from pathlib import Path

from jmh_precision_study_process_v1 import (execute, jar_identity, runner_identity, shared_launch_identity,
                                           source_identity, utc_now)
from jmh_precision_study_v1 import (FROZEN_INPUTS, POLICY_PATH, canonical, cells, evaluate_result,
                                    finite, jmh_command, load_json, load_policy, require, sha256,
                                    summarize, worst_status)


def run_study(root, replicate, output, java=None, prebuilt_jar=None, job_start_epoch=None):
    root, output = Path(root).resolve(), Path(output).resolve()
    policy, inventory = load_policy(root)
    planned = cells(policy, inventory, replicate)
    identity = source_identity(root)
    runner = runner_identity()
    require(sys.platform == "linux", "the preregistered study requires a Linux runner")
    require(not any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")),
            "inherited JVM option environment must be empty")
    require(not any(character.isspace() for character in str(root)),
            "the preregistered CI checkout path must not contain whitespace")
    launch = None
    if runner["evidenceKind"] == "GITHUB_SHARED_RUNNER":
        require(prebuilt_jar is None, "shared-runner study must build its own jar")
        require(job_start_epoch is not None, "shared-runner study requires the first-step job start epoch")
        require(runner["GITHUB_SHA"] == identity["sourceRevision"], "GitHub SHA differs from executed checkout")
        launch = shared_launch_identity(runner, policy)
    setup_seconds = 0.0 if job_start_epoch is None else time.time() - finite(job_start_epoch, "job start epoch")
    require(0 <= setup_seconds < policy["budgets"]["replicateTimeoutSeconds"], "invalid or exhausted job start")
    output.mkdir(parents=True, exist_ok=False)
    started = time.monotonic()
    deadline = started + policy["budgets"]["replicateTimeoutSeconds"] - setup_seconds
    java = str(Path(java).resolve()) if java else str(Path(os.environ["JAVA_HOME"]) / "bin/java") \
        if os.environ.get("JAVA_HOME") else shutil.which("java")
    require(java is not None, "Java executable missing")
    manifest = dict(schema="regelsuche.quality.jmh-precision-study-replicate/v1",
                    studyId=policy["studyId"], replicate=replicate, **identity, runner=runner, launch=launch,
                    inputs={}, repositoryRoot=str(root), outputDirectory=str(output),
                    startedAt=utc_now(), setupSeconds=setup_seconds, java=java,
                    cells=[], collectionStatus="ERROR", ratchetStatus="ERROR")
    for path in (POLICY_PATH, *FROZEN_INPUTS):
        destination = output / "inputs" / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes((root / path).read_bytes())
        manifest["inputs"][path] = sha256(destination)
    (output / "manifest.json").write_text(canonical(manifest), encoding="utf-8")
    temporary = root / policy["jvm"]["temporaryDirectoryPath"]
    temporary.mkdir(parents=True, exist_ok=True)
    manifest["jvmArgs"] = [policy["jvm"]["systemProperties"][0], f"-Djava.io.tmpdir={temporary}",
                           *policy["jvm"]["systemProperties"][1:]]
    manifest["javaVersion"] = execute([java, "--version"], root, output / "java-version.log",
                                       min(15, max(.001, deadline - time.monotonic())))
    if prebuilt_jar is None and (deadline <= time.monotonic() or manifest["javaVersion"]["status"] != "COMPLETED"):
        manifest["build"] = dict(status="NOT_RUN", elapsedSeconds=0.0)
        jar = None
    elif prebuilt_jar is None:
        command = [str(root / "gradlew"), "--no-daemon", "--no-configuration-cache",
                   "--max-workers=2", ":app:jmhJar", "--console=plain"]
        manifest["build"] = execute(command, root, output / "build.log",
                                    min(policy["budgets"]["buildTimeoutSeconds"], deadline - time.monotonic()))
        jars = list((root / "app/build/libs").glob("*-jmh.jar"))
        jar = jars[0] if len(jars) == 1 and manifest["build"]["status"] == "COMPLETED" else None
    else:
        manifest["build"] = dict(status="PREBUILT_LOCAL_CONTROL", elapsedSeconds=0.0)
        jar = Path(prebuilt_jar).resolve()
    try:
        manifest["jar"] = jar_identity(jar) if jar is not None else None
    except (ValueError, OSError) as error:
        manifest["jarError"] = str(error)
        manifest["jar"], jar = None, None
    supervisor_failed = False
    for cell in planned:
        retained = dict(cellId=cell["id"], protocol=cell["protocol"], status="ERROR")
        raw_path = f"raw/{cell['id']}.json"
        log_path = f"logs/{cell['id']}.log"
        retained.update(rawPath=raw_path, rawSha256=None, logPath=log_path)
        remaining = deadline - time.monotonic()
        if jar is None or remaining <= 0 or supervisor_failed or manifest["javaVersion"]["status"] != "COMPLETED":
            retained["error"] = "NOT_RUN: failed build/JVM probe/supervision or exhausted preregistered budget"
        else:
            (output / raw_path).parent.mkdir(parents=True, exist_ok=True)
            timeout = min(remaining, policy["budgets"]["maximumCellTimeoutSeconds"],
                          cell["nominalSeconds"] * policy["budgets"]["cellTimeoutNominalMultiplier"] +
                          policy["budgets"]["cellTimeoutOverheadSeconds"])
            command = jmh_command(java, jar, cell, output / raw_path, manifest["jvmArgs"])
            retained["process"] = execute(command, root, output / log_path, timeout)
            supervisor_failed = retained["process"]["status"] in ("SUPERVISOR_ERROR", "CLEANUP_ERROR")
            if (output / raw_path).is_file():
                retained["rawSha256"] = sha256(output / raw_path)
            try:
                require(retained["process"]["status"] == "COMPLETED", "JMH process did not complete")
                require(retained["rawSha256"] is not None, "raw JMH result is missing")
                rows = evaluate_result(load_json(output / raw_path), cell, inventory)
                require(all(row["jvmArgs"] == manifest["jvmArgs"] for row in rows), "JVM arguments drift")
                retained.update(status="MEASURED", summary=summarize(rows))
            except (ValueError, OSError, KeyError, TypeError) as error:
                retained["error"] = str(error)
        manifest["cells"].append(retained)
        (output / "manifest.json").write_text(canonical(manifest), encoding="utf-8")
    try:
        require(source_identity(root) == identity, "checkout changed during measurement")
        require(jar is not None and jar_identity(jar) == manifest["jar"], "jar changed or unavailable")
        load_policy(root)
    except (ValueError, OSError) as error:
        manifest["integrityError"] = str(error)
    manifest.update(finishedAt=utc_now(), elapsedSeconds=setup_seconds + time.monotonic() - started)
    complete = all(row["status"] == "MEASURED" for row in manifest["cells"]) and "integrityError" not in manifest
    manifest["collectionStatus"] = "COMPLETE" if complete else "ERROR"
    manifest["executionErrorCount"] = sum(row["status"] != "MEASURED" for row in manifest["cells"])
    manifest["ratchetStatus"] = worst_status(row.get("summary", {}).get("status", "ERROR")
                                             for row in manifest["cells"])
    (output / "manifest.json").write_text(canonical(manifest), encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--replicate", type=int, choices=(1, 2, 3), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--job-start-epoch", type=float)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--prebuilt-jar", type=Path, help="local process controls only; forbidden on shared runners")
    args = parser.parse_args()
    try:
        manifest = run_study(args.repository_root, args.replicate, args.output, args.java,
                             args.prebuilt_jar, args.job_start_epoch)
    except (ValueError, OSError) as error:
        print(f"JMH study ERROR: {error}", file=sys.stderr)
        return 2
    print(f"collectionStatus={manifest['collectionStatus']} ratchetStatus={manifest['ratchetStatus']}")
    print("selectedProtocol=none; study observations do not replace the production regression authority")
    return 2 if manifest["collectionStatus"] != "COMPLETE" else 0 if manifest["ratchetStatus"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
