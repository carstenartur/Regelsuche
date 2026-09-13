"""Checkout-owned preregistration and evidence rules for the finite JMH study."""

from __future__ import annotations

import hashlib
import json
import math
import re
import statistics
from pathlib import Path

POLICY_PATH = "config/quality/jmh-precision-study-policy-v1.json"
STUDY_POLICY_SHA256 = "236e6fc60fd7048a4f1e4683e5db0da79e4779c07469cd46f660103e11d51043"
FAMILIES = ("CORE", "REWRITE_PROGRAM", "END_TO_END_SEARCH")
FROZEN_INPUTS = {
    "config/quality/jmh-regression-policy-v2.json":
        "8c17b5f2eaed6cdbcc686ce9eaefee70f44e97ba741bb614e0a39be36ee6bd92",
    "config/quality/jmh-regression-decision-policy-v3.json":
        "e86ffe2695511342de163abab929d0f7e35d44d00e08dd715ab34d68ad1b30e9",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical(value):
    return json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"duplicate JSON key: {key}")
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError(f"nonfinite JSON constant: {value}")

    return json.loads(Path(path).read_text(encoding="utf-8"),
                      object_pairs_hook=unique, parse_constant=reject_constant)


def finite(value, label, positive=False):
    require(isinstance(value, (float, int)) and not isinstance(value, bool),
            f"{label} must be numeric")
    require(math.isfinite(value) and (value > 0 if positive else value >= 0),
            f"{label} must be finite and {'positive' if positive else 'nonnegative'}")
    return float(value)


def verify_launch(launch, runner, policy):
    expected = policy["sharedRunnerLaunch"]
    require(isinstance(launch, dict) and set(launch) ==
            set(expected) | {"headRepository", "headSha", "pullRequestNumber"},
            "shared launch identity is incomplete")
    require(all(launch.get(key) == value for key, value in expected.items()) and
            launch["headRepository"] == expected["repository"], "undeclared shared launch scope or attempt")
    require(type(launch["pullRequestNumber"]) is int and launch["pullRequestNumber"] > 0 and
            re.fullmatch(r"[0-9a-f]{40}", str(launch["headSha"])), "invalid shared launch PR identity")
    for key, environment in (("repository", "GITHUB_REPOSITORY"), ("eventName", "GITHUB_EVENT_NAME"),
                             ("headBranch", "GITHUB_HEAD_REF"), ("runAttempt", "GITHUB_RUN_ATTEMPT"),
                             ("job", "GITHUB_JOB")):
        require(launch[key] == runner.get(environment), "shared launch and runner identities differ")


def load_policy(root):
    require(sha256(root / POLICY_PATH) == STUDY_POLICY_SHA256,
            "preregistration content differs; a changed contract needs a new study revision")
    policy = load_json(root / POLICY_PATH)
    require(policy.get("schema") == "regelsuche.quality.jmh-precision-study-policy/v1",
            "unsupported study policy")
    for prefix in ("threshold", "decision"):
        path = policy.get(prefix + "PolicyPath")
        require(path in FROZEN_INPUTS, f"{prefix} policy path drift")
        expected = FROZEN_INPUTS[path]
        require(policy.get(prefix + "PolicySha256") == expected and
                sha256(root / path) == expected, f"frozen {prefix} policy content drift")
    thresholds = load_json(root / policy["thresholdPolicyPath"])
    inventory = {row["benchmark"]: row for row in thresholds["benchmarks"]}
    require(len(inventory) == len(thresholds["benchmarks"]) == policy.get("benchmarkCount") == 29,
            "study requires the complete 29-benchmark inventory")
    require(policy.get("execution") == dict(jmhVersion="1.36", jdkMajor=25,
                                            mode="avgt", threads=1), "execution drift")
    require(policy.get("selectedProtocol") is None, "study must not preselect a winner")
    protocols = policy.get("protocols", [])
    ids = [row.get("id") for row in protocols]
    require(len(ids) == len(set(ids)) == 7 and "baseline" in ids, "finite protocol inventory drift")
    require([row.get("id") for row in policy.get("replicates", [])] == [1, 2, 3],
            "exactly three preregistered replicates required")
    for row in policy["replicates"]:
        require(len(row["order"]) == 7 and set(row["order"]) == set(ids), "replicate order drift")
    budgets = policy["budgets"]
    require(budgets == dict(nominalIterationSecondsPerReplicate=1809,
                           replicateTimeoutSeconds=3600, buildTimeoutSeconds=900,
                           cellTimeoutNominalMultiplier=2, cellTimeoutOverheadSeconds=60,
                           maximumCellTimeoutSeconds=900, automaticRetries=0), "bounded budget drift")
    for replicate in (1, 2, 3):
        require(sum(row["nominalSeconds"] for row in cells(policy, inventory, replicate)) == 1809,
                "preregistered nominal budget differs")
    return policy, inventory


