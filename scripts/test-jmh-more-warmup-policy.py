#!/usr/bin/env python3
"""Check the #981 execution migration with synthetic data, never timing code."""

from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
QUALITY = ROOT / "config/quality"
THRESHOLD = QUALITY / "jmh-regression-policy-more-warmup-v1.json"
DECISION = QUALITY / "jmh-regression-decision-policy-more-warmup-v1.json"
BASELINE = QUALITY / "jmh-baseline-more-warmup-v1.json"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MoreWarmupPolicyTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(THRESHOLD.is_file(), "six-warmup production policy is not wired yet")
        self.threshold = read(THRESHOLD)
        self.old = read(QUALITY / "jmh-regression-policy-v2.json")

    def rows(self, warmups=6):
        return [{
            "jmhVersion": "1.36", "benchmark": row["benchmark"],
            "mode": "avgt", "threads": 1, "forks": 1, "jdkVersion": "25.0.4",
            "warmupIterations": warmups, "warmupTime": "1 s",
            "warmupBatchSize": 1, "measurementBatchSize": 1,
            "measurementIterations": 3, "measurementTime": "1 s",
            "primaryMetric": {"score": row["baselineScore"] / 2,
                              "scoreError": row["baselineScore"] / 100,
                              "scoreUnit": row["unit"]},
            "secondaryMetrics": {},
        } for row in self.old["benchmarks"]]

    def regression(self, rows, historical=False):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            result = temp / "result.json"
            result.write_text(json.dumps(rows), encoding="utf-8")
            command = [sys.executable, "-B", str(ROOT / "scripts/verify-jmh-regression-v3.py"),
                       "--repository-root", str(ROOT), "--result", str(result),
                       "--threshold-policy", str(QUALITY / "jmh-regression-policy-v2.json" if historical else THRESHOLD),
                       "--decision-policy", str(QUALITY / "jmh-regression-decision-policy-v3.json" if historical else DECISION),
                       "--json-output", str(temp / "report.json"),
                       "--markdown-output", str(temp / "report.md")]
            completed = subprocess.run(command, capture_output=True, text=True, timeout=20)
            self.assertTrue((temp / "report.json").is_file(), completed.stderr)
            return completed.returncode, read(temp / "report.json")

    def test_preserves_every_historical_ceiling_and_reference(self):
        self.assertEqual(self.old["benchmarks"], self.threshold["benchmarks"])
        for key in ("baselineRevision", "baselineArtifactDigest"):
            self.assertEqual(self.old[key], self.threshold[key])
        self.assertEqual(self.old["execution"], self.threshold["baselineExecution"])
        self.assertEqual(2, self.old["execution"]["warmupIterations"])
        expected = {**self.old["execution"], "warmupIterations": 6,
                    "warmupTime": "1s", "measurementTime": "1s"}
        self.assertEqual(expected, self.threshold["execution"])
        self.assertEqual("regelsuche.jmh-latency-execution/more-warmup-v1",
                         self.threshold["executionRevision"])

    def test_decision_semantics_unchanged_and_current_policy_content_bound(self):
        old = read(QUALITY / "jmh-regression-decision-policy-v3.json")
        current = read(DECISION)
        for key in ("schema", "thresholdPolicySchema", "decisionStatistic", "failureCondition",
                    "inconclusiveCondition", "boundaryPolicy", "lowPrecisionDiagnostic"):
            self.assertEqual(old[key], current[key], key)
        self.assertEqual(str(THRESHOLD.relative_to(ROOT)), current["thresholdPolicyPath"])
        data = THRESHOLD.read_bytes()
        self.assertEqual(hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest(),
                         current["thresholdPolicyGitBlobSha1"])

    def test_publication_policy_preserves_its_own_ceiling(self):
        old = read(QUALITY / "jmh-baseline.json")
        current = read(BASELINE)
        self.assertEqual(old["benchmarks"], current["benchmarks"])
        self.assertEqual(old["baselineRevision"], current["baselineRevision"])
        self.assertEqual(old["measurementPolicy"], current["baselineMeasurementPolicy"])
        self.assertEqual({**old["measurementPolicy"], "warmupIterations": 6}, current["measurementPolicy"])

    def test_six_warmup_inventory_passes(self):
        code, report = self.regression(self.rows())
        self.assertEqual(0, code)
        self.assertEqual("PASSED", report["status"])
        self.assertEqual(29, report["benchmarkCount"])
        self.assertEqual(str(THRESHOLD), report["thresholdPolicy"])

    def test_old_warmup_count_is_rejected_by_current_contract(self):
        code, report = self.regression(self.rows(2))
        self.assertNotEqual(0, code)
        self.assertEqual("FAILED", report["status"])
        self.assertTrue(any("warmup iterations" in item for item in report["violations"]))

    def test_historical_contract_still_accepts_two_warmups(self):
        code, report = self.regression(self.rows(2), historical=True)
        self.assertEqual(0, code)
        self.assertEqual("PASSED", report["status"])

    def test_precise_regression_remains_failure(self):
        rows = self.rows()
        maximum = self.old["benchmarks"][0]["maximumAllowedScore"]
        rows[0]["primaryMetric"].update(score=maximum * 1.2, scoreError=maximum * 0.01)
        code, report = self.regression(rows)
        self.assertNotEqual(0, code)
        self.assertEqual("FAILED", report["status"])

    def test_overlap_still_fails_closed_without_retry(self):
        rows = self.rows()
        maximum = self.old["benchmarks"][0]["maximumAllowedScore"]
        rows[0]["primaryMetric"].update(score=maximum * 1.1, scoreError=maximum * 0.2)
        code, report = self.regression(rows)
        self.assertNotEqual(0, code)
        self.assertEqual("INCONCLUSIVE", report["status"])
        self.assertEqual(1, report["inconclusiveCount"])

    def test_wrong_unit_is_rejected(self):
        rows = self.rows()
        rows[0]["primaryMetric"]["scoreUnit"] = "ns/op"
        code, report = self.regression(rows)
        self.assertNotEqual(0, code)
        self.assertEqual("FAILED", report["status"])

    def test_publisher_checks_current_duration_and_iteration_contract(self):
        verifier = load("jmh_publication_policy_test", ROOT / "scripts/verify-jmh-benchmark.py")
        baseline = verifier.load_baseline(BASELINE)
        rows = self.rows()
        self.assertEqual(29, len(verifier.validate_results(rows, baseline)))
        for field, value in (("warmupIterations", 2), ("warmupTime", "2 s"),
                             ("measurementTime", "2 s")):
            changed = copy.deepcopy(rows)
            changed[0][field] = value
            with self.subTest(field=field), self.assertRaises(SystemExit):
                verifier.validate_results(changed, baseline)

    def test_autocrlf_checkout_preserves_content_bound_policy_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout = Path(directory)
            def git(*args):
                return subprocess.run(["git", "-C", str(checkout), *args],
                                      check=True, capture_output=True, timeout=20)
            git("init", "-q")
            git("config", "core.autocrlf", "true")
            (checkout / ".gitattributes").write_bytes((ROOT / ".gitattributes").read_bytes())
            paths = [THRESHOLD, DECISION, BASELINE]
            for path in paths:
                target = checkout / path.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(path.read_bytes())
            git("add", ".")
            for path in paths:
                (checkout / path.relative_to(ROOT)).unlink()
            git("checkout-index", "-a", "-f")
            for path in paths:
                with self.subTest(policy=path.name):
                    self.assertEqual(path.read_bytes(), (checkout / path.relative_to(ROOT)).read_bytes())

    def test_duration_contract_matches_publication_and_execution(self):
        execution = self.threshold["execution"]
        publication = read(BASELINE)["measurementPolicy"]
        self.assertEqual(publication["warmupTime"], execution.get("warmupTime"))
        self.assertEqual(publication["measurementTime"], execution.get("measurementTime"))
        app = (ROOT / "app/build.gradle").read_text()
        self.assertIn("timeOnIteration = latencyExecution.measurementTime", app)
        self.assertIn("warmup = latencyExecution.warmupTime", app)
        quality = (ROOT / "gradle/quality-gates.gradle").read_text()
        start = quality.index("def verifyJmhRegression =")
        block = quality[start:quality.index("def ", start + 4)]
        self.assertIn("'verifyJmhBenchmark'", block)

    def test_gradle_uses_one_execution_source_and_current_verifiers(self):
        app = (ROOT / "app/build.gradle").read_text()
        self.assertIn(str(THRESHOLD.relative_to(ROOT)), app)
        self.assertIn("warmupIterations = latencyExecution.warmupIterations", app)
        quality = (ROOT / "gradle/quality-gates.gradle").read_text()
        start = quality.index("def verifyJmhRegression =")
        block = quality[start:quality.index("def ", start + 4)]
        self.assertIn(str(THRESHOLD.relative_to(ROOT)), block)
        self.assertIn(str(DECISION.relative_to(ROOT)), block)
        self.assertIn("testJmhMoreWarmupPolicy", block)
        publication = (ROOT / "gradle/ci-verification.gradle").read_text()
        start = publication.index("def verifyJmhBenchmark =")
        block = publication[start:publication.index("def jmhAllocationResult", start)]
        self.assertIn("'--baseline', 'config/quality/jmh-baseline-more-warmup-v1.json'", block)


if __name__ == "__main__":
    unittest.main()
