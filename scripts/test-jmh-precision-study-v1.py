#!/usr/bin/env python3
"""Finite, synthetic controls; these tests never start a JVM or a benchmark."""

from __future__ import annotations

import importlib.util
import copy
import json
import re
import os
import signal
import sys
import tempfile
import time
import subprocess
import zipfile
from unittest.mock import patch
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "scripts/jmh_precision_study_v1.py"


class PreregistrationTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MODULE.is_file(), "the checkout-owned finite study harness is missing")
        spec = importlib.util.spec_from_file_location("study", MODULE)
        self.study = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.study)
        self.policy, self.inventory = self.study.load_policy(ROOT)

    def test_complete_finite_matrix_and_declared_cost(self):
        self.assertEqual(29, len(self.inventory))
        self.assertEqual(7, len(self.policy["protocols"]))
        for replicate in (1, 2, 3):
            cells = self.study.cells(self.policy, self.inventory, replicate)
            self.assertEqual(9, len(cells))
            self.assertEqual(1809, sum(cell["nominalSeconds"] for cell in cells))
            for protocol in self.policy["protocols"]:
                names = [name for cell in cells if cell["protocol"] == protocol["id"]
                         for name in cell["benchmarks"]]
                self.assertCountEqual(self.inventory, names)
        with self.assertRaisesRegex(ValueError, "replicate"):
            self.study.cells(self.policy, self.inventory, 4)

    def test_anchored_command_retains_declared_units_and_raw_data(self):
        for cell in self.study.cells(self.policy, self.inventory, 1):
            command = self.study.jmh_command("java", "benchmarks.jar", cell, "raw.json")
            self.assertIn("-Djmh.json.rawData=true", command)
            self.assertNotIn("-tu", command)
            pattern = command[command.index("benchmarks.jar") + 1]
            for name in cell["benchmarks"]:
                self.assertRegex(name, pattern)
                self.assertIsNone(re.search(pattern, name + "Unregistered"))
            self.assertEqual("1", command[command.index("-t") + 1])
            self.assertEqual("true", command[command.index("-foe") + 1])
            self.assertEqual(cell["profiler"] == "gc", "-prof" in command)

    def test_reference_execution_and_frozen_thresholds_are_unchanged(self):
        baseline = next(row for row in self.policy["protocols"] if row["id"] == "baseline")
        self.assertEqual({"warmupIterations": 2, "measurementIterations": 3,
                          "forks": 1, "warmupSeconds": 1, "measurementSeconds": 1},
                         baseline["execution"])
        frozen = json.loads((ROOT / "config/quality/jmh-regression-policy-v2.json").read_text())
        self.assertEqual({row["benchmark"]: row for row in frozen["benchmarks"]}, self.inventory)
        self.assertIsNone(self.policy["selectedProtocol"])

    def test_preregistered_adoption_order_and_matrix_cannot_be_relaxed(self):
        mutations = (
            lambda policy: policy["analysis"].update(maximumLowPrecisionRatioToControl=1.0),
            lambda policy: policy["analysis"].update(maximumMedianWallclockRatioToControl=99),
            lambda policy: policy["analysis"].update(requireAllCandidateRatchetsPassed=False),
            lambda policy: policy["analysis"].update(requireLowerMedianRelativeError=False),
            lambda policy: policy["analysis"].update(requireLowerP90RelativeError=False),
            lambda policy: policy["replicates"][0]["order"].reverse(),
            lambda policy: policy["protocols"][0]["execution"].update(
                warmupIterations=3, measurementIterations=2),
        )
        for mutate in mutations:
            with self.subTest(mutation=mutate), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for path in [self.study.POLICY_PATH, *self.study.FROZEN_INPUTS]:
                    target = root / path
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes((ROOT / path).read_bytes())
                policy = copy.deepcopy(self.policy)
                mutate(policy)
                (root / self.study.POLICY_PATH).write_text(json.dumps(policy))
                with self.assertRaisesRegex(ValueError, "preregistration"):
                    self.study.load_policy(root)


