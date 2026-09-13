#!/usr/bin/env python3
"""Ordinary transport controls; fake child programs never constitute study evidence."""
import importlib.util
import copy
import json
import os
import subprocess
import tarfile
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


transport = module("amplification_transport_controls", "run-rule-amplification.py")


class ProcessRetention(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)

    def test_nonzero_child_retains_both_streams_and_exit_status(self):
        result = transport.run_logged([sys.executable, "-c",
            "import sys; print('ordinary stdout', flush=True); print('ordinary stderr', file=sys.stderr, flush=True); sys.exit(7)"],
            self.output, "execution", timeout=10)
        self.assertEqual("EXITED", result["status"])
        self.assertEqual(7, result["returnCode"])
        self.assertEqual(b"ordinary stdout\n", (self.output / "execution.stdout.txt").read_bytes())
        self.assertEqual(b"ordinary stderr\n", (self.output / "execution.stderr.txt").read_bytes())
        self.assertEqual(result, transport.verifier.read(self.output / "execution-process.json")[1])

    def test_timeout_retains_prefix_and_does_not_claim_completion(self):
        result = transport.run_logged([sys.executable, "-c",
            "import time; print('retained before timeout', flush=True); time.sleep(30)"],
            self.output, "execution", timeout=1)
        self.assertEqual("TIMED_OUT", result["status"])
        self.assertNotEqual(0, result["returnCode"])
        self.assertEqual(b"retained before timeout\n", (self.output / "execution.stdout.txt").read_bytes())
        self.assertFalse((self.output / "candidate-freeze.json").exists())

    def test_missing_executable_is_retained_without_overwriting_an_attempt(self):
        result = transport.run_logged([self.output / "missing-program"], self.output, "execution", timeout=10)
        self.assertEqual("START_FAILED", result["status"])
        self.assertIsNone(result["returnCode"])
        before = (self.output / "execution-process.json").read_bytes()
        with self.assertRaises(FileExistsError):
            transport.run_logged([sys.executable, "-c", "pass"], self.output, "execution", timeout=10)
        self.assertEqual(before, (self.output / "execution-process.json").read_bytes())

    def test_failed_container_client_retains_logs_and_requests_its_own_cleanup(self):
        # An actual Python executable stands in for the client; no Docker daemon or image is used.
        image, revision = "sha256:" + "a" * 64, "1" * 40
        inspected = [{"Id": image, "Config": {"Labels": {"org.opencontainers.image.revision": revision}}}]
        client = self.output / "docker"
        client.write_text("#!" + sys.executable + "\nimport sys\n"
            "if sys.argv[1:3] == ['image', 'inspect']: print(" + repr(json.dumps(inspected)) + ")\n"
            "elif sys.argv[1] == 'run':\n print('ordinary failed container client', file=sys.stderr, flush=True)\n sys.exit(9)\n"
            "elif sys.argv[1:3] == ['rm', '--force']: print(sys.argv[3], flush=True)\n"
            "else: sys.exit(3)\n")
        client.chmod(0o700)
        inputs = self.output / "inputs"
        inputs.mkdir()
        args = SimpleNamespace(image=image, inputs=inputs, output=self.output / "container", cohort_hash="sha256:" + "b" * 64)
        with patch.dict(os.environ, {"PATH": str(self.output) + os.pathsep + os.environ["PATH"]}), \
             patch.object(transport, "clean_revision", return_value=revision), \
             self.assertRaisesRegex(ValueError, "container authority did not finish"):
            transport.execute_container(args)
        observation = transport.verifier.read(args.output / "container-observation.json")[1]
        process = transport.verifier.read(args.output / "docker-process.json")[1]
        self.assertEqual("INCOMPLETE_EXECUTION", observation["status"])
        self.assertEqual(9, process["returnCode"])
        self.assertIn("--network=none", process["argv"])
        self.assertEqual(["docker", "rm", "--force", observation["containerName"]], observation["cleanup"]["argv"])
        self.assertTrue(transport.succeeded(observation["cleanup"]))
        self.assertEqual((observation["containerName"] + "\n").encode(), (args.output / "docker-cleanup.stdout.txt").read_bytes())
        self.assertFalse((args.output / "qualification-report.json").exists())