def cells(policy, inventory, replicate):
    require(type(replicate) is int and replicate in (1, 2, 3), "undeclared replicate")
    order = policy["replicates"][replicate - 1]["order"]
    protocols = {row["id"]: row for row in policy["protocols"]}
    result = []
    for protocol_id in order:
        protocol = protocols[protocol_id]
        require(re.fullmatch(r"[a-z][a-z-]*", protocol_id), "unsafe protocol identity")
        overrides = protocol.get("familyOverrides", {})
        require(set(overrides) <= set(FAMILIES), "undeclared family override")
        for family in FAMILIES if overrides else (None,):
            execution = protocol["execution"] | overrides.get(family, {})
            require(set(execution) == {"warmupIterations", "measurementIterations", "forks",
                                       "warmupSeconds", "measurementSeconds"}, "execution fields drift")
            require(all(type(v) is int and 1 <= v <= 8 for v in execution.values()),
                    "execution outside preregistered finite bounds")
            names = sorted(name for name, row in inventory.items()
                           if family is None or row["family"] == family)
            require(protocol["profiler"] in ("none", "gc"), "unsupported profiler")
            nominal = len(names) * execution["forks"] * (
                execution["warmupIterations"] * execution["warmupSeconds"] +
                execution["measurementIterations"] * execution["measurementSeconds"])
            result.append(dict(id=f"{len(result) + 1:02d}-{protocol_id}" +
                               (f"-{family.lower()}" if family else ""), protocol=protocol_id,
                               benchmarks=names, execution=execution,
                               profiler=protocol["profiler"], nominalSeconds=nominal))
    return result


def jmh_command(java, jar, cell, output, jvm_args=None):
    execution = cell["execution"]
    pattern = "^(?:" + "|".join(re.escape(name) for name in cell["benchmarks"]) + ")$"
    command = [str(java), "-Djmh.json.rawData=true", "-jar", str(jar), pattern,
               "-bm", "avgt", "-t", "1", "-f", str(execution["forks"]),
               "-wi", str(execution["warmupIterations"]), "-i", str(execution["measurementIterations"]),
               "-w", f"{execution['warmupSeconds']}s", "-r", f"{execution['measurementSeconds']}s",
               "-foe", "true", "-rf", "json", "-rff", str(output)]
    if cell["profiler"] == "gc":
        command.extend(["-prof", "gc"])
    if jvm_args is not None:
        command.extend(["-jvmArgs", " ".join(jvm_args)])
    return command