def synthetic_result(cell, inventory):
    rows = []
    for name in cell["benchmarks"]:
        execution = cell["execution"]
        score = inventory[name]["baselineScore"]
        secondary = {}
        if inventory[name]["family"] == "END_TO_END_SEARCH":
            for counter, value in dict(searches=10, exploredStates=1280, expandedStates=1280,
                                       generatedTransformations=3380, enqueuedStates=3220,
                                       reachedTargets=0).items():
                secondary[counter] = dict(score=value, scoreUnit="#")
        if cell["profiler"] == "gc":
            secondary["·gc.alloc.rate.norm"] = dict(score=123.0, scoreUnit="B/op")
        rows.append(dict(benchmark=name, jmhVersion="1.36", jdkVersion="25.0.3",
                         vmName="OpenJDK 64-Bit Server VM", vmVersion="25.0.3+9", jvmArgs=[],
                         mode="avgt", threads=1, forks=execution["forks"],
                         warmupIterations=execution["warmupIterations"],
                         measurementIterations=execution["measurementIterations"],
                         warmupTime=f"{execution['warmupSeconds']} s",
                         measurementTime=f"{execution['measurementSeconds']} s",
                         warmupBatchSize=1, measurementBatchSize=1,
                         primaryMetric=dict(score=score, scoreError=score * .1,
                                            scoreUnit=inventory[name]["unit"],
                                            rawData=[[score] * execution["measurementIterations"]
                                                     for _ in range(execution["forks"])]),
                         secondaryMetrics=secondary))
    return rows


class MeasurementTests(unittest.TestCase):
    setUp = PreregistrationTests.setUp

    def evaluate(self, payload=None, cell=None):
        self.assertTrue(hasattr(self.study, "evaluate_result"), "measurement verification is missing")
        cell = cell or self.study.cells(self.policy, self.inventory, 1)[0]
        return self.study.evaluate_result(payload if payload is not None else
                                          synthetic_result(cell, self.inventory), cell, self.inventory)

    def test_all_declared_protocols_preserve_complete_unit_aware_rows(self):
        for cell in self.study.cells(self.policy, self.inventory, 1):
            with self.subTest(cell=cell["id"]):
                rows = self.evaluate(cell=cell)
                self.assertCountEqual(cell["benchmarks"], [row["benchmark"] for row in rows])
                self.assertTrue(all(row["status"] == "PASSED" for row in rows))
                self.assertTrue(all(row["unit"] == self.inventory[row["benchmark"]]["unit"]
                                    for row in rows))

    def test_decision_boundaries_and_low_precision_remain_visible(self):
        cell = self.study.cells(self.policy, self.inventory, 1)[0]
        name = cell["benchmarks"][0]
        maximum = self.inventory[name]["maximumAllowedScore"]
        for score, error, outcome, precision in (
                (maximum, 0, "PASSED", "MEASURED"),
                (maximum * .5, maximum, "PASSED", "LOW_PRECISION"),
                (maximum * 1.1, maximum * .2, "INCONCLUSIVE", "MEASURED"),
                (maximum * 1.2, maximum * .1, "FAILED", "MEASURED")):
            with self.subTest(outcome=outcome, precision=precision):
                payload = synthetic_result(cell, self.inventory)
                primary = payload[0]["primaryMetric"]
                primary.update(score=score, scoreError=error, rawData=[[score] * 3])
                row = self.evaluate(payload)[0]
                self.assertEqual(outcome, row["status"])
                self.assertEqual(precision, row["precisionStatus"])
                self.assertEqual(score, row["score"])
                self.assertEqual(error, row["scoreError"])

    def test_malformed_inventory_and_execution_fail_closed(self):
        cell = self.study.cells(self.policy, self.inventory, 1)[0]
        mutations = {
            "missing benchmark": lambda rows: rows.pop(),
            "duplicate benchmark": lambda rows: rows.append(copy.deepcopy(rows[0])),
            "extra benchmark": lambda rows: rows.append(rows[0] | {"benchmark": "example.extra"}),
            "wrong unit": lambda rows: rows[0]["primaryMetric"].update(scoreUnit="ns/op"),
            "wrong iterations": lambda rows: rows[0].update(measurementIterations=8),
            "wrong warm-up": lambda rows: rows[0].update(warmupIterations=6),
            "wrong forks": lambda rows: rows[0].update(forks=2),
            "wrong duration": lambda rows: rows[0].update(measurementTime="2 s"),
            "wrong batch": lambda rows: rows[0].update(measurementBatchSize=2),
            "wrong JDK": lambda rows: rows[0].update(jdkVersion="21.0.11"),
            "wrong JMH": lambda rows: rows[0].update(jmhVersion="1.37"),
            "wrong threads": lambda rows: rows[0].update(threads=2),
            "missing VM name": lambda rows: rows[0].pop("vmName"),
            "empty VM version": lambda rows: rows[0].update(vmVersion=""),
            "wrong mode": lambda rows: rows[0].update(mode="thrpt"),
            "parameters": lambda rows: rows[0].update(params={"differentWork": "true"}),
            "missing raw": lambda rows: rows[0]["primaryMetric"].pop("rawData"),
            "short raw": lambda rows: rows[0]["primaryMetric"].update(rawData=[[1.0, 2.0]]),
            "nonfinite raw": lambda rows: rows[0]["primaryMetric"].update(rawData=[[1.0, float("nan"), 1.0]]),
            "negative error": lambda rows: rows[0]["primaryMetric"].update(scoreError=-1),
            "nonfinite error": lambda rows: rows[0]["primaryMetric"].update(scoreError="NaN"),
            "raw mean drift": lambda rows: rows[0]["primaryMetric"].update(rawData=[[999.0] * 3]),
        }
        for label, mutate in mutations.items():
            with self.subTest(label=label):
                payload = synthetic_result(cell, self.inventory)
                mutate(payload)
                with self.assertRaises(ValueError):
                    self.evaluate(payload)

    def test_profile_and_semantic_counter_evidence_is_required(self):
        for cell in self.study.cells(self.policy, self.inventory, 1):
            if cell["profiler"] != "gc":
                continue
            payload = synthetic_result(cell, self.inventory)
            del payload[0]["secondaryMetrics"]["·gc.alloc.rate.norm"]
            with self.assertRaisesRegex(ValueError, "allocation"):
                self.evaluate(payload, cell)
            payload = synthetic_result(cell, self.inventory)
            search = next(row for row in payload if "PreparedAstSearchBenchmarks" in row["benchmark"])
            del search["secondaryMetrics"]["searches"]
            with self.assertRaisesRegex(ValueError, "searches"):
                self.evaluate(payload, cell)
            payload = synthetic_result(cell, self.inventory)
            search = next(row for row in payload if "PreparedAstSearchBenchmarks" in row["benchmark"])
            search["secondaryMetrics"]["searches"]["scoreUnit"] = "ops/s"
            with self.assertRaisesRegex(ValueError, "unit"):
                self.evaluate(payload, cell)


