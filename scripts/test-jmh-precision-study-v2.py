#!/usr/bin/env python3
"""Versioned successor controls; these tests start no JVM or benchmark."""

from __future__ import annotations

import copy
import contextlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

import jmh_precision_study_v1 as predecessor
import jmh_precision_study_v2 as study


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("predecessor_controls",
                                            ROOT / "scripts/test-jmh-precision-study-v1.py")
controls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controls)


def entrypoint(name):
    path = ROOT / "scripts" / name
    if not path.is_file():
        raise AssertionError(f"successor entrypoint is missing: {name}")
    spec = importlib.util.spec_from_file_location("successor_" + path.stem, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SuccessorPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy, self.inventory = study.load_policy(ROOT)
        self.old, self.old_inventory = predecessor.load_policy(ROOT)

    def test_successor_identity_is_distinct_and_bound_to_the_retained_failure(self):
        self.assertEqual("config/quality/jmh-precision-study-policy-v2.json", study.POLICY_PATH)
        self.assertEqual("regelsuche.quality.jmh-precision-study-policy/v2", self.policy.get("schema"))
        self.assertEqual("issue-981-shared-runner-precision-v2", self.policy.get("studyId"))
        self.assertEqual("regelsuche.quality.jmh-jar-identity/v2", self.policy.get("jarIdentitySchema"))
        self.assertEqual({"studyId": self.old["studyId"], "runId": 34729477401, "runAttempt": 1,
                          "policySha256": predecessor.STUDY_POLICY_SHA256,
                          "collectionStatus": "ERROR", "adoptionOutcome": "INCOMPLETE_EVIDENCE_NO_SELECTION"},
                         self.policy.get("predecessorStudy"))

    def test_all_statistical_execution_and_cost_fields_equal_the_original_preregistration(self):
        identity_fields = {"schema", "studyId", "sharedRunnerLaunch", "jarIdentitySchema", "predecessorStudy"}
        self.assertEqual({k: v for k, v in self.old.items() if k not in identity_fields},
                         {k: v for k, v in self.policy.items() if k not in identity_fields})
        self.assertEqual(self.old_inventory, self.inventory)
        for replica in (1, 2, 3):
            self.assertEqual(predecessor.cells(self.old, self.old_inventory, replica),
                             study.cells(self.policy, self.inventory, replica))
        self.assertIsNone(self.policy["selectedProtocol"])

    def test_only_the_new_first_opened_branch_and_job_can_launch_the_successor(self):
        expected = self.old["sharedRunnerLaunch"] | {
            "headBranch": "codex/issue-981-jmh-precision-study-v2", "job": "jmh-precision-study-v2"}
        self.assertEqual(expected, self.policy["sharedRunnerLaunch"])
        self.assertEqual("opened", expected["eventAction"])
        self.assertEqual("1", expected["runAttempt"])

    def test_all_four_policy_inputs_are_required_and_policy_mutation_is_rejected(self):
        inputs = {study.POLICY_PATH, *study.FROZEN_INPUTS}
        self.assertEqual({"config/quality/jmh-precision-study-policy-v2.json",
                          predecessor.POLICY_PATH, *predecessor.FROZEN_INPUTS}, inputs)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in inputs:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / path).read_bytes())
            changed = copy.deepcopy(self.policy)
            changed["analysis"]["maximumMedianWallclockRatioToControl"] = 99
            (root / study.POLICY_PATH).write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, "preregistration"):
                study.load_policy(root)