def evaluate_result(payload, cell, inventory):
    require(isinstance(payload, list), "JMH result must be an array")
    require(all(isinstance(row, dict) and isinstance(row.get("benchmark"), str)
                for row in payload), "malformed JMH result row")
    names = [row["benchmark"] for row in payload]
    require(len(names) == len(set(names)), "duplicate benchmark")
    require(set(names) == set(cell["benchmarks"]), "missing or extra benchmark")
    result = []
    execution = cell["execution"]
    for entry in sorted(payload, key=lambda row: row["benchmark"]):
        name = entry["benchmark"]
        expected = inventory[name]
        for key, value in dict(jmhVersion="1.36", mode="avgt", threads=1,
                               forks=execution["forks"],
                               warmupIterations=execution["warmupIterations"],
                               measurementIterations=execution["measurementIterations"],
                               warmupTime=f"{execution['warmupSeconds']} s",
                               measurementTime=f"{execution['measurementSeconds']} s",
                               warmupBatchSize=1, measurementBatchSize=1).items():
            require(type(entry.get(key)) is type(value) and entry[key] == value,
                    f"{name}: {key} differs from preregistration")
        require(re.fullmatch(r"25(?:\.[0-9]+)*(?:[+_-].*)?", str(entry.get("jdkVersion", ""))),
                f"{name}: JDK major must be 25")
        require(not entry.get("params"), f"{name}: undeclared benchmark parameters")
        require(isinstance(entry.get("jvmArgs"), list) and
                all(isinstance(arg, str) for arg in entry["jvmArgs"]), f"{name}: JVM arguments missing")
        for key in ("vmName", "vmVersion"):
            require(isinstance(entry.get(key), str) and entry[key].strip(), f"{name}: {key} missing")
        primary = entry.get("primaryMetric")
        require(isinstance(primary, dict), f"{name}: primary metric missing")
        score = finite(primary.get("score"), f"{name} score", positive=True)
        error = finite(primary.get("scoreError"), f"{name} scoreError")
        require(primary.get("scoreUnit") == expected["unit"], f"{name}: wrong unit")
        samples = primary.get("rawData")
        require(isinstance(samples, list) and len(samples) == execution["forks"],
                f"{name}: raw fork inventory missing or incomplete")
        for fork in samples:
            require(isinstance(fork, list) and len(fork) == execution["measurementIterations"],
                    f"{name}: raw measurement inventory incomplete")
            for sample in fork:
                finite(sample, f"{name} raw sample", positive=True)
        mean = statistics.mean(value for fork in samples for value in fork)
        require(math.isclose(mean, score, rel_tol=1e-9, abs_tol=1e-15),
                f"{name}: raw mean differs from score")
        secondary = entry.get("secondaryMetrics")
        require(isinstance(secondary, dict), f"{name}: secondary metrics missing")
        allocation_keys = [key for key in secondary if key.lstrip("·") == "gc.alloc.rate.norm"]
        allocation = None
        if cell["profiler"] == "gc":
            require(len(allocation_keys) == 1, f"{name}: GC allocation evidence missing or ambiguous")
            metric = secondary[allocation_keys[0]]
            require(metric.get("scoreUnit") == "B/op", f"{name}: allocation unit differs")
            allocation = finite(metric.get("score"), f"{name} allocation")
        else:
            require(not allocation_keys, f"{name}: undeclared GC profiling")
        work = {}
        if expected["family"] == "END_TO_END_SEARCH":
            for counter in ("searches", "exploredStates", "expandedStates", "generatedTransformations",
                            "enqueuedStates", "reachedTargets"):
                require(isinstance(secondary.get(counter), dict), f"{name}: {counter} missing")
                require(secondary[counter].get("scoreUnit") == "#", f"{name}: {counter} event unit differs")
                work[counter] = finite(secondary[counter].get("score"), f"{name} {counter}",
                                       positive=counter == "searches")
            work = {key: value / work["searches"] for key, value in work.items()}
        maximum = expected["maximumAllowedScore"]
        tolerance = max(1e-12, maximum * 1e-12)  # Historical v3 boundary arithmetic, unchanged.
        decision = max(0.0, score - error)
        status = ("FAILED" if decision > maximum + tolerance else
                  "INCONCLUSIVE" if score > maximum + tolerance else "PASSED")
        result.append(dict(benchmark=name, family=expected["family"], unit=expected["unit"],
                           score=score, scoreError=error, relativeError=error / score,
                           decisionScore=decision, maximumAllowedScore=maximum,
                           maximumMultiplier=expected["maximumMultiplier"],
                           frozenBaselineScore=expected["baselineScore"],
                           precisionStatus="LOW_PRECISION" if error >= score else "MEASURED",
                           status=status, rawData=samples, allocationBytesPerOperation=allocation,
                           workPerSearch=work,
                           firstToLastMeasurementRatio=statistics.median(
                               fork[0] / fork[-1] for fork in samples),
                           jdkVersion=entry["jdkVersion"], jvmArgs=entry["jvmArgs"],
                           vmName=entry.get("vmName"), vmVersion=entry.get("vmVersion")))
    return result


def worst_status(statuses):
    order = {"PASSED": 0, "INCONCLUSIVE": 1, "FAILED": 2, "ERROR": 3}
    values = list(statuses)
    return max(values, key=order.__getitem__) if values else "ERROR"


def summarize(rows):
    require(bool(rows), "cannot summarize an empty measurement inventory")
    errors = sorted(row["relativeError"] for row in rows)
    return dict(benchmarkCount=len(rows), medianRelativeError=statistics.median(errors),
                p90RelativeError=errors[math.ceil(.9 * len(errors)) - 1],
                lowPrecisionCount=sum(row["precisionStatus"] == "LOW_PRECISION" for row in rows),
                inconclusiveCount=sum(row["status"] == "INCONCLUSIVE" for row in rows),
                failedCount=sum(row["status"] == "FAILED" for row in rows),
                status=worst_status(row["status"] for row in rows))
