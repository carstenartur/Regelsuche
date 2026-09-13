#!/usr/bin/env python3
"""Replay the exact finite JMH study corpus; never infer a pooled confidence interval."""

from __future__ import annotations

import argparse
import math
import re
import statistics
import sys
from pathlib import Path

from jmh_precision_study_process_v1 import git
from jmh_precision_study_supervisor_v1 import SUPERVISION
from jmh_precision_study_v1 import (FAMILIES, FROZEN_INPUTS, POLICY_PATH, canonical, cells,
                                    evaluate_result, finite, jmh_command, load_json, load_policy,
                                    require, sha256, summarize, verify_launch, worst_status)


def bound_file(directory, relative, digest):
    require(isinstance(relative, str) and not Path(relative).is_absolute() and
            ".." not in Path(relative).parts, "unsafe evidence path")
    path = directory
    for part in Path(relative).parts:
        path = path / part
        require(not path.is_symlink(), f"symbolic evidence path: {relative}")
    require(path.is_file() and re.fullmatch(r"[0-9a-f]{64}", str(digest)) and sha256(path) == digest,
            f"retained evidence digest differs or file missing: {relative}")
    return path


def verify_replica(root, directory, policy, inventory):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), "replicate directory missing or symbolic")
    require(not (directory / "manifest.json").is_symlink(), "symbolic manifest")
    manifest = load_json(directory / "manifest.json")
    require(isinstance(manifest, dict), "replicate manifest must be an object")
    require(manifest.get("schema") == "regelsuche.quality.jmh-precision-study-replicate/v1" and
            manifest.get("studyId") == policy["studyId"], "unsupported replicate manifest")
    require(manifest.get("collectionStatus") == "COMPLETE" and not manifest.get("integrityError"),
            "incomplete replicate; no partial-corpus qualification")
    expected_inputs = {path: sha256(root / path) for path in (POLICY_PATH, *FROZEN_INPUTS)}
    require(manifest.get("inputs") == expected_inputs, "replicate policy identities differ")
    for path, digest in expected_inputs.items():
        bound_file(directory, "inputs/" + path, digest)
    require(manifest.get("sourceRevision") == git(root, "rev-parse", "HEAD") and
            manifest.get("sourceTree") == git(root, "rev-parse", "HEAD^{tree}"),
            "source identity differs; replay from the measured checkout")
    replica = manifest.get("replicate")
    planned = cells(policy, inventory, replica)
    retained_cells = manifest.get("cells")
    require(isinstance(retained_cells, list) and [row.get("cellId") for row in retained_cells] ==
            [row["id"] for row in planned], "incomplete or reordered cell inventory")
    temporary = Path(manifest["repositoryRoot"]) / policy["jvm"]["temporaryDirectoryPath"]
    expected_args = [policy["jvm"]["systemProperties"][0], f"-Djava.io.tmpdir={temporary}",
                     *policy["jvm"]["systemProperties"][1:]]
    require(manifest.get("jvmArgs") == expected_args, "preregistered JVM arguments differ")
    jar = manifest.get("jar")
    require(isinstance(jar, dict) and all(re.fullmatch(r"[0-9a-f]{64}", str(jar.get(key)))
                                         for key in ("sha256", "contentSha256")), "jar identity missing")
    java = manifest["javaVersion"]
    def supervised(receipt):
        require(receipt.get("supervision") == SUPERVISION and receipt.get("remainingDescendants") == 0,
                "process supervision missing or descendants remain")
        require(type(receipt.get("adoptedChildExitCount")) is int and receipt["adoptedChildExitCount"] >= 0 and
                type(receipt.get("adoptedChildFailureCount")) is int and receipt["adoptedChildFailureCount"] == 0 and
                receipt.get("adoptedChildFailureExitCodes") == [],
                "observed adopted child failed or exit evidence is missing")
    supervised(java)
    require(java.get("command") == [manifest["java"], "--version"] and
            java.get("status") == "COMPLETED" and java.get("exitCode") == 0,
            "JVM probe failed or changed")
    bound_file(directory, "java-version.log", java["logSha256"])
    measured_seconds = finite(java["elapsedSeconds"], "JVM probe wallclock")
    build = manifest["build"]
    shared = manifest["runner"]["evidenceKind"] == "GITHUB_SHARED_RUNNER"
    if build["status"] == "COMPLETED":
        supervised(build)
        require(build.get("exitCode") == 0 and build.get("command") ==
                [str(Path(manifest["repositoryRoot"]) / "gradlew"), "--no-daemon",
                 "--no-configuration-cache", "--max-workers=2", ":app:jmhJar", "--console=plain"],
                "build command or status differs")
        bound_file(directory, "build.log", build["logSha256"])
        require(finite(build["elapsedSeconds"], "build wallclock") <=
                policy["budgets"]["buildTimeoutSeconds"] + 2, "build budget exceeded")
    else:
        require(not shared and build["status"] == "PREBUILT_LOCAL_CONTROL", "unqualified shared-runner build")
    measured_seconds += finite(build["elapsedSeconds"], "build wallclock")
    protocols = {}
    for cell, retained in zip(planned, retained_cells):
        require(retained.get("protocol") == cell["protocol"] and retained.get("status") == "MEASURED",
                "unmeasured or relabeled cell")
        require(retained.get("rawPath") == f"raw/{cell['id']}.json" and
                retained.get("logPath") == f"logs/{cell['id']}.log", "cell evidence path differs")
        process = retained["process"]
        supervised(process)
        require(process.get("status") == "COMPLETED" and process.get("exitCode") == 0,
                "failed cell process")
        expected_command = jmh_command(manifest["java"], jar["path"], cell,
                                       Path(manifest["outputDirectory"]) / retained["rawPath"], expected_args)
        require(process.get("command") == expected_command, "cell command differs from preregistration")
        timeout = finite(process["timeoutSeconds"], "cell timeout", positive=True)
        maximum_timeout = min(policy["budgets"]["maximumCellTimeoutSeconds"],
                              cell["nominalSeconds"] * policy["budgets"]["cellTimeoutNominalMultiplier"] +
                              policy["budgets"]["cellTimeoutOverheadSeconds"])
        duration = finite(process["elapsedSeconds"], "cell wallclock", positive=True)
        require(timeout <= maximum_timeout and duration <= timeout + 2, "cell budget exceeded")
        require(not shared or duration + 1 >= cell["nominalSeconds"], "implausibly short shared-runner cell")
        measured_seconds += duration
        raw = bound_file(directory, retained["rawPath"], retained["rawSha256"])
        bound_file(directory, retained["logPath"], process["logSha256"])
        rows = evaluate_result(load_json(raw), cell, inventory)
        require(all(row["jvmArgs"] == expected_args for row in rows), "observed JVM argument drift")
        require(summarize(rows) == retained.get("summary"), "retained summary differs from raw measurements")
        protocol = protocols.setdefault(cell["protocol"], dict(benchmarks=[], wallclockSeconds=0.0))
        protocol["benchmarks"].extend(rows)
        protocol["wallclockSeconds"] += duration
    for protocol in protocols.values():
        protocol["benchmarks"].sort(key=lambda row: row["benchmark"])
        require([row["benchmark"] for row in protocol["benchmarks"]] == sorted(inventory),
                "protocol does not contain exactly the complete 29-row inventory")
        protocol["summary"] = summarize(protocol["benchmarks"])
    elapsed = finite(manifest["elapsedSeconds"], "replicate wallclock", positive=True)
    setup = finite(manifest["setupSeconds"], "CI setup wallclock")
    require(measured_seconds + setup <= elapsed + .01 and
            elapsed <= policy["budgets"]["replicateTimeoutSeconds"] + 2, "replicate wallclock budget differs")
    require(manifest.get("ratchetStatus") == worst_status(row["summary"]["status"]
                                                         for row in protocols.values()), "ratchet summary drift")
    if shared:
        runner = manifest["runner"]
        verify_launch(manifest.get("launch"), runner, policy)
        require(runner["GITHUB_SHA"] == manifest["sourceRevision"] and
                runner["GITHUB_ACTIONS"] == "true" and runner["RUNNER_ENVIRONMENT"] == "github-hosted" and
                runner["RUNNER_OS"] == "Linux" and runner["ImageOS"] == "ubuntu22",
                "shared-runner provenance differs from declared environment")
        require(all(isinstance(runner.get(key), str) and runner[key] for key in
                    ("GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "RUNNER_NAME", "ImageVersion")),
                "shared-runner identity incomplete")
    else:
        require(manifest["runner"]["evidenceKind"] == "LOCAL_CONTROL", "unsupported evidence kind")
        require(manifest.get("launch") is None, "local controls cannot claim a shared launch")
    return manifest, protocols


def inversions(first, second):
    names = sorted(first)
    return sum((first[left] - first[right]) * (second[left] - second[right]) < 0
               for index, left in enumerate(names) for right in names[index + 1:])


def verify_independent_runners(manifests):
    boot_ids = [manifest["runner"].get("bootId") for manifest in manifests]
    require(all(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", str(value))
                for value in boot_ids), "hosted Linux boot identity missing")
    require(len(set(boot_ids)) == 3, "replicates require three independent shared runners")


def adoption_checks(row, baseline, kind, rules):
    return dict(sharedRunnerEvidence=kind == "GITHUB_SHARED_RUNNER", candidate=row["protocol"] != "baseline",
        positiveControlLowPrecisionCount=baseline["summary"]["lowPrecisionCount"] > 0,
        materialLowPrecisionReduction=row["summary"]["lowPrecisionCount"] <=
            baseline["summary"]["lowPrecisionCount"] * rules["maximumLowPrecisionRatioToControl"],
        noFamilyLowPrecisionIncrease=all(row["families"][family]["lowPrecisionCount"] <=
            baseline["families"][family]["lowPrecisionCount"] for family in FAMILIES),
        lowerMedianRelativeError=row["summary"]["medianRelativeError"] < baseline["summary"]["medianRelativeError"],
        lowerP90RelativeError=row["summary"]["p90RelativeError"] < baseline["summary"]["p90RelativeError"],
        allCandidateRatchetsPassed=row["summary"]["status"] == "PASSED",
        boundedMedianCost=row["medianWallclockSeconds"] / baseline["medianWallclockSeconds"] <= rules["maximumMedianWallclockRatioToControl"],
        semanticWorkParity=row["semanticWorkParity"])


def analyze(root, directories):
    policy, inventory = load_policy(root)
    require(len(directories) == 3, "exactly three replicate directories required")
    verified = [verify_replica(root, directory, policy, inventory) for directory in directories]
    verified.sort(key=lambda result: result[0]["replicate"])
    require([manifest["replicate"] for manifest, _ in verified] == [1, 2, 3], "duplicate or missing replicate")
    manifests = [manifest for manifest, _ in verified]
    directories_by_replicate = {load_json(Path(directory) / "manifest.json")["replicate"]: Path(directory)
                               for directory in directories}
    require(len({manifest["jar"]["contentSha256"] for manifest in manifests}) == 1,
            "benchmark jar contents differ between replicates")
    environment_keys = ("evidenceKind", "GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
                        "RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")
    require(len({tuple(manifest["runner"].get(key) for key in environment_keys) for manifest in manifests}) == 1,
            "runner environment or workflow run/attempt differs")
    kind = manifests[0]["runner"]["evidenceKind"]
    if kind == "GITHUB_SHARED_RUNNER":
        verify_independent_runners(manifests)
        require(len({canonical(manifest["launch"]) for manifest in manifests}) == 1,
                "replicates refer to different pull-request launches")
    vm_identities = {(row["jdkVersion"], row["vmName"], row["vmVersion"])
                     for _, protocols in verified for protocol in protocols.values()
                     for row in protocol["benchmarks"]}
    require(len(vm_identities) == 1, "JDK patch or VM differs within the study")
    results = []
    for protocol in policy["protocols"]:
        protocol_id = protocol["id"]
        replicas = [data[protocol_id] for _, data in verified]
        rows = [row for replica in replicas for row in replica["benchmarks"]]
        costs = [replica["wallclockSeconds"] for replica in replicas]
        row = dict(protocol=protocol_id, summary=summarize(rows),
                   families={family: summarize([item for item in rows if item["family"] == family])
                             for family in FAMILIES}, medianWallclockSeconds=statistics.median(costs),
                   wallclockSecondsByReplicate=costs, replicas=[], benchmarks=[],
                   semanticWorkParity=True, rankingComparisons=[])
        row["executionErrorCount"] = 0  # Nonzero execution errors reject the complete-corpus prerequisite.
        for (manifest, data), replica in zip(verified, replicas):
            baseline = {item["benchmark"]: item for item in data["baseline"]["benchmarks"]}
            enriched = []
            for measured in replica["benchmarks"]:
                control = baseline[measured["benchmark"]]
                work = measured["workPerSearch"]
                parity = work.keys() == control["workPerSearch"].keys() and all(
                    math.isclose(value, control["workPerSearch"][key], rel_tol=1e-9, abs_tol=1e-9)
                    for key, value in work.items())
                row["semanticWorkParity"] &= parity
                enriched.append(measured | dict(scoreRatioToControl=measured["score"] / control["score"],
                                                 scoreRatioToFrozenBaseline=measured["score"] /
                                                 measured["frozenBaselineScore"], semanticWorkParity=parity))
            row["replicas"].append(dict(replicate=manifest["replicate"], summary=replica["summary"],
                                        benchmarks=enriched))
            for family in FAMILIES:
                scores = {item["benchmark"]: item["score"] for item in enriched if item["family"] == family}
                row["rankingComparisons"].append(dict(replicate=manifest["replicate"], family=family,
                    orderInversionsToControl=inversions(scores, {name: baseline[name]["score"] for name in scores}),
                    orderInversionsToFrozenBaseline=inversions(scores, {name: inventory[name]["baselineScore"]
                                                                        for name in scores})))
        for name in sorted(inventory):
            measurements = [item for item in rows if item["benchmark"] == name]
            row["benchmarks"].append(dict(benchmark=name, family=inventory[name]["family"],
                unit=inventory[name]["unit"], medianScore=statistics.median(item["score"] for item in measurements),
                medianReportedScoreError=statistics.median(item["scoreError"] for item in measurements),
                worstStatus=worst_status(item["status"] for item in measurements),
                lowPrecisionReplicateCount=sum(item["precisionStatus"] == "LOW_PRECISION" for item in measurements)))
        results.append(row)
    baseline = next(row for row in results if row["protocol"] == "baseline")
    rules = policy["analysis"]
    for row in results:
        row["medianWallclockRatioToControl"] = row["medianWallclockSeconds"] / baseline["medianWallclockSeconds"]
        checks = adoption_checks(row, baseline, kind, rules)
        row.update(adoptionChecks=checks, eligibleForReview=all(checks.values()))
    outcome = ("NO_LOW_PRECISION_IMPROVEMENT_ESTABLISHED" if baseline["summary"]["lowPrecisionCount"] == 0 else
               "CANDIDATES_REQUIRE_MANUAL_REVIEW" if any(row["eligibleForReview"] for row in results) else
               "NO_PROTOCOL_QUALIFIES")
    return dict(schema="regelsuche.quality.jmh-precision-study-report/v1", studyId=policy["studyId"],
                collectionStatus="COMPLETE", ratchetStatus=worst_status(row["summary"]["status"] for row in results),
                evidenceKind=kind, sourceRevision=manifests[0]["sourceRevision"],
                inputs=manifests[0]["inputs"], measurementRowCount=609,
                claimBoundary=policy["claimBoundary"], aggregation=rules["aggregation"],
                adoptionOutcome=outcome, selectedProtocol=None,
                frozenSemanticComparability="NOT_ESTABLISHED: historical work counters are not bound by the frozen policy",
                protocols=results, replicates=[dict(replicate=manifest["replicate"],
                    elapsedSeconds=manifest["elapsedSeconds"], setupSeconds=manifest["setupSeconds"],
                    build=manifest["build"], runner=manifest["runner"], jar=manifest["jar"],
                    manifestSha256=sha256(directories_by_replicate[manifest["replicate"]] / "manifest.json"))
                    for manifest in manifests])


def markdown(report):
    lines = ["# Preregistered JMH precision study v1", "", report.get("claimBoundary", ""), "",
             f"Collection: **{report['collectionStatus']}**. Ratchet: **{report['ratchetStatus']}**.",
             f"Adoption: **{report['adoptionOutcome']}**. Selected protocol: **none**.", "",
             "Medians across replicas are descriptive; reported JMH errors are never pooled into a new confidence interval.", "",
             "| Protocol | Rows | LOW_PRECISION | INCONCLUSIVE | FAILED | Median error/score | p90 error/score | Median seconds | Control cost ratio | Review eligible |",
             "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
    for row in report.get("protocols", []):
        summary = row["summary"]
        lines.append(f"| {row['protocol']} | {summary['benchmarkCount']} | {summary['lowPrecisionCount']} | "
                     f"{summary['inconclusiveCount']} | {summary['failedCount']} | {summary['medianRelativeError']:.6g} | "
                     f"{summary['p90RelativeError']:.6g} | {row['medianWallclockSeconds']:.3f} | "
                     f"{row['medianWallclockRatioToControl']:.3f} | {row['eligibleForReview']} |")
    lines.extend(["", "Canonical JSON retains every raw score, scoreError, unit, precision/ratchet outcome, family, work comparison and per-replica cost.",
                  "Manual ranking/semantic review and a new production execution authority/baseline are still required before adoption."])
    if report.get("errors"):
        lines.extend(["", *[f"- {error}" for error in report["errors"]]])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--replicate", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    try:
        report = analyze(args.repository_root, args.replicate)
    except (ValueError, OSError, KeyError, TypeError, AttributeError) as error:
        execution_errors = []
        for directory in args.replicate:
            try:
                manifest = load_json(directory / "manifest.json")
                for cell in manifest.get("cells", []):
                    if cell.get("status") != "MEASURED":
                        execution_errors.append(dict(replicate=manifest.get("replicate"), cellId=cell.get("cellId"),
                            processStatus=cell.get("process", {}).get("status", "NOT_RUN"), error=cell.get("error")))
            except (ValueError, OSError, KeyError, TypeError, AttributeError):
                pass  # The validation failure remains authoritative; malformed diagnostics cannot create a pass.
        report = dict(schema="regelsuche.quality.jmh-precision-study-report/v1", collectionStatus="ERROR",
                      ratchetStatus="ERROR", adoptionOutcome="INCOMPLETE_EVIDENCE_NO_SELECTION",
                      selectedProtocol=None, errors=[str(error)], protocols=[],
                      retainedExecutionErrors=execution_errors, executionErrorCount=len(execution_errors))
    (args.output / "report.json").write_text(canonical(report), encoding="utf-8")
    (args.output / "report.md").write_text(markdown(report), encoding="utf-8")
    print(f"collectionStatus={report['collectionStatus']} ratchetStatus={report['ratchetStatus']}")
    return 2 if report["collectionStatus"] != "COMPLETE" else 0 if report["ratchetStatus"] == "PASSED" else 1


if __name__ == "__main__":
    sys.exit(main())