class SuccessorMetricTests(unittest.TestCase):
    setUp = SuccessorPolicyTests.setUp

    def test_nonobject_gc_metric_is_a_retained_validation_error(self):
        cell = next(c for c in study.cells(self.policy, self.inventory, 1) if c["profiler"] == "gc")
        for malformed in (None, [], 3, "bad"):
            with self.subTest(metric=malformed):
                payload = controls.synthetic_result(cell, self.inventory)
                payload[0]["secondaryMetrics"]["·gc.alloc.rate.norm"] = malformed
                try:
                    study.evaluate_result(payload, cell, self.inventory)
                except Exception as error:
                    self.assertIsInstance(error, ValueError, "malformed GC data must stay in the retained error path")
                    self.assertIn("allocation metric must be an object", str(error))
                else:
                    self.fail("malformed allocation metric was accepted")

    def test_valid_gc_values_and_search_event_units_keep_the_predecessor_decisions(self):
        for cell in study.cells(self.policy, self.inventory, 1):
            payload = controls.synthetic_result(cell, self.inventory)
            self.assertEqual(predecessor.evaluate_result(payload, cell, self.inventory),
                             study.evaluate_result(payload, cell, self.inventory))

    def test_jmh_event_counts_require_hash_units_before_per_search_normalization(self):
        cell = study.cells(self.policy, self.inventory, 1)[0]
        payload = controls.synthetic_result(cell, self.inventory)
        search = next(row for row in payload if self.inventory[row["benchmark"]]["family"] == "END_TO_END_SEARCH")
        self.assertEqual("#", search["secondaryMetrics"]["searches"]["scoreUnit"])
        search["secondaryMetrics"]["searches"]["scoreUnit"] = "events/op"
        with self.assertRaisesRegex(ValueError, "event unit differs"):
            study.evaluate_result(payload, cell, self.inventory)


