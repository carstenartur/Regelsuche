#!/usr/bin/env python3
"""Collect existing #514 reports and their exact inputs; never execute a gate."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

from release_readiness_files import EvidenceFiles
from quality_acceptance_structure import (DATABASE_MANIFEST, LATEST_SCAN, POLICIES,
    SCANNER_EXECUTION, SCANNER_INPUTS, SCANNER_MANIFEST, require_structure)

QUALITY = "build/reports/quality/"
HISTORY_POLICY = "config/quality/jmh-history-policy.json"
HISTORY_REPORT = QUALITY + "jmh-history/history.json"
VISUAL_POLICY = "app/src/e2eTest/resources/screenshots/visual-regression-policy.json"
HTTP_REPORT = "app/build/test-results/test/TEST-de.regelsuche.web.WebWorkbenchServerRequestLimitTest.xml"
SCAN_POLICY = "config/quality/supply-chain-vulnerability-policy.json"
SCAN_INVENTORY = QUALITY + "supply-chain"
SCAN_BASE = SCAN_INVENTORY + "/vulnerability"
NO_HASH_BINDING = object()

# This is a retention map, not a second threshold or exception policy.
SECTIONS = {
    "collection": {
        "policyFiles": [],
        "supportFiles": ["docs/quality-acceptance-evidence.md",
                         "scripts/collect-quality-acceptance-evidence.py",
                         "scripts/quality_acceptance_structure.py",
                         "scripts/release_readiness_files.py"]},
    "coverage": {
        "policyFiles": ["config/quality/coverage-policy.json"],
        "supportFiles": ["scripts/verify-coverage-regression.py"]},
    "jmh": {
        "policyFiles": ["config/quality/jmh-regression-policy-v2.json",
                        "config/quality/jmh-regression-decision-policy-v3.json",
                        "config/quality/jmh-baseline.json"],
        "supportFiles": ["scripts/verify-jmh-regression-v3.py",
                         "scripts/verify-jmh-benchmark.py"]},
    "history": {
        "policyFiles": [HISTORY_POLICY],
        "supportFiles": ["docs/performance-history.md"]},
    "complexity": {
        "policyFiles": ["config/quality/complexity-hotspots.json",
                        "ai-knowledge/complexity-baseline.json"],
        "supportFiles": ["scripts/verify-complexity-hotspots.py"]},
    "visual": {
        "policyFiles": [VISUAL_POLICY],
        "supportFiles": ["Dockerfile.visual-regression",
                         "app/src/e2eTest/java/de/regelsuche/e2e/ScreenshotDiffUtil.java"]},
    "http": {
        "policyFiles": ["app/src/main/java/de/regelsuche/web/WebSecurityConfig.java"],
        "supportFiles": ["docs/web-workbench-security.md",
                         "app/src/test/java/de/regelsuche/web/WebWorkbenchServerRequestLimitTest.java"]},
    "inventory": {
        "policyFiles": ["config/quality/supply-chain-policy.json"],
        "supportFiles": ["docs/supply-chain-evidence.md"]},
    "vulnerability": {
        "policyFiles": [SCAN_POLICY],
        "supportFiles": ["docs/supply-chain-vulnerability-evidence.md"]},
    "testCost": {
        "policyFiles": [],
        "supportFiles": ["docs/release-evidence-task-reuse.md",
                         "docs/evidence/release-evidence-task-reuse-v1.json",
                         "docs/evidence/release-evidence-integrity-review-v1.json"]},
}

# section -> (source, native schema, native outcome field, current value fields)
REPORTS = {
    "coverage": [(QUALITY + "coverage-report.json", "regelsuche.quality.coverage-report/v1",
                  "status", ["aggregate", "modules", "ratchetPolicy", "violations"])],
    "jmh": [(QUALITY + "jmh-regression-report.json", "regelsuche.quality.jmh-regression-report/v3",
             "status", ["benchmarks", "baselineRevision", "baselineArtifactDigest",
                        "thresholdPolicyGitBlobSha1", "decisionStatistic", "lowPrecisionBenchmarks",
                        "inconclusiveBenchmarks", "violations"]),
            ("app/build/reports/jmh/result.json", None, None, []),
            ("app/build/reports/jmh-allocation/result.json", None, None, []),
            ("public/dev/bench/allocation.json", None, None, []),
            ("regelsuche-math-sympy/build/reports/jmh/sympy-factorization.json", None, None, []),
            ("public/dev/bench/sympy-factorization.json", None, None, [])],
    "history": [(HISTORY_REPORT, "regelsuche.quality.jmh-history/v1", "status",
                 ["snapshotCount", "benchmarkCount", "snapshots", "benchmarks"])],
    "complexity": [(QUALITY + "complexity-hotspot-report.json", "regelsuche.quality.complexity-hotspot-report/v1",
                    "status", ["allowedCognitiveIncrease", "allowedCyclomaticIncrease", "changes",
                               "activeExceptions", "violations"]),
                   ("build/ai-knowledge/check.json", None, None, []),
                   ("build/ai-knowledge/complexity.json", None, None, [])],
    "inventory": [(SCAN_INVENTORY + "/bom.json", None, None, ["components", "dependencies"]),
                  (SCAN_INVENTORY + "/dependency-inventory.json", "regelsuche.dependency-inventory/v1",
                   None, ["components", "dependencies"]),
                  (SCAN_INVENTORY + "/supply-chain-evidence.json", "regelsuche.supply-chain-evidence/v1",
                   "vulnerabilityScanStatus", ["componentCount", "dependencyNodeCount", "dependencyEdgeCount",
                                               "policyHash", "inventoryHash"])],
    "testCost": [(QUALITY + "slow-tests.json", "regelsuche.quality.slow-tests/v1", None,
                  ["suiteCount", "testCount", "slowThresholdSeconds", "slowTestCount",
                   "totalTestSeconds", "slowestTests", "slowestClasses"])],
}

CLAIM = ("COMPLETE describes the finite #514 retention map, not successful execution or a new gate decision. "
         "Outcomes and values are copied from the named reports. Checkout identity identifies collection; "
         "it does not authenticate when or from which revision inputs were executed. Run the existing gates "
         "in a clean checkout first. Historical task-reuse receipts are not current measurements. "
         "No downloaded CI artifact ZIP, signature, mathematical claim or security certification is verified.")


def sha256(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key: " + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError("non-finite JSON number: " + value)


class Collection:
    def __init__(self, source):
        self.source = source
        self.files = {}
        self.problems = []
        self.sections = {key: {**value, "outcomes": [], "currentValueBindings": []}
                         for key, value in SECTIONS.items()}

    def problem(self, section, path, reason):
        self.problems.append({"section": section, "path": str(path), "reason": str(reason)})

    def retain(self, section, path, expected=NO_HASH_BINDING):
        try:
            path = str(Path(path))
            data = self.source.read_bytes(path)
            if expected is not NO_HASH_BINDING and sha256(data) != expected:
                raise ValueError("byte hash binding differs")
            self.files[path] = data
            return data
        except (OSError, ValueError) as failure:
            self.problem(section, path, failure)
            return None

    def document(self, section, path, schema=None, outcome=None, fields=(), expected=NO_HASH_BINDING,
                 structure=None):
        data = self.retain(section, path, expected)
        if data is None:
            return None
        try:
            value = json.loads(data, object_pairs_hook=unique_object, parse_constant=reject_constant)
            if not isinstance(value, (dict, list)):
                raise ValueError("report must be a JSON object or array")
            if schema is not None and value.get("schema") != schema:
                raise ValueError("report schema differs: expected " + schema)
            if structure is not None:
                require_structure(value, structure)
            if outcome:
                if not isinstance(value.get(outcome), str) or not value[outcome]:
                    raise ValueError("missing native outcome: " + outcome)
                self.sections[section]["outcomes"].append(
                    {"sourcePath": path, "field": outcome, "value": value[outcome]})
            for field in fields:
                if field not in value:
                    raise ValueError("missing current value field: " + field)
            if fields:
                self.sections[section]["currentValueBindings"].append({
                    "sourcePath": path, "sha256": sha256(data),
                    "fields": {key: value[key] for key in fields}})
            return value
        except (ValueError, TypeError, AttributeError) as failure:
            self.problem(section, path, failure)
            return None

    def bound(self, section, reference, prefix=""):
        path = prefix + reference["path"]
        self.retain(section, path, reference["sha256"])
        return path

    def history(self):
        policy = self.document("history", HISTORY_POLICY)
        report = self.document("history", HISTORY_REPORT)
        if policy is not None:
            for snapshot in policy["snapshots"]:
                self.bound("history", snapshot)
        if report is not None:
            contract = self.document("history", "config/quality/jmh-regression-policy-v2.json")
            expected = {row["benchmark"] for row in contract["benchmarks"]}
            names = [row["benchmark"] for row in report["benchmarks"]]
            charts = [row["chart"] for row in report["benchmarks"]]
            if (not expected or set(names) != expected or len(names) != len(expected)
                    or report["benchmarkCount"] != len(expected) or len(set(charts)) != len(charts)):
                raise ValueError("history chart inventory differs from the frozen benchmark inventory")
            for benchmark in report["benchmarks"]:
                chart = Path(benchmark["chart"])
                if chart.is_absolute() or len(chart.parts) != 2 or chart.parts[0] != "charts" or chart.suffix != ".svg":
                    raise ValueError("invalid history chart child path")
                self.retain("history", QUALITY + "jmh-history/" + str(chart))

    def visual(self):
        policy = self.document("visual", VISUAL_POLICY)
        if policy is None:
            return
        for name, identity in policy["baselines"].items():
            if Path(name).name != name or not name.endswith(".png"):
                raise ValueError("invalid visual baseline child path")
            baseline = "app/src/e2eTest/resources/screenshots/baseline/" + name
            data = self.retain("visual", baseline)
            if data is not None:
                blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
                if blob != identity["gitBlobSha"] or len(data) != identity["byteLength"]:
                    self.problem("visual", baseline, "baseline policy binding differs")
            stem = "build/reports/visual-regression/" + name[:-4]
            receipt = self.document("visual", stem + ".comparison.json", "regelsuche.visual-comparison/v1",
                                    "status", ["changedPixels", "totalPixels", "diffRatio", "channelTolerance",
                                               "maxDiffRatio", "baselineSha256", "actualSha256"])
            self.retain("visual", stem + ".actual.png", NO_HASH_BINDING if receipt is None else receipt["actualSha256"])
            if receipt is not None:
                self.retain("visual", baseline, receipt["baselineSha256"])
                for field in ("channelTolerance", "maxDiffRatio"):
                    if receipt[field] != policy["comparison"][field]:
                        self.problem("visual", stem + ".comparison.json", "comparison policy binding differs")
                if receipt["changedPixels"] > 0:
                    self.retain("visual", stem + ".diff.png")

    def http(self):
        data = self.retain("http", HTTP_REPORT)
        if data is not None:
            report = ET.fromstring(data)
            if report.tag != "testsuite" or report.get("name") != "de.regelsuche.web.WebWorkbenchServerRequestLimitTest":
                raise ValueError("HTTP request-limit suite identity differs")
            counts = {field: int(report.attrib[field]) for field in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or any(value < 0 for value in counts.values()):
                raise ValueError("HTTP request-limit suite has no valid current counts")
            self.sections["http"]["currentValueBindings"].append({
                "sourcePath": HTTP_REPORT, "sha256": sha256(data), "fields": counts})

    def vulnerability(self):
        section = "vulnerability"
        latest = self.document(section, SCAN_BASE + "/latest.json", structure=LATEST_SCAN)
        policy = self.document(section, SCAN_POLICY)
        if latest is None or policy is None:
            return
        run = Path(latest["run"])
        if run.parent != Path(SCAN_BASE + "/runs") or run.name in ("", ".", ".."):
            raise ValueError("latest scanner run is not a direct retained run child")
        run = str(run) + "/"
        evidence = self.document(section, run + "vulnerability-evidence.json",
            "regelsuche.supply-chain-vulnerability-evidence/v1", "decision",
            ["evaluatedAt", "databaseAsOf", "databaseHash", "scannerHash", "findings", "errors", "execution"],
            expected=latest["evidenceHash"])
        if evidence is None:
            return
        if evidence["decision"] != latest["decision"]:
            self.problem(section, SCAN_BASE + "/latest.json", "latest decision binding differs")
        for name, key, current in (
            ("bom.json", "rawBomHash", SCAN_INVENTORY + "/bom.json"),
            ("dependency-inventory.json", "inventoryHash", SCAN_INVENTORY + "/dependency-inventory.json"),
            ("inventory-evidence-v1.json", "inventoryEvidenceHash", SCAN_INVENTORY + "/supply-chain-evidence.json"),
            ("vulnerability-policy.json", "policyHash", SCAN_POLICY),
            ("database-manifest.json", "databaseManifestHash", policy["databaseManifest"]["path"]),
            ("scanner-manifest.json", "scannerManifestHash", policy["scannerManifest"]["path"])):
            self.retain(section, run + name, evidence[key])
            self.retain(section, current, evidence[key])
        database = self.document(section, policy["databaseManifest"]["path"],
            "regelsuche.supply-chain-advisory-snapshot/v1", expected=policy["databaseManifest"]["sha256"],
            structure=DATABASE_MANIFEST)
        scanner = self.document(section, policy["scannerManifest"]["path"],
            "regelsuche.supply-chain-scanner/v1", expected=policy["scannerManifest"]["sha256"],
            structure=SCANNER_MANIFEST)
        if database is not None and scanner is not None:
            references = [database["archive"], database["metadata"], database["upstreamRetention"], *database["licenses"],
                          *(scanner[key] for key in ("license", "release", "tag", "checksums", "provenance"))]
            for reference in references:
                self.bound(section, reference)
                self.bound(section, reference, run + "authority/")
            for path in (SCAN_POLICY, policy["inventoryPolicy"], policy["databaseManifest"]["path"],
                         policy["scannerManifest"]["path"]):
                data = self.retain(section, path)
                if data is not None:
                    self.retain(section, run + "authority/" + path, sha256(data))
            self.retain(section, run + "cache/osv-scalibr/Maven/all.zip", database["archive"]["sha256"])
            if (evidence["databaseHash"] != database["archive"]["sha256"]
                    or evidence["databaseAsOf"] != database["createdAt"] or evidence["scannerHash"] != scanner["sha256"]):
                self.problem(section, run + "vulnerability-evidence.json", "scanner/database identity binding differs")
        inputs = self.document(section, run + "scanner-inputs.json", expected=evidence["scannerInputsHash"],
                               structure=SCANNER_INPUTS)
        if inputs is not None:
            inventory = self.document(section, SCAN_INVENTORY + "/dependency-inventory.json")
            required = {component["purl"] for component in inventory["components"]}
            present = [component["purl"] for component in inputs["components"]]
            sources = [component["source"] for component in inputs["components"]]
            if (not required or set(present) != required or len(present) != len(required)
                    or len(set(sources)) != len(sources)):
                raise ValueError("scanner input file inventory differs from the complete resolved PURL inventory")
            for component in inputs["components"]:
                child = Path(component["source"])
                if child.parent != Path("inputs"):
                    raise ValueError("scanner input is not a direct input child")
                self.retain(section, run + str(child), component["sha256"])
        execution = self.document(section, run + "scanner-execution.json", structure=SCANNER_EXECUTION)
        if execution is not None:
            if execution != evidence["execution"]:
                self.problem(section, run + "scanner-execution.json", "execution receipt binding differs")
            for field, name in (("stdout", "scanner.stdout.json"), ("stderr", "scanner.stderr.log")):
                if execution[field + "Path"] != name:
                    raise ValueError("scanner stream path differs")
                self.retain(section, run + name, execution[field + "Hash"])
        self.retain(section, run + "osv-scanner.toml")

    def read(self):
        for section, spec in SECTIONS.items():
            for path in spec["policyFiles"]:
                if path.endswith(".json"):
                    schema, structure = POLICIES[path]
                    self.document(section, path, schema, structure=structure)
                else:
                    self.retain(section, path)
            for path in spec["supportFiles"]:
                self.retain(section, path)
        for section, reports in REPORTS.items():
            for path, schema, outcome, fields in reports:
                self.document(section, path, schema, outcome, fields)
        for section in ("history", "visual", "http", "vulnerability"):
            try:
                getattr(self, section)()
            except (KeyError, TypeError, ValueError, AttributeError, ET.ParseError) as failure:
                self.problem(section, section + " bindings", failure)


def checkout_identity(root):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(root), *args], text=True, stderr=subprocess.PIPE).strip()
    if Path(git("rev-parse", "--show-toplevel")) != root:
        raise ValueError("collection root is not the checkout root")
    return {"revision": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}"),
            "trackedChangesPresent": bool(git("status", "--porcelain", "--untracked-files=no"))}


def collect(root, output):
    root = Path(root).absolute()
    output = Path(output)
    if output.is_absolute() or ".." in output.parts or output.parts[:3] != ("build", "reports", "quality"):
        raise ValueError("output must be a new directory beneath build/reports/quality")
    with EvidenceFiles(root) as source:
        collection = Collection(source)
        collection.read()
        identity = checkout_identity(root)
        for section, value in collection.sections.items():
            value["availability"] = "UNAVAILABLE" if any(p["section"] == section for p in collection.problems) else "RETAINED"
        result = {"schema": "regelsuche.quality.acceptance-evidence-index/v1", "checkout": identity,
                  "claimBoundary": CLAIM, "completeness": "INCOMPLETE" if collection.problems else "COMPLETE",
                  "sections": collection.sections, "problems": collection.problems,
                  "files": [{"sourcePath": path, "path": "files/" + path, "bytes": len(data), "sha256": sha256(data)}
                            for path, data in sorted(collection.files.items())]}
        parent = root
        for part in output.parts[:-1]:
            parent = parent / part
            if parent.is_symlink():
                raise ValueError("symbolic output parent is forbidden")
            parent.mkdir(exist_ok=True)
        destination = root / output
        destination.mkdir()  # Fresh directory only: never report a stale index as this collection.
        for entry in result["files"]:
            target = destination / entry["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(collection.files[entry["sourcePath"]])
        lines = ["# Quality acceptance evidence", "", "Completeness: **" + result["completeness"] + "**", "",
                 "Collection revision: `" + identity["revision"] + "`; tree: `" + identity["tree"] + "`.", "", CLAIM, "",
                 "[Policy, baseline, exception and measurement map](files/docs/quality-acceptance-evidence.md)", "",
                 "| Section | Evidence availability | Reported outcomes |", "| --- | --- | --- |"]
        for section, value in result["sections"].items():
            outcomes = ", ".join("[" + item["value"] + "](files/" + item["sourcePath"] + ")" for item in value["outcomes"])
            lines.append(f"| {section} | {value['availability']} | {outcomes or 'No native outcome field; see current value bindings in index.json'} |")
        lines += ["", "Exact policies, baselines, exception identities and measurements are retained under `files/`.",
                  "`index.json` binds every retained file by SHA-256 and projects native current value fields.", ""]
        for problem in result["problems"]:
            lines.append("- " + problem["section"] + ": `" + problem["path"] + "` — " + problem["reason"])
        (destination / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        (destination / "index.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(QUALITY + "acceptance-evidence"))
    parser.add_argument("--require-complete", action="store_true")
    args = parser.parse_args()
    try:
        result = collect(args.root, args.output)
        print("qualityAcceptanceEvidence=" + result["completeness"])
        print("qualityAcceptanceIndex=" + str(args.output / "index.json"))
        return int(args.require_complete and result["completeness"] != "COMPLETE")
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        print("qualityAcceptanceEvidence=ERROR: " + str(failure), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
