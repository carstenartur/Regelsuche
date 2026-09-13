#!/usr/bin/env python3
"""Synthetic controls for evidence collection; no gate or experiment is executed."""

import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location(
    "collector", ROOT / "scripts/collect-quality-acceptance-evidence.py")
collector = importlib.util.module_from_spec(spec)
spec.loader.exec_module(collector)


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def write(root, name, value):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    data = value if isinstance(value, bytes) else (json.dumps(value) + "\n").encode()
    path.write_bytes(data)
    return digest(data)


class EvidenceCollectionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture",
                        "-c", "user.email=fixture@example.invalid", "commit", "-q",
                        "--allow-empty", "-m", "Synthetic evidence fixture"], check=True)

    def complete_fixture(self):
        # Actual checkout policies, baseline bytes and historical receipts; only
        # current reports below are synthetic and never presented as real runs.
        for section in collector.SECTIONS.values():
            for name in section["policyFiles"] + section["supportFiles"]:
                target = self.root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / name, target)
        for section_id, reports in collector.REPORTS.items():
            for path, schema, outcome, fields in reports:
                value = {field: [] for field in fields}
                if schema:
                    value["schema"] = schema
                if outcome:
                    value[outcome] = "NOT_EVALUATED" if section_id == "inventory" else "PASSED"
                write(self.root, path, value)
        history = json.loads((self.root / collector.HISTORY_POLICY).read_bytes())
        for snapshot in history["snapshots"]:
            write(self.root, snapshot["path"], (ROOT / snapshot["path"]).read_bytes())
        benchmarks = json.loads((ROOT / "config/quality/jmh-regression-policy-v2.json").read_bytes())["benchmarks"]
        history_benchmarks = [{"benchmark": row["benchmark"], "chart": f"charts/fixture-{i}.svg"}
                              for i, row in enumerate(benchmarks)]
        write(self.root, collector.HISTORY_REPORT, {
            "schema": "regelsuche.quality.jmh-history/v1", "status": "PASSED",
            "snapshotCount": 2, "benchmarkCount": len(benchmarks), "snapshots": [],
            "benchmarks": history_benchmarks})
        for row in history_benchmarks:
            write(self.root, "build/reports/quality/jmh-history/" + row["chart"], b"<svg/>\n")
        policy = json.loads((self.root / collector.VISUAL_POLICY).read_bytes())
        for name in policy["baselines"]:
            baseline = "app/src/e2eTest/resources/screenshots/baseline/" + name
            write(self.root, baseline, (ROOT / baseline).read_bytes())
            stem = name[:-4]
            write(self.root, f"build/reports/visual-regression/{stem}.actual.png", (ROOT / baseline).read_bytes())
            write(self.root, f"build/reports/visual-regression/{stem}.comparison.json", {
                "schema": "regelsuche.visual-comparison/v1", "status": "PASSED",
                "changedPixels": 0, "totalPixels": 100, "diffRatio": 0.0,
                "channelTolerance": 12, "maxDiffRatio": 0.002,
                "baselineSha256": digest((ROOT / baseline).read_bytes()),
                "actualSha256": digest((ROOT / baseline).read_bytes())})
        write(self.root, collector.HTTP_REPORT,
              b'<testsuite name="de.regelsuche.web.WebWorkbenchServerRequestLimitTest" '
              b'tests="4" failures="0" errors="0" skipped="0" time="0.125">'
              b'<testcase name="synthetic boundary control"/></testsuite>')
        self.scanner_fixture()

    def scanner_fixture(self):
        self.run = collector.SCAN_BASE + "/runs/synthetic-fixture"
        components = [{"purl": "pkg:maven/example/fixture@1"}, {"purl": "pkg:maven/example/second@1"}]
        write(self.root, collector.SCAN_INVENTORY + "/bom.json", {"components": components, "dependencies": []})
        write(self.root, collector.SCAN_INVENTORY + "/dependency-inventory.json", {
            "schema": "regelsuche.dependency-inventory/v1", "components": components, "dependencies": []})
        policy = json.loads((self.root / collector.SCAN_POLICY).read_bytes())
        authority = [collector.SCAN_POLICY, policy["inventoryPolicy"]]
        manifests = {}
        for field in ("databaseManifest", "scannerManifest"):
            ref = policy[field]
            write(self.root, ref["path"], (ROOT / ref["path"]).read_bytes())
            authority.append(ref["path"])
            manifests[field] = json.loads((ROOT / ref["path"]).read_bytes())
        database, scanner = manifests.values()
        for ref in [database["archive"], database["metadata"], database["upstreamRetention"],
                    *database["licenses"], *(scanner[key] for key in
                    ("license", "release", "tag", "checksums", "provenance"))]:
            write(self.root, ref["path"], (ROOT / ref["path"]).read_bytes())
            authority.append(ref["path"])
        for path in authority:
            write(self.root, self.run + "/authority/" + path, (self.root / path).read_bytes())
        evidence = {"schema": "regelsuche.supply-chain-vulnerability-evidence/v1",
                    "decision": "PASS", "findings": [], "errors": [],
                    "evaluatedAt": "SYNTHETIC_FIXTURE", "databaseAsOf": database["createdAt"],
                    "databaseHash": database["archive"]["sha256"], "scannerHash": scanner["sha256"]}
        for name, key, source in (
            ("bom.json", "rawBomHash", collector.SCAN_INVENTORY + "/bom.json"),
            ("dependency-inventory.json", "inventoryHash", collector.SCAN_INVENTORY + "/dependency-inventory.json"),
            ("inventory-evidence-v1.json", "inventoryEvidenceHash", collector.SCAN_INVENTORY + "/supply-chain-evidence.json"),
            ("vulnerability-policy.json", "policyHash", collector.SCAN_POLICY),
            ("database-manifest.json", "databaseManifestHash", policy["databaseManifest"]["path"]),
            ("scanner-manifest.json", "scannerManifestHash", policy["scannerManifest"]["path"])):
            evidence[key] = write(self.root, self.run + "/" + name, (self.root / source).read_bytes())
        input_hash = write(self.root, self.run + "/inputs/fixture.cdx.json", {"synthetic": True})
        second_hash = write(self.root, self.run + "/inputs/second.cdx.json", {"synthetic": "second"})
        evidence["scannerInputsHash"] = write(self.root, self.run + "/scanner-inputs.json", {
            "components": [{"purl": "pkg:maven/example/fixture@1", "source": "inputs/fixture.cdx.json", "sha256": input_hash},
                           {"purl": "pkg:maven/example/second@1", "source": "inputs/second.cdx.json", "sha256": second_hash}]})
        execution = {"outcome": "EXITED", "exitCode": 0, "command": ["SYNTHETIC_FIXTURE"]}
        for field, name, data in (("stdout", "scanner.stdout.json", b"{}\n"),
                                  ("stderr", "scanner.stderr.log", b"")):
            execution[field + "Path"] = name
            execution[field + "Hash"] = write(self.root, self.run + "/" + name, data)
        evidence["execution"] = execution
        write(self.root, self.run + "/scanner-execution.json", execution)
        write(self.root, self.run + "/cache/osv-scalibr/Maven/all.zip", (self.root / database["archive"]["path"]).read_bytes())
        write(self.root, self.run + "/osv-scanner.toml", b"# synthetic fixture\n")
        self.scanner_evidence = evidence
        self.retain_scanner_decision()

    def retain_scanner_decision(self):
        evidence_hash = write(self.root, self.run + "/vulnerability-evidence.json", self.scanner_evidence)
        write(self.root, collector.SCAN_BASE + "/latest.json", {
            "run": self.run, "decision": self.scanner_evidence["decision"], "evidenceHash": evidence_hash})

    def collect(self, name="collected"):
        return collector.collect(self.root, Path("build/reports/quality") / name)

    def test_missing_current_reports_are_explicit_and_strict_cli_fails(self):
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertEqual("UNAVAILABLE", result["sections"]["coverage"]["availability"])
        completed = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/collect-quality-acceptance-evidence.py"),
                                    "--root", str(self.root), "--output", "build/reports/quality/strict",
                                    "--require-complete"], capture_output=True, text=True)
        self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
        self.assertTrue((self.root / "build/reports/quality/strict/index.json").is_file())

    def test_complete_bundle_preserves_exact_bytes_and_native_failed_outcome(self):
        self.complete_fixture()
        self.scanner_evidence["decision"] = "FAIL"
        self.scanner_evidence["findings"] = [{"fixture": "threshold violation"}]
        self.retain_scanner_decision()
        result = self.collect()
        self.assertEqual([], result["problems"])
        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual("FAIL", result["sections"]["vulnerability"]["outcomes"][0]["value"])
        self.assertNotIn("status", result)
        for entry in result["files"]:
            copied = self.root / "build/reports/quality/collected" / entry["path"]
            self.assertEqual((self.root / entry["sourcePath"]).read_bytes(), copied.read_bytes())
            self.assertEqual(entry["sha256"], digest(copied.read_bytes()))
        self.assertEqual(result, self.collect("second"))

    def test_each_missing_scanner_binding_prevents_complete_bundle(self):
        self.complete_fixture()
        for index, name in enumerate(("scanner.stderr.log", "inputs/fixture.cdx.json",
                                      "cache/osv-scalibr/Maven/all.zip", "authority/" + collector.SCAN_POLICY)):
            path = self.root / self.run / name
            original = path.read_bytes()
            path.unlink()
            result = self.collect("missing-" + str(index))
            self.assertEqual("INCOMPLETE", result["completeness"], name)
            self.assertTrue(any(name in item["path"] for item in result["problems"]), name)
            path.write_bytes(original)

    def test_current_inventory_and_retained_scan_must_match(self):
        self.complete_fixture()
        write(self.root, collector.SCAN_INVENTORY + "/bom.json", {"different": "current inventory"})
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertTrue(any("binding" in problem["reason"] for problem in result["problems"]))

    def test_symlink_and_parent_traversal_never_read_external_members(self):
        self.complete_fixture()
        path = self.root / self.run / "scanner.stdout.json"
        path.unlink()
        path.symlink_to(ROOT / "pom.xml")
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])
        write(self.root, collector.SCAN_BASE + "/latest.json", {
            "run": "../outside", "decision": "PASS", "evidenceHash": "sha256:" + "0" * 64})
        self.assertEqual("INCOMPLETE", self.collect("traversal")["completeness"])
        with self.assertRaises(ValueError):
            collector.collect(self.root, Path("../external-output"))

    def test_missing_visual_receipt_or_chart_is_incomplete(self):
        self.complete_fixture()
        (self.root / "build/reports/visual-regression/inspector.comparison.json").unlink()
        (self.root / "build/reports/quality/jmh-history/charts/fixture-0.svg").unlink()
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertEqual({"visual", "history"}, {p["section"] for p in result["problems"]})

    def test_rehashed_input_omission_cannot_hide_required_component_file(self):
        self.complete_fixture()
        inputs = json.loads((self.root / self.run / "scanner-inputs.json").read_bytes())
        inputs["components"].pop()
        (self.root / self.run / "inputs/second.cdx.json").unlink()
        self.scanner_evidence["scannerInputsHash"] = write(self.root, self.run + "/scanner-inputs.json", inputs)
        self.retain_scanner_decision()
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])

    def test_removed_chart_declaration_cannot_hide_required_benchmark(self):
        self.complete_fixture()
        history = json.loads((self.root / collector.HISTORY_REPORT).read_bytes())
        history["benchmarks"].pop()
        write(self.root, collector.HISTORY_REPORT, history)
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])

    def test_null_hash_and_duplicate_json_cannot_disable_input_binding(self):
        self.complete_fixture()
        latest = json.loads((self.root / collector.SCAN_BASE / "latest.json").read_bytes())
        latest["evidenceHash"] = None
        write(self.root, collector.SCAN_BASE + "/latest.json", latest)
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])
        write(self.root, collector.SCAN_BASE + "/latest.json", b'{"decision":"FAIL","decision":"PASS"}')
        self.assertEqual("INCOMPLETE", self.collect("duplicate-json")["completeness"])

    def test_early_scanner_error_keeps_native_outcome_with_missing_measurements(self):
        self.complete_fixture()
        self.scanner_evidence = {"schema": "regelsuche.supply-chain-vulnerability-evidence/v1",
                                 "decision": "ERROR", "errors": ["SYNTHETIC provisioning error"],
                                 "evaluatedAt": "SYNTHETIC_FIXTURE"}
        self.retain_scanner_decision()
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertEqual("UNAVAILABLE", result["sections"]["vulnerability"]["availability"])
        self.assertEqual("ERROR", result["sections"]["vulnerability"]["outcomes"][0]["value"])

    def test_empty_scanner_selector_cannot_hide_entire_missing_run(self):
        self.complete_fixture()
        write(self.root, collector.SCAN_BASE + "/latest.json", {})
        shutil.rmtree(self.root / self.run)
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertEqual("UNAVAILABLE", result["sections"]["vulnerability"]["availability"])

    def test_empty_execution_receipt_cannot_hide_missing_native_streams(self):
        self.complete_fixture()
        write(self.root, self.run + "/scanner-execution.json", {})
        for name in ("scanner.stdout.json", "scanner.stderr.log"):
            (self.root / self.run / name).unlink()
        result = self.collect()
        self.assertEqual("INCOMPLETE", result["completeness"])
        self.assertEqual("UNAVAILABLE", result["sections"]["vulnerability"]["availability"])

    def test_empty_rehashed_scanner_inputs_cannot_hide_all_component_files(self):
        self.complete_fixture()
        self.scanner_evidence["scannerInputsHash"] = write(self.root, self.run + "/scanner-inputs.json", {})
        self.retain_scanner_decision()
        shutil.rmtree(self.root / self.run / "inputs")
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])

    def test_empty_visual_policy_cannot_hide_missing_comparisons(self):
        self.complete_fixture()
        write(self.root, collector.VISUAL_POLICY, {})
        shutil.rmtree(self.root / "build/reports/visual-regression")
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])

    def test_scalar_selector_json_is_an_incomplete_diagnostic(self):
        self.complete_fixture()
        for index, value in enumerate((None, False, 0, "", [], {"run": 1})):
            with self.subTest(value=value):
                write(self.root, collector.SCAN_BASE + "/latest.json", value)
                result = self.collect("scalar-" + str(index))
                self.assertEqual("INCOMPLETE", result["completeness"])
                self.assertEqual("UNAVAILABLE", result["sections"]["vulnerability"]["availability"])

    def test_empty_history_policy_cannot_hide_required_snapshots(self):
        self.complete_fixture()
        policy = json.loads((self.root / collector.HISTORY_POLICY).read_bytes())
        write(self.root, collector.HISTORY_POLICY, {})
        for snapshot in policy["snapshots"]:
            (self.root / snapshot["path"]).unlink()
        self.assertEqual("INCOMPLETE", self.collect()["completeness"])

    def test_each_json_policy_requires_available_structure(self):
        self.complete_fixture()
        for section, entry in collector.SECTIONS.items():
            for name in entry["policyFiles"]:
                if not name.endswith(".json"):
                    continue
                path = self.root / name
                original = path.read_bytes()
                for value in ({}, [], None):
                    with self.subTest(path=name, value=value):
                        write(self.root, name, value)
                        with collector.EvidenceFiles(self.root) as source:
                            collection = collector.Collection(source)
                            collection.read()
                        self.assertTrue(any(problem["section"] == section for problem in collection.problems),
                                        "malformed policy omitted its required acceptance structure")
                path.write_bytes(original)

    def test_missing_threshold_and_exception_fields_prevent_complete_retention(self):
        self.complete_fixture()
        mutations = (
            ("config/quality/coverage-policy.json", ("aggregate", "lineMinimumPercent")),
            ("config/quality/coverage-policy.json", ("modules", "app", "branchMinimumPercent")),
            ("config/quality/jmh-regression-policy-v2.json", ("baselineArtifactDigest",)),
            ("config/quality/jmh-regression-decision-policy-v3.json", ("decisionStatistic",)),
            ("config/quality/jmh-baseline.json", ("measurementPolicy", "materialRegressionRatio")),
            ("config/quality/complexity-hotspots.json", ("allowedCognitiveIncrease",)),
            ("config/quality/complexity-hotspots.json", ("exceptions",)),
        )
        for index, (name, keys) in enumerate(mutations):
            with self.subTest(path=name, keys=keys):
                path = self.root / name
                original = path.read_bytes()
                policy = json.loads(original)
                parent = policy
                for key in keys[:-1]:
                    parent = parent[key]
                parent.pop(keys[-1])
                write(self.root, name, policy)
                self.assertEqual("INCOMPLETE", self.collect("missing-policy-field-" + str(index))["completeness"])
            path.write_bytes(original)

    def test_empty_declared_visual_and_history_inventories_are_unavailable(self):
        self.complete_fixture()
        for index, (name, field, empty) in enumerate((
                (collector.VISUAL_POLICY, "baselines", {}),
                (collector.HISTORY_POLICY, "snapshots", []))):
            with self.subTest(path=name):
                path = self.root / name
                original = path.read_bytes()
                policy = json.loads(original)
                policy[field] = empty
                write(self.root, name, policy)
                self.assertEqual("INCOMPLETE", self.collect("empty-inventory-" + str(index))["completeness"])
            path.write_bytes(original)

    def test_rehashed_empty_scanner_manifests_do_not_hide_authority_files(self):
        for index, (field, value) in enumerate((
                ("databaseManifest", {}), ("databaseManifest", []), ("databaseManifest", None),
                ("scannerManifest", {}), ("scannerManifest", []), ("scannerManifest", None))):
            with self.subTest(field=field, value=value):
                self.complete_fixture()
                policy = json.loads((self.root / collector.SCAN_POLICY).read_bytes())
                ref = policy[field]
                ref["sha256"] = write(self.root, ref["path"], value)
                prefix = "database" if field == "databaseManifest" else "scanner"
                self.scanner_evidence[field + "Hash"] = write(
                    self.root, self.run + "/" + prefix + "-manifest.json", value)
                write(self.root, self.run + "/authority/" + ref["path"], value)
                self.scanner_evidence["policyHash"] = write(self.root, collector.SCAN_POLICY, policy)
                write(self.root, self.run + "/vulnerability-policy.json", policy)
                write(self.root, self.run + "/authority/" + collector.SCAN_POLICY, policy)
                self.retain_scanner_decision()
                (self.root / self.run / "cache/osv-scalibr/Maven/all.zip").unlink()
                self.assertEqual("INCOMPLETE", self.collect("empty-manifest-" + str(index))["completeness"])

    def test_retained_failed_policy_and_expired_exception_are_not_reevaluated(self):
        self.complete_fixture()
        name = "config/quality/complexity-hotspots.json"
        policy = json.loads((self.root / name).read_bytes())
        policy["exceptions"] = [{"id": "historical-fixture", "sourceFile": "fixture.java",
            "signature": "fixture()", "maximumCognitiveComplexity": 0,
            "maximumCyclomaticComplexity": 0, "rationale": "Synthetic historical failure",
            "expiresOn": "2000-01-01"}]
        write(self.root, name, policy)
        report_path = collector.QUALITY + "complexity-hotspot-report.json"
        report = json.loads((self.root / report_path).read_bytes())
        report["status"] = "FAILED"
        report["violations"] = ["Synthetic historical expired exception"]
        write(self.root, report_path, report)
        result = self.collect()
        self.assertEqual("COMPLETE", result["completeness"])
        self.assertEqual("FAILED", result["sections"]["complexity"]["outcomes"][0]["value"])
        history = "docs/evidence/release-evidence-task-reuse-v1.json"
        retained = self.root / "build/reports/quality/collected/files" / history
        self.assertEqual((ROOT / history).read_bytes(), retained.read_bytes())
        self.assertTrue(all(binding["sourcePath"] != history
                            for section in result["sections"].values()
                            for binding in section["currentValueBindings"]))

    def test_collection_never_overwrites_previous_bundle_or_symbolic_output_parent(self):
        self.collect()
        with self.assertRaises(FileExistsError):
            self.collect()
        (self.root / "build/reports/quality/link").symlink_to(ROOT, target_is_directory=True)
        with self.assertRaises(ValueError):
            collector.collect(self.root, Path("build/reports/quality/link/forbidden"))


if __name__ == "__main__":
    unittest.main()
