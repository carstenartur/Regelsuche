#!/usr/bin/env python3
"""Synthetic protocol fixtures test validation only; they are never study receipts."""
import copy
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("repro", ROOT / "scripts/verify-rule-amplification-reproduction.py")
repro = importlib.util.module_from_spec(spec)
spec.loader.exec_module(repro)


def synthetic_files():
    c, h = repro.canonical, repro.digest
    sources = {"schema": "regelsuche.amplification-sources/v1", "sources": [{"expression": "sin(x)^2 + cos(y)^2", "assumptions": []}]}
    labels = {"schema": "regelsuche.amplification-labels/v1", "rows": [{"caseId": "synthetic-negative", "family": "trigonometry",
              "principalId": "sympy.trig.pythagorean", "reference": "", "kind": "NEAR_MISS"}]}
    plan = {"schema": "regelsuche.amplification-plan/v1", "repositoryRevision": "1" * 40,
            "sourceHash": h(c(sources)), "qualificationHash": h(c(labels)),
            "nativeProfiles": [{"profile": profile} for profile in repro.PROFILES],
            "externalOperations": [{"operation": operation} for operation in repro.OPERATIONS]}
    native, external, verification, native_rows, external_rows = [], [], [], [], []
    for profile, configuration in zip(repro.PROFILES, plan["nativeProfiles"]):
        observation = {"configuration": configuration, "configurationHash": h(c(configuration)), "source": sources["sources"][0],
                       "candidates": [], "stages": [{"stage": "DIRECT_ONLY", "status": "COMPLETED"}],
                       "work": {"counters": {"matcherCalls": 1}, "chargedUnits": 1},
                       "exactPreparationWork": {"counters": {}, "chargedUnits": 0, "limit": 100}}
        run = {"profile": profile, "configurationHash": h(c(configuration)), "source": observation["source"], "candidates": [],
               "canonicalJson": c(observation).decode(), "contentHash": h(c(observation))}
        native.append({"sourceIndex": 0, "run": run})
        verification.append({"sourceIndex": 0, "profile": profile, "verified": True, "replayHash": run["contentHash"],
                             "verificationWork": observation["work"], "verificationExactPreparationWork": observation["exactPreparationWork"]})
        native_rows.append({"sourceIndex": 0, "caseId": "synthetic-negative", "profile": profile, "runHash": run["contentHash"],
                            "status": "NEGATIVE_NO_CANDIDATE", "referenceReached": False})
    for operation, configuration in zip(repro.OPERATIONS, plan["externalOperations"]):
        source_input = {"sourceExpression": sources["sources"][0]["expression"], "declaredAssumptions": [], "operation": operation}
        observation = {"configuration": configuration, "configurationHash": h(c(configuration)), "status": "UNSUPPORTED", "output": "",
                       "input": source_input, "inputHash": h(c(source_input))}
        outcome = {"configurationHash": h(c(configuration)), "canonicalJson": c(observation).decode(), "contentHash": h(c(observation)), "status": "UNSUPPORTED", "output": ""}
        external.append({"sourceIndex": 0, "operation": operation, "outcome": outcome})
        external_rows.append({"sourceIndex": 0, "caseId": "synthetic-negative", "operation": operation, "status": "UNSUPPORTED", "outcomeHash": outcome["contentHash"]})
    freeze = {"schema": "regelsuche.amplification-candidate-freeze/v1", "status": "ALL_PLANNED_CANDIDATE_ROWS_FROZEN",
              "qualificationExposure": "HASH_ONLY_NOT_OPENED", "planHash": h(c(plan)), "sourceHash": plan["sourceHash"],
              "qualificationHash": plan["qualificationHash"], "native": native, "external": external}
    report = {"schema": "regelsuche.amplification-qualification/v1", "planHash": h(c(plan)), "candidateFreezeHash": h(c(freeze)),
              "qualificationHash": plan["qualificationHash"], "independentReplay": verification, "falsePositives": 0, "invalidReplays": 0,
              "nativeRows": native_rows, "externalRows": external_rows, "semanticEvidence": "PUBLIC_CONTROL_OBSERVATIONS",
              "statuses": {"NEGATIVE_NO_CANDIDATE": 4}, "comparativeGainClaim": "NOT_AUTHORIZED", "comparativeMatchedWorkGate": "BLOCKED_UNAVAILABLE_INTERNAL_WORK"}
    return dict(zip(repro.FILES, (plan, sources, labels, freeze, report)))