class CohortPreparation(unittest.TestCase):
    def setUp(self):
        self.cohort = module("amplification_cohort_controls", "run-amplification-cohort.py")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_minimal_context_uses_bound_archive_and_not_repository_dockerignore(self):
        inputs = self.root / "inputs"
        inputs.mkdir()
        (inputs / "source.tar").write_bytes(b"ordinary source archive bytes")
        (inputs / "Dockerfile").write_bytes(b"FROM bound-image\nCOPY source.tar /tmp/source.tar\n")
        (inputs / "unrelated.txt").write_text("must not enter Docker context")
        bindings = {name: transport.file_hash(inputs / name) for name in ("source.tar", "Dockerfile")}
        context = self.cohort.assemble_context(inputs, self.root / "context", {"files": bindings})
        self.assertEqual({"source.tar", "Dockerfile"}, {p.name for p in context.iterdir()})
        self.assertEqual((inputs / "source.tar").read_bytes(), (context / "source.tar").read_bytes())
        (inputs / "source.tar").write_bytes(b"substituted source")
        with self.assertRaisesRegex(ValueError, "binding"):
            self.cohort.assemble_context(inputs, self.root / "changed", {"files": bindings})

    def test_exact_jdk_build_and_observed_platform_are_required(self):
        contract = self.cohort.environment_contract()
        properties = {"java.runtime.version": "25.0.3+9-LTS", "java.vendor": "Eclipse Adoptium"}
        self.cohort.validate_environment(contract, properties, "Linux", "x86_64", "patchelf 0.14.3")
        for change in ({"java.runtime.version": "25"}, {"java.runtime.version": "25.0.4+7"}, {"java.vendor": "another vendor"}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.cohort.validate_environment(contract, properties | change, "Linux", "x86_64", "patchelf 0.14.3")
        with self.assertRaises(ValueError):
            self.cohort.validate_environment(contract, properties, "Linux", "aarch64", "patchelf 0.14.3")

    def test_noble_container_relocation_package_has_its_own_explicit_pin(self):
        contract = self.cohort.environment_contract()
        properties = {"java.runtime.version": "25.0.3+9-LTS", "java.vendor": "Eclipse Adoptium"}
        self.cohort.validate_environment(contract, properties, "Linux", "x86_64", "patchelf 0.18.0", kind="container")
        with self.assertRaises(ValueError):
            self.cohort.validate_environment(contract, properties, "Linux", "x86_64", "patchelf 0.14.3", kind="container")
        with self.assertRaises(ValueError):
            self.cohort.validate_environment(contract, properties, "Linux", "x86_64", "patchelf 0.18.0")

    def test_archive_contains_actual_committed_bytes_and_excludes_working_changes(self):
        source = self.root / "source"
        source.mkdir()
        subprocess.run(["git", "init", "--quiet", source], check=True)
        (source / "owned.txt").write_bytes(b"committed bytes")
        subprocess.run(["git", "add", "owned.txt"], cwd=source, check=True)
        subprocess.run(["git", "-c", "user.name=Transport Control", "-c", "user.email=transport-control@example.invalid",
                        "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "ordinary archive control"], cwd=source, check=True)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source).decode().strip()
        (source / "owned.txt").write_bytes(b"uncommitted change")
        (source / "untracked.txt").write_bytes(b"not a source archive member")
        destination = self.root / "source.tar"
        with patch.object(self.cohort, "ROOT", source):
            self.cohort.archive_source(destination, revision)
        with tarfile.open(destination) as archive:
            self.assertEqual(["owned.txt"], archive.getnames())
            self.assertEqual(b"committed bytes", archive.extractfile("owned.txt").read())


class ExecutionBoundaries(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        for name in ("plan.json", "sources.json", "qualification.json"):
            (self.inputs / name).write_bytes(b"{}")
        self.classes = self.root / "classes"
        for name in transport.ANCHORS:
            p = self.classes / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b"ordinary fake bytecode; not executable Java")
        self.classpath = self.root / "classpath.txt"
        self.classpath.write_text(str(self.classes))
        self.source = {"repositoryRevision": "1" * 40, "repositoryTree": "2" * 40,
                       "cleanCheckout": True, "sourceAuthority": "SYNTHETIC_TEST_SOURCE", "observedMachineHash": "sha256:" + "3" * 64}
        self.args = SimpleNamespace(inputs=self.inputs, classpath_file=self.classpath, output=self.root / "execution", cohort_hash="sha256:" + "4" * 64)

    def execute_fake(self, body):
        # The actual subprocess is Python; no fake program is counted as Java formation.
        original = transport.run_logged
        with patch.object(transport, "source_snapshot", return_value=self.source), \
             patch.object(transport, "command", return_value=SimpleNamespace(stderr=b"synthetic Java metadata")), \
             patch.object(transport, "run_logged", side_effect=lambda args, out, prefix: original([sys.executable, "-c", body], out, prefix, timeout=5)):
            return transport.execute_local(self.args, "host")

    def test_nonzero_execution_retains_start_failure_and_partial_output(self):
        with self.assertRaisesRegex(ValueError, "did not finish"):
            self.execute_fake("import sys; print('ordinary failed child', flush=True); sys.exit(9)")
        _, observation = transport.verifier.read(self.args.output / "execution-observation.json")
        self.assertEqual("INCOMPLETE_EXECUTION", observation["status"])
        self.assertEqual(observation["before"], observation["after"])
        self.assertFalse((self.args.output / "execution-receipt.json").exists())
        self.assertEqual(b"ordinary failed child\n", (self.args.output / "execution.stdout.txt").read_bytes())

    def test_actual_compiled_bytes_changed_by_child_are_rejected_after_execution(self):
        changed = self.classes / next(iter(transport.ANCHORS))
        with self.assertRaisesRegex(ValueError, "compiled authority changed"):
            self.execute_fake("from pathlib import Path; Path(" + repr(str(changed)) + ").write_bytes(b'changed')")
        _, observation = transport.verifier.read(self.args.output / "execution-observation.json")
        self.assertNotEqual(observation["before"]["implementationHash"], observation["after"]["implementationHash"])
        self.assertEqual("INCOMPLETE_EXECUTION", observation["status"])


class CohortAcceptance(unittest.TestCase):
    """Synthetic Java wire artifacts test transport acceptance only."""
    def process(self, root, prefix, argv, timeout):
        # Complete wire shape for import controls, explicitly not an observed process.
        for suffix, raw in (("stdout", b"synthetic process fixture; not execution evidence\n"), ("stderr", b"")):
            (root / (prefix + "." + suffix + ".txt")).write_bytes(raw)
        transport.write_new(root / (prefix + "-process.json"), {"schema": "regelsuche.amplification-process/v1",
            "argv": argv, "status": "EXITED", "returnCode": 0, "timeoutSeconds": timeout,
            "startedAt": "2026-09-13T00:00:00+00:00", "finishedAt": "2026-09-13T00:00:01+00:00",
            "logs": {name: transport.file_hash(root / name) for name in (prefix + ".stdout.txt", prefix + ".stderr.txt")}})

    def setUp(self):
        self.cohort = module("amplification_cohort_acceptance", "run-amplification-cohort.py")
        self.synthetic = module("amplification_synthetic_authority", "test-rule-amplification-reproduction.py")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.revision, self.tree = "1" * 40, "2" * 40
        self.contract = self.cohort.environment_contract()
        self.environment = {"javaRuntimeVersion": "25.0.3+9-LTS", "javaVendor": "Eclipse Adoptium",
            "system": "Linux", "architecture": "x86_64", "patchelf": "patchelf 0.14.3", "javaFlags": transport.JAVA_FLAGS,
            "contractHash": transport.file_hash(self.cohort.ENVIRONMENT), "kernel": "synthetic", "osRelease": {"ID": "synthetic"}}
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        self.files = self.synthetic.synthetic_files()
        # Expand one explicitly synthetic row into the exact public protocol cardinalities.
        source = self.files["sources.json"]["sources"][0]
        self.files["sources.json"]["sources"] = [copy.deepcopy(source) for _ in range(16)]
        label = self.files["qualification.json"]["rows"][0]
        self.files["qualification.json"]["rows"] = [dict(label, caseId="synthetic-" + str(i)) for i in range(16)]
        plan, freeze, report = (self.files[n] for n in ("plan.json", "candidate-freeze.json", "qualification-report.json"))
        plan["sourceHash"] = transport.digest(transport.canonical(self.files["sources.json"]))
        plan["qualificationHash"] = transport.digest(transport.canonical(self.files["qualification.json"]))
        freeze.update(sourceHash=plan["sourceHash"], qualificationHash=plan["qualificationHash"], planHash=transport.digest(transport.canonical(plan)))
        report.update(qualificationHash=plan["qualificationHash"], planHash=freeze["planHash"], statuses={"NEGATIVE_NO_CANDIDATE": 64})
        for artifact, names in ((freeze, ("native", "external")), (report, ("independentReplay", "nativeRows", "externalRows"))):
            for name in names:
                rows = copy.deepcopy(artifact[name])
                artifact[name] = []
                for index in range(16):
                    for row in copy.deepcopy(rows):
                        row["sourceIndex"] = index
                        if "caseId" in row:
                            row["caseId"] = "synthetic-" + str(index)
                        artifact[name].append(row)
        report["candidateFreezeHash"] = transport.digest(transport.canonical(freeze))
        for name in ("plan.json", "sources.json", "qualification.json"):
            (self.inputs / name).write_bytes(transport.canonical(self.files[name]))
        (self.inputs / "source.tar").write_bytes(b"synthetic archive; not a real source build")
        (self.inputs / "Dockerfile").write_bytes((ROOT / "reproduction/Dockerfile.amplification").read_bytes())
        (self.inputs / "environment.json").write_bytes(self.cohort.ENVIRONMENT.read_bytes())
        classes = {name: transport.digest(name.encode()) for name in sorted(transport.ANCHORS)}
        transport.write_new(self.inputs / "compiled-classes.json", classes)
        self.code_hash = transport.digest(transport.canonical(classes))
        self.plan = {"schema": self.cohort.SCHEMA, "repositoryRevision": self.revision, "repositoryTree": self.tree,
                     "cohortId": "c" * 32, "previousCohortResultHash": "", "corpus": self.cohort.CORPUS,
                     "scope": self.cohort.SCOPE, "comparativeGainClaim": "NOT_AUTHORIZED",
                     "roles": self.cohort.ROLES, "environment": self.contract, "sourceCount": 16, "nativeRows": 64, "externalRows": 96,
                     "implementationHash": self.code_hash, "files": {name: transport.file_hash(self.inputs / name) for name in self.cohort.INPUTS}}
        transport.write_new(self.inputs / "cohort-plan.json", self.plan)
        self.cohort_hash = transport.file_hash(self.inputs / "cohort-plan.json")

    def bundle(self, role):
        root = self.root / role
        run = root / "run"
        run.mkdir(parents=True)
        kind = "container" if role == "container" else "host"
        observed = {"repositoryRevision": self.revision, "repositoryTree": self.tree, "implementationHash": self.code_hash,
                    "cleanCheckout": True, "sourceAuthority": "CLEAN_CHECKOUT" if kind == "host" else "IMAGE_BUILT_FROM_DECLARED_COMMIT_ARCHIVE",
                    "javaVersion": "synthetic Java metadata",
                    "observedMachineHash": "" if kind == "container" else transport.digest(role.encode())}
        for name, value in self.files.items():
            transport.write_new(run / name, value)
        image = "sha256:" + "a" * 64
        execution = {"schema": "regelsuche.amplification-execution-receipt/v1", "kind": kind, **observed,
            "pinnedImageId": image if kind == "container" else "", "imageInspection": {"Id": image,
            "Config": {"Labels": {"org.opencontainers.image.revision": self.revision}}} if kind == "container" else {},
            "files": {name: transport.file_hash(run / name) for name in transport.verifier.FILES}}
        transport.write_new(run / "execution-receipt.json", execution)
        native = run
        if kind == "container":
            native = run / "bundle"
            native.mkdir()
            for p in run.iterdir():
                if p.is_file():
                    (native / p.name).write_bytes(p.read_bytes())
        transport.write_new(native / "execution-start.json", {**observed, "cohortHash": self.cohort_hash})
        self.process(native, "execution", ["java", *transport.JAVA_FLAGS, "-cp", "/synthetic/classes", transport.MAIN,
                                          "run", self.revision, str(native)], 1800)
        transport.retain_observation(native, "execution-observation.json", {"schema": "regelsuche.amplification-transport-observation/v1",
            "kind": kind, "status": "COMPLETE_BUNDLE", "before": observed, "after": observed, "cohortHash": self.cohort_hash})
        if kind == "container":
            transport.write_new(native / "cohort-inside.json", {"schema": "regelsuche.amplification-cohort-inside/v1", "cohortHash": self.cohort_hash,
                "before": observed, "environment": self.environment | {"patchelf": "patchelf 0.18.0"},
                "executionObservationHash": transport.file_hash(native / "execution-observation.json")})
            inspection = {"Id": image, "Config": {"Labels": {"org.opencontainers.image.revision": self.revision,
                "de.regelsuche.source-tree": self.tree, "de.regelsuche.source-archive-sha256": self.plan["files"]["source.tar"].removeprefix("sha256:")}}}
            container_name = "regelsuche-amplification-" + "a" * 32
            self.process(run, "docker", ["docker", "run", "--rm", "--platform", "linux/amd64", "--name", container_name,
                "--network=none", "--env", "REGELSUCHE_AMPLIFICATION_COHORT_HASH=" + self.cohort_hash, "--mount",
                "type=bind,src=" + str(self.inputs) + ",dst=/inputs,readonly", "--mount",
                "type=bind,src=" + str(run) + ",dst=/out", image], 3600)
            transport.retain_observation(run, "container-observation.json", {"status": "COMPLETE_BUNDLE", "imageInspection": inspection,
                "containerName": container_name})
        finish_source = {k: v for k, v in observed.items() if k not in ("implementationHash", "javaVersion")}
        if kind == "container":
            finish_source.update(sourceAuthority="CLEAN_CHECKOUT", observedMachineHash=transport.digest(b"synthetic-container-host"))
        start = {"source": observed if kind == "host" else finish_source, "environment": copy.deepcopy(self.environment)}
        finish = {"source": finish_source, "environment": copy.deepcopy(self.environment)}
        transport.write_new(root / "role-start.json", {"cohortHash": self.cohort_hash, "role": role, **start})
        transport.retain_observation(root, "cohort-execution.json", {"schema": "regelsuche.amplification-cohort-execution/v1", "role": role,
            "cohortHash": self.cohort_hash, "status": "COMPLETE_BUNDLE", "start": start, "finish": finish,
            "authorityFiles": execution["files"], "executionReceiptHash": transport.file_hash(run / "execution-receipt.json")})
        return root

    def compare(self, roots, filename="result.json", require_conclusive=False):
        with patch.object(self.cohort.transport, "clean_revision", return_value=self.revision):
            return self.cohort.compare(self.inputs, roots, self.root / filename, self.cohort_hash, require_conclusive)

    def rebind(self, root, name):
        value = transport.verifier.read(root / name)[1]
        (root / name).unlink()
        transport.retain_observation(root, name, value)

    def test_complete_synthetic_wire_bundle_passes_without_becoming_study_evidence(self):
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        self.assertEqual("REPRODUCED", self.compare(roots)["status"])

    def test_exact_preregistered_hash_and_all_input_bytes_are_required(self):
        with self.assertRaisesRegex(ValueError, "preregistered"):
            self.cohort.read_cohort(self.inputs, "sha256:" + "f" * 64)
        (self.inputs / "qualification.json").write_bytes(b"{}")
        with self.assertRaisesRegex(ValueError, "file binding"):
            self.cohort.read_cohort(self.inputs, self.cohort_hash)

    def test_missing_role_is_failure_with_a_retained_result(self):
        roots = [self.bundle("host-a"), self.bundle("host-b"), self.root / "missing"]
        with self.assertRaises(ValueError):
            self.compare(roots)
        self.assertEqual("NOT_REPRODUCED", transport.verifier.read(self.root / "result.json")[1]["status"])

    def test_rehashed_runtime_metadata_cannot_replace_the_pinned_jdk(self):
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        root = roots[0]
        receipt = transport.verifier.read(root / "cohort-execution.json")[1]
        receipt["start"]["environment"]["javaRuntimeVersion"] = "25.0.4+7"
        receipt["finish"]["environment"]["javaRuntimeVersion"] = "25.0.4+7"
        start = transport.verifier.read(root / "role-start.json")[1]
        start["environment"]["javaRuntimeVersion"] = "25.0.4+7"
        (root / "role-start.json").write_bytes(transport.canonical(start))
        receipt["retainedFiles"]["role-start.json"] = transport.file_hash(root / "role-start.json")
        (root / "cohort-execution.json").write_bytes(transport.canonical(receipt))
        with self.assertRaisesRegex(ValueError, "pinned Java build"):
            self.compare(roots)

    def test_failed_process_cannot_be_relabelled_as_a_complete_bundle(self):
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        root = roots[0]
        path = root / "run/execution-process.json"
        process = transport.verifier.read(path)[1]
        process["returnCode"] = 9
        path.write_bytes(transport.canonical(process))
        self.rebind(root / "run", "execution-observation.json")
        self.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "process did not complete"):
            self.compare(roots)

    def test_complete_identical_unavailable_rows_remain_inconclusive(self):
        freeze, report = self.files["candidate-freeze.json"], self.files["qualification-report.json"]
        outcome = freeze["external"][0]["outcome"]
        value = json.loads(outcome["canonicalJson"])
        value["status"] = outcome["status"] = "UNAVAILABLE"
        outcome["canonicalJson"] = transport.canonical(value).decode()
        outcome["contentHash"] = transport.digest(outcome["canonicalJson"].encode())
        report["externalRows"][0].update(status="UNAVAILABLE", outcomeHash=outcome["contentHash"])
        report["candidateFreezeHash"] = transport.digest(transport.canonical(freeze))
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        self.assertEqual("REPRODUCED_INCONCLUSIVE", self.compare(roots)["status"])
        with self.assertRaisesRegex(ValueError, "required conclusive"):
            self.compare(roots, "conclusive-result.json", require_conclusive=True)
        self.assertEqual("REPRODUCED_INCONCLUSIVE", transport.verifier.read(self.root / "conclusive-result.json")[1]["status"])

    def test_duplicate_role_cannot_supply_three_environments(self):
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        with self.assertRaisesRegex(ValueError, "each planned role"):
            self.compare([roots[0], roots[0], roots[2]])

    def test_rehashed_empty_second_host_identity_cannot_count_as_an_observed_machine(self):
        roots = [self.bundle(role) for role in self.cohort.ROLES]
        root = roots[1]
        def erase_identity(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key == "observedMachineHash":
                        value[key] = ""
                    else:
                        erase_identity(item)
        for name in ("run/execution-start.json", "run/execution-observation.json", "run/execution-receipt.json",
                     "role-start.json", "cohort-execution.json"):
            path = root / name
            value = transport.verifier.read(path)[1]
            erase_identity(value)
            path.write_bytes(transport.canonical(value))
        self.rebind(root / "run", "execution-observation.json")
        receipt = transport.verifier.read(root / "cohort-execution.json")[1]
        receipt["executionReceiptHash"] = transport.file_hash(root / "run/execution-receipt.json")
        (root / "cohort-execution.json").write_bytes(transport.canonical(receipt))
        self.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "observed host machine"):
            self.compare(roots)

    def test_real_transport_process_connects_to_cohort_acceptance_with_synthetic_wire_output(self):
        classes = self.root / "classes"
        for name in transport.ANCHORS:
            path = classes / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
        classpath = self.root / "classpath.txt"
        classpath.write_text(str(classes))
        wire = self.root / "synthetic-wire"
        wire.mkdir()
        for name, value in self.files.items():
            transport.write_new(wire / name, value)
        owner = self.cohort.transport
        source = {"repositoryRevision": self.revision, "repositoryTree": self.tree, "cleanCheckout": True,
                  "sourceAuthority": "CLEAN_CHECKOUT", "observedMachineHash": transport.digest(b"host-a")}
        # An actual Python executable accepts the real transport argv. Its emitted wire data remains synthetic.
        fake_bin = self.root / "bin"
        fake_bin.mkdir()
        fake_java = fake_bin / "java"
        fake_java.write_text("#!" + sys.executable + "\nfrom pathlib import Path\nimport shutil, sys\n"
            "[shutil.copyfile(p, Path(sys.argv[-1])/p.name) for p in Path(" + repr(str(wire)) + ").iterdir()]\n")
        fake_java.chmod(0o700)
        with patch.object(self.cohort, "CLASSPATH", classpath), \
             patch.object(self.cohort, "compile_authority", return_value=str(classes)), \
             patch.object(self.cohort, "observed_environment", return_value=self.environment), \
             patch.object(owner, "source_snapshot", return_value=source), \
             patch.object(owner, "command", return_value=SimpleNamespace(stderr=b"synthetic Java metadata")), \
             patch.dict(os.environ, {"PATH": str(fake_bin) + os.pathsep + os.environ["PATH"]}):
            self.cohort.execute_role("host-a", self.inputs, self.root / "host-a", self.cohort_hash)
        roots = [self.root / "host-a", self.bundle("host-b"), self.bundle("container")]
        self.assertEqual("REPRODUCED", self.compare(roots)["status"])


if __name__ == "__main__":
    unittest.main()
