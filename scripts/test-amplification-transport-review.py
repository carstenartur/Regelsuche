#!/usr/bin/env python3
"""Independent process-boundary controls; synthetic wire data is not study evidence."""
import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("cohort_review_fixtures", ROOT / "scripts/test-amplification-cohort.py")
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)
transport = fixtures.transport


class RetainedProcessReview(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.CohortAcceptance()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.roots = [self.fixture.bundle(role) for role in self.fixture.cohort.ROLES]

    def actual_process(self, root, prefix, body, timeout):
        argv = transport.verifier.read(root / (prefix + "-process.json"))[1]["argv"]
        for name in (prefix + "-process.json", prefix + ".stdout.txt", prefix + ".stderr.txt"):
            (root / name).unlink()
        fake_bin = self.fixture.root / ("bin-" + prefix)
        fake_bin.mkdir()
        executable = fake_bin / argv[0]
        executable.write_text("#!" + sys.executable + "\n" + body + "\n")
        executable.chmod(0o700)
        # The real short Python child receives the exact transport argv; it is not Java/Docker evidence.
        with patch.dict(os.environ, {"PATH": str(fake_bin) + os.pathsep + os.environ["PATH"]}):
            return transport.run_logged(argv, root, prefix, timeout=timeout)

    def test_actual_failed_docker_process_cannot_be_accepted_as_complete(self):
        root = self.roots[2]
        process = self.actual_process(root / "run", "docker",
            "import sys; print('retained outer failure', flush=True); sys.exit(9)", 3600)
        self.assertEqual(9, process["returnCode"])
        self.fixture.rebind(root / "run", "container-observation.json")
        self.fixture.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "container process did not complete"):
            self.fixture.compare(self.roots)

    def test_rehashed_java_stream_cannot_contradict_its_actual_process_hash(self):
        root = self.roots[0]
        process = self.actual_process(root / "run", "execution", "print('actually observed stdout')", 1800)
        self.assertTrue(transport.succeeded(process))
        self.fixture.rebind(root / "run", "execution-observation.json")
        self.fixture.rebind(root, "cohort-execution.json")
        self.assertEqual("REPRODUCED", self.fixture.compare(self.roots, "actual-process-positive.json")["status"])
        (root / "run/execution.stdout.txt").write_bytes(b"replaced retained stdout\n")
        self.fixture.rebind(root / "run", "execution-observation.json")
        self.fixture.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "process log binding"):
            self.fixture.compare(self.roots)

    def test_rehashed_java_invocation_cannot_replace_the_declared_flags(self):
        root = self.roots[0]
        path = root / "run/execution-process.json"
        process = transport.verifier.read(path)[1]
        process["argv"] = ["java", "-Xmx8g", "-cp", "/synthetic/classes", transport.MAIN,
                           "run", self.fixture.revision, "/synthetic/run"]
        path.write_bytes(transport.canonical(process))
        self.fixture.rebind(root / "run", "execution-observation.json")
        self.fixture.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "Java invocation"):
            self.fixture.compare(self.roots)

    def test_empty_retained_map_cannot_remove_the_result_binding_to_process_evidence(self):
        path = self.roots[0] / "cohort-execution.json"
        receipt = transport.verifier.read(path)[1]
        receipt["retainedFiles"] = {}
        path.write_bytes(transport.canonical(receipt))
        with self.assertRaisesRegex(ValueError, "retained file set"):
            self.fixture.compare(self.roots)

    def test_archive_source_authority_cannot_be_relabelled_as_a_host_execution(self):
        root = self.roots[0]
        def substitute(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if key == "sourceAuthority":
                        value[key] = "IMAGE_BUILT_FROM_DECLARED_COMMIT_ARCHIVE"
                    else:
                        substitute(item)
        for name in ("run/execution-start.json", "run/execution-observation.json", "run/execution-receipt.json",
                     "role-start.json", "cohort-execution.json"):
            path = root / name
            value = transport.verifier.read(path)[1]
            substitute(value)
            path.write_bytes(transport.canonical(value))
        self.fixture.rebind(root / "run", "execution-observation.json")
        path = root / "cohort-execution.json"
        receipt = transport.verifier.read(path)[1]
        receipt["executionReceiptHash"] = transport.file_hash(root / "run/execution-receipt.json")
        path.write_bytes(transport.canonical(receipt))
        self.fixture.rebind(root, "cohort-execution.json")
        with self.assertRaisesRegex(ValueError, "source authority"):
            self.fixture.compare(self.roots)

    def test_container_process_must_bind_the_same_image_cohort_and_readonly_inputs(self):
        root = self.roots[2]
        path = root / "run/docker-process.json"
        original = path.read_bytes()
        for index, replacement in ((-1, "sha256:" + "b" * 64), (9, "REGELSUCHE_AMPLIFICATION_COHORT_HASH=sha256:" + "f" * 64),
                                   (11, "type=bind,src=/synthetic/inputs,dst=/inputs")):
            with self.subTest(argument=index):
                process = transport.json.loads(original)
                process["argv"][index] = replacement
                path.write_bytes(transport.canonical(process))
                self.fixture.rebind(root / "run", "container-observation.json")
                self.fixture.rebind(root, "cohort-execution.json")
                with self.assertRaisesRegex(ValueError, "container (invocation|mount binding)"):
                    self.fixture.compare(self.roots, "container-argument-" + str(index) + ".json")


if __name__ == "__main__":
    unittest.main()