class ReproductionContracts(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)

    def bundle(self, name, kind="host", machine="synthetic-host-a", files=None):
        root = self.base / name
        root.mkdir()
        files = files or synthetic_files()
        for name, value in files.items():
            (root / name).write_bytes(repro.canonical(value))
        image = "sha256:" + "2" * 64
        receipt = {"schema": "regelsuche.amplification-execution-receipt/v1", "kind": kind, "repositoryRevision": "1" * 40,
                   "cleanCheckout": True, "implementationHash": "sha256:" + "3" * 64, "observedMachineHash": machine,
                   "pinnedImageId": image if kind == "container" else "",
                   "imageInspection": {"Id": image, "Config": {"Labels": {"org.opencontainers.image.revision": "1" * 40}}} if kind == "container" else {},
                   "files": {name: repro.digest((root / name).read_bytes()) for name in repro.FILES}}
        (root / "execution-receipt.json").write_bytes(repro.canonical(receipt))
        return root

    def test_distinct_hosts_container_and_every_bound_file_required(self):
        a = self.bundle("a")
        b = self.bundle("b", machine="synthetic-host-b")
        container = self.bundle("container", kind="container", machine="")
        result = repro.verify([a, b, container])
        self.assertEqual("REPRODUCED", result["status"])
        self.assertEqual("NOT_AUTHORIZED", result["comparativeGainClaim"])
        (b / "candidate-freeze.json").unlink()
        with self.assertRaises(ValueError):
            repro.verify([a, b, container])

    def test_repeated_process_on_same_host_does_not_satisfy_independence(self):
        roots = [self.bundle("a"), self.bundle("b"), self.bundle("container", "container")]
        with self.assertRaisesRegex(ValueError, "one observed host"):
            repro.verify(roots)

    def test_rehashed_duplicated_replay_rows_are_not_complete_replay(self):
        files = synthetic_files()
        rows = files["qualification-report.json"]["independentReplay"]
        rows[1] = copy.deepcopy(rows[0])
        with self.assertRaisesRegex(ValueError, "replay"):
            repro.bundle(self.bundle("duplicate", files=files))

    def test_omitted_or_substituted_external_row_is_not_complete(self):
        files = synthetic_files()
        files["candidate-freeze.json"]["external"].pop()
        with self.assertRaises(ValueError):
            repro.bundle(self.bundle("omitted", files=files))

    def test_external_outcome_status_projection_cannot_change_a_retained_failure(self):
        files = synthetic_files()
        freeze = files["candidate-freeze.json"]
        freeze["external"][0]["outcome"]["status"] = "COMPLETED"
        files["qualification-report.json"]["candidateFreezeHash"] = repro.digest(repro.canonical(freeze))
        with self.assertRaisesRegex(ValueError, "external.*projection"):
            repro.bundle(self.bundle("external-projection", files=files))

    def test_independent_exact_work_projection_cannot_claim_an_unexecuted_delta(self):
        files = synthetic_files()
        files["qualification-report.json"]["independentReplay"][0]["verificationExactPreparationWork"] = {
            "counters": {"invented": 1}, "chargedUnits": 1, "limit": 100}
        with self.assertRaisesRegex(ValueError, "replay.*differs"):
            repro.bundle(self.bundle("exact-work-projection", files=files))

    def test_qualification_projection_cannot_change_frozen_source_index(self):
        files = synthetic_files()
        files["qualification-report.json"]["nativeRows"][0]["sourceIndex"] = 1
        with self.assertRaisesRegex(ValueError, "qualification row binding"):
            repro.bundle(self.bundle("source-index-projection", files=files))

    def native_failure_files(self, status):
        files = synthetic_files()
        freeze, report = files["candidate-freeze.json"], files["qualification-report.json"]
        run = freeze["native"][0]["run"]
        observed = repro.json.loads(run["canonicalJson"])
        observed["stages"] = [{"stage": "DIRECT_ONLY", "status": status}]
        run["canonicalJson"] = repro.canonical(observed).decode()
        run["contentHash"] = repro.digest(run["canonicalJson"].encode())
        report["candidateFreezeHash"] = repro.digest(repro.canonical(freeze))
        report["independentReplay"][0]["replayHash"] = run["contentHash"]
        report["nativeRows"][0].update(runHash=run["contentHash"], status=status)
        report["statuses"] = {"NEGATIVE_NO_CANDIDATE": 3, status: 1}
        return files

    def test_rehashed_status_summary_cannot_hide_a_retained_native_failure(self):
        for status in ("BUDGET_INCONCLUSIVE", "TECHNICAL_FAILURE"):
            with self.subTest(status=status):
                files = self.native_failure_files(status)
                files["qualification-report.json"]["statuses"] = {"NEGATIVE_NO_CANDIDATE": 4}
                roots = [self.bundle(status + "-a", files=files), self.bundle(status + "-b", machine="synthetic-host-b", files=files),
                         self.bundle(status + "-container", "container", files=files)]
                with self.assertRaisesRegex(ValueError, "native.*status"):
                    repro.verify(roots)

    def test_rehashed_row_and_summary_cannot_hide_the_frozen_native_failure(self):
        for status in ("BUDGET_INCONCLUSIVE", "TECHNICAL_FAILURE"):
            with self.subTest(status=status):
                files = self.native_failure_files(status)
                files["qualification-report.json"]["nativeRows"][0]["status"] = "NEGATIVE_NO_CANDIDATE"
                files["qualification-report.json"]["statuses"] = {"NEGATIVE_NO_CANDIDATE": 4}
                roots = [self.bundle(status + "-a", files=files), self.bundle(status + "-b", machine="synthetic-host-b", files=files),
                         self.bundle(status + "-container", "container", files=files)]
                with self.assertRaisesRegex(ValueError, "native.*status"):
                    repro.verify(roots)

    def test_identical_bound_native_failures_remain_reproduced_inconclusive(self):
        for status in ("BUDGET_INCONCLUSIVE", "TECHNICAL_FAILURE"):
            with self.subTest(status=status):
                files = self.native_failure_files(status)
                roots = [self.bundle(status + "-a", files=files), self.bundle(status + "-b", machine="synthetic-host-b", files=files),
                         self.bundle(status + "-container", "container", files=files)]
                self.assertEqual("REPRODUCED_INCONCLUSIVE", repro.verify(roots)["status"])

    def test_source_symbol_names_do_not_become_status_evidence(self):
        files = synthetic_files()
        source = files["sources.json"]["sources"][0]
        source["expression"] = "MATCH_BUDGET + BUDGET_INCONCLUSIVE + TECHNICAL_FAILURE"
        plan, freeze, report = files["plan.json"], files["candidate-freeze.json"], files["qualification-report.json"]
        plan["sourceHash"] = repro.digest(repro.canonical(files["sources.json"]))
        freeze["sourceHash"] = plan["sourceHash"]
        freeze["planHash"] = report["planHash"] = repro.digest(repro.canonical(plan))
        for index, row in enumerate(freeze["native"]):
            run = row["run"]
            observed = repro.json.loads(run["canonicalJson"])
            observed["source"] = run["source"] = source
            run["canonicalJson"] = repro.canonical(observed).decode()
            run["contentHash"] = repro.digest(run["canonicalJson"].encode())
            report["independentReplay"][index]["replayHash"] = report["nativeRows"][index]["runHash"] = run["contentHash"]
        for index, row in enumerate(freeze["external"]):
            outcome = row["outcome"]
            observed = repro.json.loads(outcome["canonicalJson"])
            observed["input"]["sourceExpression"] = source["expression"]
            observed["inputHash"] = repro.digest(repro.canonical(observed["input"]))
            outcome["canonicalJson"] = repro.canonical(observed).decode()
            outcome["contentHash"] = repro.digest(outcome["canonicalJson"].encode())
            report["externalRows"][index]["outcomeHash"] = outcome["contentHash"]
        report["candidateFreezeHash"] = repro.digest(repro.canonical(freeze))
        roots = [self.bundle("names-a", files=files), self.bundle("names-b", machine="synthetic-host-b", files=files),
                 self.bundle("names-container", "container", files=files)]
        self.assertEqual("REPRODUCED", repro.verify(roots)["status"])

    def test_transport_import_does_not_dirty_its_checkout_with_bytecode(self):
        scripts = self.base / "transport" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("run-rule-amplification.py", "verify-rule-amplification-reproduction.py"):
            (scripts / name).write_bytes((ROOT / "scripts" / name).read_bytes())
        environment = dict(os.environ)
        environment.pop("PYTHONDONTWRITEBYTECODE", None)
        result = subprocess.run([sys.executable, "-c", "import runpy,sys; runpy.run_path(sys.argv[1], run_name='transport_import_control')",
                                 str(scripts / "run-rule-amplification.py")], env=environment, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertFalse((scripts / "__pycache__").exists(), "transport import dirtied its own clean-checkout boundary")

    def test_symlink_and_noncanonical_inputs_are_rejected(self):
        root = self.bundle("unsafe")
        (root / "sources.json").rename(root / "elsewhere.json")
        (root / "sources.json").symlink_to(root / "elsewhere.json")
        with self.assertRaises(ValueError):
            repro.bundle(root)


if __name__ == "__main__":
    unittest.main()