class SuccessorPipelineTests(unittest.TestCase):
    def setUp(self):
        SuccessorPolicyTests.setUp(self)
        self.study = study
        environment = patch.dict(os.environ, {"GITHUB_ACTIONS": "false", "JAVA_TOOL_OPTIONS": "",
                                              "JDK_JAVA_OPTIONS": "", "_JAVA_OPTIONS": ""})
        environment.start()
        self.addCleanup(environment.stop)

    fixture = controls.RetainedStudyControls.fixture

    def runner(self):
        return entrypoint("run-jmh-precision-study-v2.py")

    def reporter(self):
        return entrypoint("report-jmh-precision-study-v2.py")

    # Re-exercise the existing controls against the successor entrypoints and
    # its four policy inputs, without altering any predecessor module globals.
    report_module = reporter
    test_tampered_raw_log_policy_and_receipts_are_rejected = (
        controls.ReportControls.test_tampered_raw_log_policy_and_receipts_are_rejected)
    test_reported_inconclusive_cannot_be_averaged_into_a_pass = (
        controls.ReportControls.test_reported_inconclusive_cannot_be_averaged_into_a_pass)
    test_adoption_requires_all_preregistered_conditions_at_their_boundaries = (
        controls.ReportControls.test_adoption_requires_all_preregistered_conditions_at_their_boundaries)
    test_hosted_runner_names_need_not_be_unique_but_boots_must_be = (
        controls.ReportControls.test_hosted_runner_names_need_not_be_unique_but_boots_must_be)

    def corpus(self, directory):
        checkout, java, jar = self.fixture(directory)
        outputs = [Path(directory) / f"replicate-{number}" for number in (1, 2, 3)]
        runner = self.runner()
        for number, output in enumerate(outputs, 1):
            manifest = runner.run_study(checkout, number, output, java=java, prebuilt_jar=jar)
            self.assertEqual("COMPLETE", manifest["collectionStatus"])
        return checkout, outputs

    def test_runner_binds_the_new_schema_four_inputs_and_duplicate_jar_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            with zipfile.ZipFile(jar, "a") as archive, warnings.catch_warnings():
                warnings.filterwarnings("ignore", message="Duplicate name:", category=UserWarning)
                archive.writestr("synthetic-control.txt", "second distinct synthetic payload")
            output = Path(directory) / "replicate-1"
            manifest = self.runner().run_study(checkout, 1, output, java=java, prebuilt_jar=jar)
            self.assertEqual("COMPLETE", manifest["collectionStatus"])
            self.assertEqual("regelsuche.quality.jmh-precision-study-replicate/v2", manifest["schema"])
            self.assertEqual(self.policy["studyId"], manifest["studyId"])
            self.assertEqual("regelsuche.quality.jmh-jar-identity/v2", manifest["jar"].get("schema"))
            self.assertEqual(1, manifest["jar"].get("duplicateEntryNameCount"))
            self.assertEqual({study.POLICY_PATH, *study.FROZEN_INPUTS}, set(manifest["inputs"]))
            self.assertEqual(9, len(manifest["cells"]))
            self.assertTrue(all(cell["rawSha256"] for cell in manifest["cells"]))
            with self.assertRaises(FileExistsError):
                self.runner().run_study(checkout, 1, output, java=java, prebuilt_jar=jar)

    def test_malformed_gc_is_retained_in_the_final_manifest_raw_file_and_error_report(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            source = java.read_text()
            self.assertIn("{'score':123,'scoreUnit':'B/op'}", source)
            java.write_text(source.replace("{'score':123,'scoreUnit':'B/op'}", "None"))
            output = Path(directory) / "replicate-1"
            console = io.StringIO()
            try:
                with contextlib.redirect_stdout(console):
                    manifest = self.runner().run_study(checkout, 1, output, java=java, prebuilt_jar=jar)
            except AttributeError as error:
                self.fail(f"malformed GC escaped final error retention: {error}")
            self.assertEqual("ERROR", manifest["collectionStatus"])
            self.assertEqual(1, manifest["executionErrorCount"])
            self.assertEqual(9, len(manifest["cells"]))
            self.assertEqual(manifest, study.load_json(output / "manifest.json"))
            gc = next(cell for cell in manifest["cells"] if cell["protocol"] == "gc-profile")
            self.assertIn("allocation metric must be an object", gc["error"])
            self.assertIn("allocation metric must be an object", console.getvalue())
            self.assertEqual(study.sha256(output / gc["rawPath"]), gc["rawSha256"])
            self.assertIsNone(study.load_json(output / gc["rawPath"])[0]["secondaryMetrics"]["gc.alloc.rate.norm"])
            analysis = Path(directory) / "analysis"
            args = ["report", "--repository-root", str(checkout), "--replicate", str(output),
                    "--replicate", str(Path(directory) / "missing-2"), "--replicate", str(Path(directory) / "missing-3"),
                    "--output", str(analysis)]
            report_console = io.StringIO()
            with patch.object(sys, "argv", args), contextlib.redirect_stdout(report_console):
                status = self.reporter().main()
            report = study.load_json(analysis / "report.json")
            self.assertEqual(2, status)
            self.assertEqual("regelsuche.quality.jmh-precision-study-report/v2", report["schema"])
            self.assertEqual("INCOMPLETE_EVIDENCE_NO_SELECTION", report["adoptionOutcome"])
            self.assertIsNone(report["selectedProtocol"])
            self.assertEqual(1, report["executionErrorCount"])
            self.assertIn("allocation metric must be an object", report["retainedExecutionErrors"][0]["error"])
            logged_report = report_console.getvalue().split("BEGIN_JMH_PRECISION_STUDY_REPORT_V2\n", 1)[1]
            logged_report = logged_report.split("END_JMH_PRECISION_STUDY_REPORT_V2\n", 1)[0]
            self.assertEqual((analysis / "report.json").read_text(), logged_report)

    def test_unauthorized_shared_launch_is_rejected_before_any_process(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, _ = self.fixture(directory)
            revision = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
            event = Path(directory) / "event.json"
            event.write_text(json.dumps(dict(action="synchronize", number=999,
                pull_request=dict(head=dict(ref="codex/issue-981-jmh-precision-study-v2", sha=revision,
                                           repo=dict(full_name="carstenartur/Regelsuche"))))))
            environment = dict(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted",
                               GITHUB_SHA=revision, GITHUB_EVENT_PATH=str(event), GITHUB_EVENT_NAME="pull_request",
                               GITHUB_REPOSITORY="carstenartur/Regelsuche", GITHUB_RUN_ATTEMPT="1",
                               GITHUB_HEAD_REF="codex/issue-981-jmh-precision-study-v2", GITHUB_JOB="jmh-precision-study-v2")
            runner = self.runner()
            with patch.dict(os.environ, environment), patch.object(runner, "execute", side_effect=AssertionError("process started")):
                with self.assertRaisesRegex(ValueError, "launch"):
                    runner.run_study(checkout, 1, Path(directory) / "forbidden", java=java, job_start_epoch=time.time())
            self.assertFalse((Path(directory) / "forbidden").exists())

    def test_replay_is_deterministic_and_preserves_both_complete_null_outcomes(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            reporter = self.reporter()
            report = reporter.analyze(checkout, outputs)
            self.assertEqual(study.canonical(report), study.canonical(reporter.analyze(checkout, list(reversed(outputs)))))
            self.assertEqual("regelsuche.quality.jmh-precision-study-report/v2", report["schema"])
            self.assertEqual(609, report["measurementRowCount"])
            self.assertEqual(7, len(report["protocols"]))
            self.assertEqual("NO_LOW_PRECISION_IMPROVEMENT_ESTABLISHED", report["adoptionOutcome"])
            self.assertIsNone(report["selectedProtocol"])
            for output in outputs:
                manifest = study.load_json(output / "manifest.json")
                planned = study.cells(self.policy, self.inventory, manifest["replicate"])
                for retained, cell in zip(manifest["cells"], planned):
                    raw = output / retained["rawPath"]
                    rows = study.load_json(raw)
                    for row in rows:
                        row["primaryMetric"]["scoreError"] = row["primaryMetric"]["score"] * 2
                    raw.write_text(study.canonical(rows))
                    retained["rawSha256"] = study.sha256(raw)
                    retained["summary"] = study.summarize(study.evaluate_result(rows, cell, self.inventory))
                (output / "manifest.json").write_text(study.canonical(manifest))
            noisy = reporter.analyze(checkout, outputs)
            self.assertEqual("NO_PROTOCOL_QUALIFIES", noisy["adoptionOutcome"])
            self.assertIsNone(noisy["selectedProtocol"])
            self.assertTrue(all(row["summary"]["lowPrecisionCount"] == 87 for row in noisy["protocols"]))

    def test_replay_refuses_historical_schemas_missing_inputs_and_partial_corpora(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            reporter = self.reporter()
            path = outputs[0] / "manifest.json"
            original = study.load_json(path)
            for mutate in (
                    lambda m: m.update(schema="regelsuche.quality.jmh-precision-study-replicate/v1"),
                    lambda m: m.update(studyId=self.old["studyId"]),
                    lambda m: m["jar"].pop("schema", None),
                    lambda m: m["jar"].update(schema="regelsuche.quality.jmh-jar-identity/v1"),
                    lambda m: m["inputs"].pop(predecessor.POLICY_PATH, None),
                    lambda m: m["cells"].pop()):
                changed = copy.deepcopy(original)
                mutate(changed)
                path.write_text(study.canonical(changed))
                with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                    reporter.analyze(checkout, outputs)
                path.write_text(study.canonical(original))
            for selected in (outputs[:2], [outputs[0], outputs[0], outputs[2]]):
                with self.subTest(selected=selected), self.assertRaisesRegex(ValueError, "replicate"):
                    reporter.analyze(checkout, selected)

    def test_equal_jar_content_hashes_cannot_hide_conflicting_entry_inventories(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            reporter = self.reporter()
            path = outputs[0] / "manifest.json"
            original = study.load_json(path)
            for counts in (dict(entryCount=6), dict(entryCount=2, duplicateEntryNameCount=1)):
                changed = copy.deepcopy(original)
                changed["jar"].update(counts)
                path.write_text(study.canonical(changed))
                with self.subTest(counts=counts), self.assertRaisesRegex(ValueError, "jar"):
                    reporter.analyze(checkout, outputs)
            path.write_text(study.canonical(original))

    def test_a_duplicate_name_requires_at_least_two_logical_jar_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            output = Path(directory) / "replicate-1"
            manifest = self.runner().run_study(checkout, 1, output, java=java, prebuilt_jar=jar)
            self.assertEqual(1, manifest["jar"]["entryCount"])
            manifest["jar"]["duplicateEntryNameCount"] = 1
            (output / "manifest.json").write_text(study.canonical(manifest))
            with self.assertRaisesRegex(ValueError, "jar entry inventory"):
                self.reporter().verify_replica(checkout, output, self.policy, self.inventory)


if __name__ == "__main__":
    unittest.main(verbosity=2)