@unittest.skipUnless(sys.platform == "linux", "the declared process authority is Linux")
class ProcessControls(unittest.TestCase):
    setUp = PreregistrationTests.setUp

    def process_module(self):
        path = ROOT / "scripts/jmh_precision_study_process_v1.py"
        self.assertTrue(path.is_file(), "bounded process evidence runner is missing")
        spec = importlib.util.spec_from_file_location("study_process", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_real_nonzero_process_preserves_stdout_stderr_and_exit(self):
        process = self.process_module()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "failed.log"
            receipt = process.execute([sys.executable, "-c",
                                       "import sys; print('raw stdout'); print('raw stderr', file=sys.stderr); sys.exit(7)"],
                                      ROOT, log, 5)
            self.assertEqual("PROCESS_ERROR", receipt["status"])
            self.assertEqual(7, receipt["exitCode"])
            self.assertIn("raw stdout", log.read_text())
            self.assertIn("raw stderr", log.read_text())
            self.assertGreater(receipt["elapsedSeconds"], 0)
            self.assertEqual(self.study.sha256(log), receipt["logSha256"])

    def test_real_timeout_terminates_process_group_and_retains_diagnostic(self):
        process = self.process_module()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "timeout.log"
            receipt = process.execute([sys.executable, "-c",
                                       "import time; time.sleep(10)"],
                                      ROOT, log, .1)
            self.assertEqual("TIMEOUT", receipt["status"])
            self.assertLess(receipt["elapsedSeconds"], 3)
            # The whole-command budget may expire during interpreter startup.
            # Completed stdout/stderr retention is checked by the nonzero case.
            self.assertIn("TIMEOUT", log.read_text())

    def test_reusing_a_process_log_is_rejected(self):
        process = self.process_module()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "existing.log"
            log.write_text("historical evidence\n")
            with self.assertRaises(FileExistsError):
                process.execute([sys.executable, "-c", "print('replacement')"], ROOT, log, 5)
            self.assertEqual("historical evidence\n", log.read_text())

    def test_parent_exit_cannot_leave_a_child_mutating_retained_logs(self):
        process = self.process_module()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "child.log"
            child = "import time; print('child started',flush=True); time.sleep(.4); print('late child output',flush=True)"
            parent = ("import subprocess,sys,time; subprocess.Popen([sys.executable,'-c'," +
                      repr(child) + "]); time.sleep(.1); sys.exit(0)")
            receipt = process.execute([sys.executable, "-c", parent], ROOT, log, 5)
            self.assertEqual("COMPLETED", receipt["status"])
            retained = log.read_bytes()
            time.sleep(.5)
            self.assertEqual(retained, log.read_bytes())
            self.assertIn("late child output", log.read_text())

    def test_unrelated_child_of_the_caller_is_never_collected(self):
        process = self.process_module()
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(10)"])
        try:
            with tempfile.TemporaryDirectory() as directory:
                receipt = process.execute([sys.executable, "-c", "print('owned process')"],
                                          ROOT, Path(directory) / "owned.log", 5)
                self.assertEqual("COMPLETED", receipt["status"])
                self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=3)

    def test_successful_parent_cannot_hide_an_adopted_child_failure(self):
        process = self.process_module()
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "adopted-failure.log"
            child = "import sys,time; time.sleep(.3); print('detached failure',flush=True); sys.exit(7)"
            parent = ("import subprocess,sys; subprocess.Popen([sys.executable,'-c'," + repr(child) +
                      "],start_new_session=True); sys.exit(0)")
            receipt = process.execute([sys.executable, "-c", parent], ROOT, log, 5)
            self.assertEqual("CHILD_PROCESS_ERROR", receipt["status"])
            self.assertEqual(0, receipt["exitCode"])
            self.assertEqual([7], receipt["adoptedChildFailureExitCodes"])
            self.assertEqual(1, receipt["adoptedChildFailureCount"])
            self.assertEqual(0, receipt["remainingDescendants"])
            self.assertIn("detached failure", log.read_text())

    def test_timeout_reaps_a_detached_term_ignoring_descendant(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            marker, pidfile, log = base / "writes", base / "child.pid", base / "detached.log"
            ready, receipt_file = base / "ready", base / "receipt.json"
            # Deliberately start slower than the cleanup window. The child must
            # install SIGTERM-ignore and acknowledge a flushed write first.
            child = ("import os,signal,time; from pathlib import Path; time.sleep(.4); "
                     "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                     f"Path({str(pidfile)!r}).write_text(str(os.getpid())); "
                     f"stream=Path({str(marker)!r}).open('a'); "
                     "stream.write('x'); stream.flush(); "
                     f"Path({str(ready)!r}).write_text('ready'); "
                     "exec(\"while True:\\n stream.write('x'); stream.flush(); time.sleep(.03)\")")
            parent = ("import subprocess,sys,time; subprocess.Popen([sys.executable,'-c'," + repr(child) +
                      "],start_new_session=True); time.sleep(10)")
            # A private test driver owns the real supervisor's process tree.
            # Only this fixture supplies a readiness-relative deadline; the
            # production execute() and supervisor remain byte-for-byte unchanged.
            driver = f'''
import json, sys, time
from pathlib import Path
sys.path.insert(0, {str(ROOT / 'scripts')!r})
from jmh_precision_study_supervisor_v1 import supervise
class ReadyRequest(dict):
    def __getitem__(self, key):
        if key == "deadline" and key not in self:
            startup_deadline = time.monotonic() + 5
            while not Path({str(ready)!r}).is_file():
                if time.monotonic() >= startup_deadline:
                    raise RuntimeError("child did not acknowledge its first flushed write")
                time.sleep(.01)
            self[key] = time.monotonic() + .2
        return super().__getitem__(key)
receipt = supervise(ReadyRequest(command={[sys.executable, '-c', parent]!r}, root={str(ROOT)!r}))
Path({str(receipt_file)!r}).write_text(json.dumps(receipt))
'''
            try:
                with log.open("xb") as stream:
                    subprocess.run([sys.executable, "-B", "-c", driver], cwd=ROOT, stdout=stream,
                                   stderr=subprocess.STDOUT, check=True, timeout=8)
                receipt = json.loads(receipt_file.read_text())
                self.assertTrue(ready.is_file(), "child never acknowledged its first flushed write")
                retained = marker.read_bytes()
                self.assertTrue(retained, "child must have written before the timeout window")
                time.sleep(.2)
                self.assertEqual(retained, marker.read_bytes(), "detached child survived the timeout receipt")
                self.assertEqual("TIMEOUT", receipt["status"])
                self.assertEqual(0, receipt["remainingDescendants"])
            finally:
                if pidfile.exists():
                    # This test child reports its PID in this same namespace; no /proc PID is signalled.
                    try:
                        fd = os.pidfd_open(int(pidfile.read_text()))
                        signal.pidfd_send_signal(fd, signal.SIGKILL)
                        os.close(fd)
                    except ProcessLookupError:
                        pass

    def test_namespace_translation_uses_the_callers_depth_not_the_innermost_pid(self):
        path = ROOT / "scripts/jmh_precision_study_supervisor_v1.py"
        spec = importlib.util.spec_from_file_location("study_supervisor", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(23, module.signalable_pid([717864, 23], 2))
        self.assertEqual(23, module.signalable_pid([717864, 23, 1], 2))
        with self.assertRaisesRegex(ValueError, "translated"):
            module.signalable_pid([717864], 2)

    def test_hosted_launch_binds_original_event_branch_repository_and_first_attempt(self):
        process = self.process_module()
        self.assertTrue(hasattr(process, "shared_launch_identity"), "shared launch authorization is missing")
        runner = dict(GITHUB_EVENT_NAME="pull_request", GITHUB_RUN_ATTEMPT="1",
                      GITHUB_REPOSITORY="carstenartur/Regelsuche", GITHUB_JOB="jmh-precision-study",
                      GITHUB_HEAD_REF="codex/issue-981-jmh-precision-study")
        event = dict(action="opened", number=999, pull_request=dict(head=dict(
            ref=runner["GITHUB_HEAD_REF"], repo=dict(full_name=runner["GITHUB_REPOSITORY"]), sha="a" * 40)))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "event.json"
            with patch.dict(os.environ, {"GITHUB_EVENT_PATH": str(path)}):
                path.write_text(json.dumps(event))
                launch = process.shared_launch_identity(runner, self.policy)
                self.assertEqual(999, launch["pullRequestNumber"])
                self.assertEqual("a" * 40, launch["headSha"])
                for mutate in (
                        lambda r, e: r.update(GITHUB_EVENT_NAME="workflow_dispatch"),
                        lambda r, e: r.update(GITHUB_RUN_ATTEMPT="2"),
                        lambda r, e: r.update(GITHUB_REPOSITORY="other/Regelsuche"),
                        lambda r, e: r.update(GITHUB_HEAD_REF="another-branch"),
                        lambda r, e: r.update(GITHUB_JOB="jmh-verification"),
                        lambda r, e: e.update(action="synchronize"),
                        lambda r, e: e.update(action="reopened"),
                        lambda r, e: e.update(number=True),
                        lambda r, e: e["pull_request"]["head"]["repo"].update(full_name="other/Regelsuche"),
                        lambda r, e: e["pull_request"]["head"].update(ref="another-branch"),
                        lambda r, e: e["pull_request"]["head"].update(sha="not-a-commit"),
                        lambda r, e: e.clear()):
                    changed_runner, changed_event = copy.deepcopy(runner), copy.deepcopy(event)
                    mutate(changed_runner, changed_event)
                    path.write_text(json.dumps(changed_event))
                    with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                        process.shared_launch_identity(changed_runner, self.policy)
                path.write_text('{"action":"synchronize","action":"opened"}')
                with self.assertRaisesRegex(ValueError, "duplicate"):
                    process.shared_launch_identity(runner, self.policy)


@unittest.skipUnless(sys.platform == "linux", "the declared process authority is Linux")
class RetainedStudyControls(unittest.TestCase):
    def setUp(self):
        PreregistrationTests.setUp(self)
        environment = patch.dict(os.environ, {"GITHUB_ACTIONS": "false"})
        environment.start()
        self.addCleanup(environment.stop)

    def runner_module(self):
        path = ROOT / "scripts/run-jmh-precision-study-v1.py"
        self.assertTrue(path.is_file(), "finite study runner is missing")
        spec = importlib.util.spec_from_file_location("study_runner", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def fixture(self, directory):
        base = Path(directory)
        checkout = base / "checkout"
        for path in [self.study.POLICY_PATH, *self.study.FROZEN_INPUTS]:
            target = checkout / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / path).read_bytes())
        (checkout / ".gitignore").write_text("app/build/\n")
        for args in (("init", "-q"), ("add", "."),
                     ("-c", "user.name=Study Control", "-c", "user.email=control@example.invalid",
                      "commit", "-qm", "synthetic control checkout")):
            subprocess.run(["git", "-C", str(checkout), *args], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        jar = base / "synthetic.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("synthetic-control.txt", "not a benchmark jar")
        template = base / "template.json"
        template.write_text(json.dumps(synthetic_result(self.study.cells(self.policy, self.inventory, 1)[0],
                                                        self.inventory)))
        executable = base / "synthetic-java"
        executable.write_text("#!" + sys.executable + "\n" +
                              "import json, re, sys\nfrom pathlib import Path\n" +
                              "if '--version' in sys.argv:\n print('synthetic Java 25 control'); sys.exit(0)\n" +
                              f"rows=json.loads(Path({str(template)!r}).read_text())\n" +
                              "args=sys.argv\ndef value(flag): return args[args.index(flag)+1]\n" +
                              "rows=[r for r in rows if re.search(args[args.index('-jar')+2],r['benchmark'])]\n" +
                              "for row in rows:\n" +
                              " row['jvmArgs']=value('-jvmArgs').split()\n" +
                              " row.update(forks=int(value('-f')), warmupIterations=int(value('-wi')), measurementIterations=int(value('-i')), warmupTime=value('-w').replace('s',' s'), measurementTime=value('-r').replace('s',' s'))\n" +
                              " row['primaryMetric']['rawData']=[[row['primaryMetric']['score']]*row['measurementIterations'] for _ in range(row['forks'])]\n" +
                              " if '-prof' in args: row['secondaryMetrics']['gc.alloc.rate.norm']={'score':123,'scoreUnit':'B/op'}\n" +
                              "Path(value('-rff')).write_text(json.dumps(rows)+'\\n')\n" +
                              "print('SYNTHETIC PROCESS CONTROL; NOT PERFORMANCE EVIDENCE')\n")
        executable.chmod(0o755)
        return checkout, executable, jar

    def test_real_process_pipeline_retains_every_cell_and_rejects_output_reuse(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            output = Path(directory) / "replicate-1"
            manifest = runner.run_study(checkout, 1, output, java=java, prebuilt_jar=jar)
            self.assertEqual("COMPLETE", manifest["collectionStatus"])
            self.assertEqual(9, len(manifest["cells"]))
            self.assertEqual("LOCAL_CONTROL", manifest["runner"]["evidenceKind"])
            for cell in manifest["cells"]:
                self.assertEqual(self.study.sha256(output / cell["rawPath"]), cell["rawSha256"])
            with self.assertRaises(FileExistsError):
                runner.run_study(checkout, 1, output, java=java, prebuilt_jar=jar)

    def test_missing_raw_result_is_retained_as_error_without_retry(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            java.write_text("#!" + sys.executable + "\nprint('zero exit without result')\n")
            output = Path(directory) / "replicate-1"
            manifest = runner.run_study(checkout, 1, output, java=java, prebuilt_jar=jar)
            self.assertEqual("ERROR", manifest["collectionStatus"])
            self.assertEqual(9, len(manifest["cells"]))
            self.assertTrue(all(cell["status"] == "ERROR" for cell in manifest["cells"]))
            self.assertTrue(all("raw" in cell["error"].lower() for cell in manifest["cells"]))

    def test_global_budget_also_bounds_a_hanging_java_probe(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            java.write_text("#!" + sys.executable + "\nimport time; time.sleep(2)\n")
            started = time.monotonic()
            manifest = runner.run_study(checkout, 1, Path(directory) / "replicate-1", java=java,
                                         prebuilt_jar=jar, job_start_epoch=time.time() - 3599.85)
            self.assertLess(time.monotonic() - started, 1)
            self.assertEqual("ERROR", manifest["collectionStatus"])
            self.assertEqual("TIMEOUT", manifest["javaVersion"]["status"])
            self.assertEqual(9, manifest["executionErrorCount"])
            self.assertTrue(all("process" not in cell for cell in manifest["cells"]))

    def test_corrupt_jar_retains_a_complete_error_manifest(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            jar.write_bytes(b"corrupt jar control")
            manifest = runner.run_study(checkout, 1, Path(directory) / "replicate-1", java=java, prebuilt_jar=jar)
            self.assertEqual("ERROR", manifest["collectionStatus"])
            self.assertIn("jar", manifest["jarError"])
            self.assertEqual(9, manifest["executionErrorCount"])

    def test_synthetic_jar_is_forbidden_for_a_hosted_measurement(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, jar = self.fixture(directory)
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"}):
                with self.assertRaisesRegex(ValueError, "build its own jar"):
                    runner.run_study(checkout, 1, Path(directory) / "replicate-1", java=java, prebuilt_jar=jar)

    def test_undeclared_hosted_event_is_rejected_before_starting_any_process(self):
        runner = self.runner_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, java, _ = self.fixture(directory)
            revision = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"],
                                               text=True).strip()
            event = Path(directory) / "event.json"
            event.write_text(json.dumps(dict(action="opened", number=999,
                pull_request=dict(head=dict(ref="codex/issue-981-jmh-precision-study",
                                            repo=dict(full_name="carstenartur/Regelsuche"), sha="a" * 40)))))
            environment = dict(GITHUB_ACTIONS="true", RUNNER_ENVIRONMENT="github-hosted",
                               GITHUB_SHA=revision, GITHUB_EVENT_NAME="workflow_dispatch",
                               GITHUB_EVENT_PATH=str(event), GITHUB_RUN_ATTEMPT="1",
                               GITHUB_REPOSITORY="carstenartur/Regelsuche",
                               GITHUB_HEAD_REF="codex/issue-981-jmh-precision-study",
                               GITHUB_JOB="jmh-precision-study", RUNNER_OS="Linux", ImageOS="ubuntu22")
            with patch.dict(os.environ, environment), patch.object(runner, "execute", side_effect=
                    AssertionError("unauthorized hosted launch reached a child process")):
                with self.assertRaisesRegex(ValueError, "launch"):
                    runner.run_study(checkout, 1, Path(directory) / "replicate-1", java=java,
                                     job_start_epoch=time.time())


@unittest.skipUnless(sys.platform == "linux", "the retained process fixtures require Linux")
class ReportControls(unittest.TestCase):
    setUp = RetainedStudyControls.setUp
    runner_module = RetainedStudyControls.runner_module
    fixture = RetainedStudyControls.fixture
    def report_module(self):
        path = ROOT / "scripts/report-jmh-precision-study-v1.py"
        self.assertTrue(path.is_file(), "retained study report verifier is missing")
        spec = importlib.util.spec_from_file_location("study_report", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def corpus(self, directory):
        runner = self.runner_module()
        checkout, java, jar = self.fixture(directory)
        outputs = [Path(directory) / f"replicate-{number}" for number in (1, 2, 3)]
        for number, output in enumerate(outputs, 1):
            manifest = runner.run_study(checkout, number, output, java=java, prebuilt_jar=jar)
            self.assertEqual("COMPLETE", manifest["collectionStatus"])
        return checkout, outputs

    def test_replay_is_byte_identical_and_does_not_invent_a_winner(self):
        reporter = self.report_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            first = reporter.analyze(checkout, outputs)
            second = reporter.analyze(checkout, list(reversed(outputs)))
            self.assertEqual(self.study.canonical(first), self.study.canonical(second))
            self.assertEqual("COMPLETE", first["collectionStatus"])
            self.assertEqual(609, first["measurementRowCount"])
            self.assertEqual(7, len(first["protocols"]))
            self.assertIsNone(first["selectedProtocol"])
            self.assertEqual("LOCAL_CONTROL", first["evidenceKind"])
            self.assertEqual("NO_LOW_PRECISION_IMPROVEMENT_ESTABLISHED", first["adoptionOutcome"])
            self.assertTrue(all(not row["eligibleForReview"] for row in first["protocols"]))

    def test_missing_and_duplicate_replicates_are_rejected(self):
        reporter = self.report_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            for selected in (outputs[:2], [outputs[0], outputs[0], outputs[2]]):
                with self.subTest(selected=selected), self.assertRaisesRegex(ValueError, "replicate"):
                    reporter.analyze(checkout, selected)

    def test_tampered_raw_log_policy_and_receipts_are_rejected(self):
        reporter = self.report_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            original = self.study.load_json(outputs[0] / "manifest.json")
            for relative in (original["cells"][0]["rawPath"], original["cells"][0]["logPath"],
                             "inputs/" + self.study.POLICY_PATH):
                path = outputs[0] / relative
                data = path.read_bytes()
                path.write_bytes(data + b"\n")
                with self.subTest(path=relative), self.assertRaises(ValueError):
                    reporter.analyze(checkout, outputs)
                path.write_bytes(data)
            for mutate in (
                    lambda manifest: manifest.update(sourceRevision="0" * 40),
                    lambda manifest: manifest.update(launch=dict(eventAction="opened")),
                    lambda manifest: manifest["jar"].update(contentSha256="0" * 64),
                    lambda manifest: manifest["cells"][0]["process"].update(exitCode=7),
                    lambda manifest: manifest["cells"][0]["process"].update(remainingDescendants=1),
                    lambda manifest: manifest["cells"][0]["process"].update(adoptedChildFailureExitCodes=[7]),
                    lambda manifest: manifest["cells"][0]["process"].update(adoptedChildFailureCount=1),
                    lambda manifest: manifest["javaVersion"].pop("supervision"),
                    lambda manifest: manifest["cells"][0]["summary"].update(lowPrecisionCount=99),
                    lambda manifest: manifest["cells"][0].update(rawPath="../outside.json"),
                    lambda manifest: manifest["cells"][0]["process"]["command"].extend(["-f", "5"]),
                    lambda manifest: manifest.update(elapsedSeconds=0),
                    lambda manifest: manifest["cells"].pop()):
                changed = copy.deepcopy(original)
                mutate(changed)
                (outputs[0] / "manifest.json").write_text(self.study.canonical(changed))
                with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                    reporter.analyze(checkout, outputs)
                (outputs[0] / "manifest.json").write_text(self.study.canonical(original))

    def test_reported_inconclusive_cannot_be_averaged_into_a_pass(self):
        reporter = self.report_module()
        with tempfile.TemporaryDirectory() as directory:
            checkout, outputs = self.corpus(directory)
            manifest = self.study.load_json(outputs[0] / "manifest.json")
            retained = manifest["cells"][0]
            path = outputs[0] / retained["rawPath"]
            payload = self.study.load_json(path)
            maximum = self.inventory[payload[0]["benchmark"]]["maximumAllowedScore"]
            payload[0]["primaryMetric"].update(score=maximum * 1.1, scoreError=maximum * .2,
                                                rawData=[[maximum * 1.1] * 3])
            path.write_text(json.dumps(payload))
            retained["rawSha256"] = self.study.sha256(path)
            cell = self.study.cells(self.policy, self.inventory, 1)[0]
            retained["summary"] = self.study.summarize(self.study.evaluate_result(payload, cell, self.inventory))
            manifest["ratchetStatus"] = "INCONCLUSIVE"
            (outputs[0] / "manifest.json").write_text(self.study.canonical(manifest))
            report = reporter.analyze(checkout, outputs)
            self.assertEqual("INCONCLUSIVE", report["ratchetStatus"])
            baseline = next(row for row in report["protocols"] if row["protocol"] == "baseline")
            self.assertEqual(1, baseline["summary"]["inconclusiveCount"])
            self.assertEqual("INCONCLUSIVE", baseline["benchmarks"][0]["worstStatus"])

    def test_adoption_requires_all_preregistered_conditions_at_their_boundaries(self):
        reporter = self.report_module()
        self.assertTrue(hasattr(reporter, "adoption_checks"), "adoption predicate is missing")
        baseline = dict(summary=dict(lowPrecisionCount=12, medianRelativeError=1.0, p90RelativeError=2.0),
                        families={family: dict(lowPrecisionCount=value) for family, value in
                                  zip(self.study.FAMILIES, (0, 9, 3))}, medianWallclockSeconds=100)
        candidate = dict(protocol="more-measurements", semanticWorkParity=True,
                         summary=dict(lowPrecisionCount=6, medianRelativeError=.4, p90RelativeError=.8,
                                      status="PASSED"), medianWallclockSeconds=350,
                         families={family: dict(lowPrecisionCount=value) for family, value in
                                   zip(self.study.FAMILIES, (0, 4, 2))})
        def checks(row, kind="GITHUB_SHARED_RUNNER"):
            return reporter.adoption_checks(row, baseline, kind, self.policy["analysis"])
        self.assertTrue(all(checks(candidate).values()))
        for mutate in (
                lambda row: row["summary"].update(lowPrecisionCount=7),
                lambda row: row["summary"].update(medianRelativeError=1.0),
                lambda row: row["summary"].update(p90RelativeError=2.0),
                lambda row: row["summary"].update(status="INCONCLUSIVE"),
                lambda row: row["summary"].update(status="FAILED"),
                lambda row: row.update(medianWallclockSeconds=350.01),
                lambda row: row["families"]["CORE"].update(lowPrecisionCount=1),
                lambda row: row.update(semanticWorkParity=False)):
            row = copy.deepcopy(candidate)
            mutate(row)
            with self.subTest(mutation=mutate):
                self.assertFalse(all(checks(row).values()))
        self.assertFalse(all(checks(candidate, "LOCAL_CONTROL").values()))
        baseline["summary"]["lowPrecisionCount"] = 0
        candidate["summary"]["lowPrecisionCount"] = 0
        self.assertFalse(checks(candidate)["positiveControlLowPrecisionCount"])

    def test_hosted_runner_names_need_not_be_unique_but_boots_must_be(self):
        reporter = self.report_module()
        self.assertTrue(hasattr(reporter, "verify_independent_runners"), "runner instance verification missing")
        manifests = [dict(runner=dict(RUNNER_NAME="Hosted Agent",
                                     bootId=f"00000000-0000-0000-0000-{number:012d}")) for number in (1, 2, 3)]
        reporter.verify_independent_runners(manifests)
        manifests[1]["runner"]["bootId"] = manifests[0]["runner"]["bootId"]
        with self.assertRaisesRegex(ValueError, "independent"):
            reporter.verify_independent_runners(manifests)
        del manifests[1]["runner"]["bootId"]
        with self.assertRaisesRegex(ValueError, "boot"):
            reporter.verify_independent_runners(manifests)

    def test_cli_retains_a_canonical_error_report_for_malformed_manifests(self):
        self.report_module()
        with tempfile.TemporaryDirectory() as directory:
            directories = [Path(directory) / str(number) for number in (1, 2, 3)]
            for path in directories:
                path.mkdir()
                (path / "manifest.json").write_text("[]\n")
            output = Path(directory) / "report"
            command = [sys.executable, "-B", str(ROOT / "scripts/report-jmh-precision-study-v1.py"),
                       "--repository-root", str(ROOT), "--output", str(output)]
            for path in directories:
                command.extend(["--replicate", str(path)])
            process = subprocess.run(command, capture_output=True, text=True, timeout=10)
            self.assertEqual(2, process.returncode, process.stderr)
            report = self.study.load_json(output / "report.json")
            self.assertEqual("ERROR", report["collectionStatus"])
            self.assertEqual("INCOMPLETE_EVIDENCE_NO_SELECTION", report["adoptionOutcome"])
            self.assertIsNone(report["selectedProtocol"])
            self.assertTrue(report["errors"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
