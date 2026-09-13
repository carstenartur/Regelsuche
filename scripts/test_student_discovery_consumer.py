"""Exercise cleanup boundaries; subprocess stubs are not Java qualification."""

from contextlib import redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("verify-student-java-sdk-consumer.py")
spec = importlib.util.spec_from_file_location("student_consumer_verifier", SCRIPT)
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class StopBeforeBuild(Exception):
    """Stop after cleanup without invoking a compiler or downloading artifacts."""


class StudentDiscoveryConsumerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.outside = self.base / "outside"
        self.write(self.outside / "keep.txt", "outside must survive")
        self.workspace("default")

    @staticmethod
    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def workspace(self, name):
        self.root = self.base / name / "checkout"
        self.repository = self.root / "build/student-sdk-repository"
        self.source = self.root / "examples/external-consumers/geometric-sequence-domain-java25"
        self.output = self.root / verifier.OUTPUT_RELATIVE
        self.write(self.repository / "keep.txt", "published artifacts")
        self.write(self.source / "keep.txt", "maintained consumer")
        for name in ("hello-rule-java25", "finite-difference-domain-java25", "solver-adapter-java25", "number-theory-plan-java25",
                     "extension-runtime-java25"):
            self.write(self.root / "examples/external-consumers" / name / "keep.txt", "maintained consumer")
        self.write(self.root / verifier.GENERATOR_RELATIVE, "# fixture generator\n")
        self.write(self.root / "release.properties", "version=0.4.0-SNAPSHOT\n")

    def argv(self, repository=None):
        return [str(SCRIPT), "--repository-root", str(self.root),
                "--published-repository", str(repository or self.repository),
                "--gradle", "must-not-be-invoked"]

    def symlink(self, link, target):
        link.parent.mkdir(parents=True, exist_ok=True)
        try:
            link.symlink_to(target, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are unavailable")

    def assert_inputs_survive(self):
        self.assertEqual("outside must survive", (self.outside / "keep.txt").read_text())
        self.assertEqual("published artifacts", (self.repository / "keep.txt").read_text())
        self.assertEqual("maintained consumer", (self.source / "keep.txt").read_text())

    def test_cli_rejects_caller_output_before_cleanup(self):
        self.write(self.output / "stale.txt", "not yet removable")
        for extra in (["--output", str(self.outside)], [f"--output={self.outside}"]):
            with self.subTest(extra=extra):
                result = subprocess.run([sys.executable, *self.argv(), *extra],
                                        capture_output=True, text=True, check=False,
                                        timeout=10)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn("unrecognized arguments: --output", result.stderr)
                self.assertEqual("not yet removable", (self.output / "stale.txt").read_text())
                self.assert_inputs_survive()

    def test_output_and_ancestor_symlinks_are_rejected_before_cleanup(self):
        for relative in ("build", "build/reports", "build/reports/student-java-sdk"):
            for dangling in (False, True):
                with self.subTest(relative=relative, dangling=dangling):
                    root = self.base / f"links-{relative.replace('/', '-')}-{dangling}"
                    target = self.base / "missing" if dangling else self.outside
                    link = root / relative
                    self.symlink(link, target)
                    with patch.object(sys, "argv", [str(SCRIPT), "--repository-root", str(root),
                            "--published-repository", str(self.repository)]), \
                            patch.object(verifier.shutil, "rmtree") as cleanup, \
                            self.assertRaisesRegex(RuntimeError, "must not traverse symlinks"):
                        verifier.main()
                    cleanup.assert_not_called()
                    self.assertTrue(link.is_symlink())
                    self.assertEqual(not dangling, target.exists())
                    self.assert_inputs_survive()

    def test_published_repository_overlap_is_rejected_before_cleanup(self):
        for relation in ("same", "child", "parent"):
            with self.subTest(relation=relation):
                self.workspace(relation)
                self.write(self.output / "stale.txt", "not yet removable")
                repository = {"same": self.output, "child": self.output / "repository",
                              "parent": self.output.parent}[relation]
                repository.mkdir(parents=True, exist_ok=True)
                with patch.object(sys, "argv", self.argv(repository)), \
                        patch.object(verifier.shutil, "rmtree") as cleanup, \
                        self.assertRaisesRegex(RuntimeError, "must be disjoint"):
                    verifier.main()
                cleanup.assert_not_called()
                self.assertEqual("not yet removable", (self.output / "stale.txt").read_text())
                self.assert_inputs_survive()

    def test_cleanup_removes_only_the_fixed_checkout_output(self):
        self.write(self.output / "stale.txt", "removable report")
        with patch.object(sys, "argv", self.argv()), \
                patch.object(verifier.shutil, "rmtree", wraps=verifier.shutil.rmtree) as cleanup, \
                patch.object(verifier, "artifact_files", side_effect=StopBeforeBuild), \
                self.assertRaises(StopBeforeBuild):
            verifier.main()
        cleanup.assert_called_once_with(self.output)
        self.assertTrue(self.output.is_dir())
        self.assertFalse((self.output / "stale.txt").exists())
        self.assert_inputs_survive()

    def test_cleanup_does_not_follow_symlinks_inside_owned_output(self):
        self.symlink(self.output / "linked", self.outside)
        with patch.object(sys, "argv", self.argv()), \
                patch.object(verifier, "artifact_files", side_effect=StopBeforeBuild), \
                self.assertRaises(StopBeforeBuild):
            verifier.main()
        self.assertEqual([], list(self.output.iterdir()))
        self.assert_inputs_survive()

    def test_internal_generator_uses_fixed_child_and_separate_caches(self):
        # This verifies orchestration, not the result of a real Java build.
        with patch.object(sys, "argv", self.argv()), \
                patch.object(verifier, "artifact_files", return_value={}), \
                patch.object(verifier, "verify_pinned_consumer", return_value={}), \
                patch.object(verifier, "execute_consumer",
                             side_effect=self.consumer_results()) as consumers, \
                patch.object(verifier, "verify_generated_project_shape", return_value={}), \
                patch.object(verifier, "run", side_effect=[
                    "generated", "java.specification.version = 25\n"]) as commands, \
                redirect_stdout(io.StringIO()):
            self.assertEqual(0, verifier.main())
        command = commands.call_args_list[0].args[0]
        self.assertEqual([sys.executable, str(self.root / verifier.GENERATOR_RELATIVE)], command[:2])
        self.assertEqual(str(self.output / "generated-starter"), command[command.index("--output") + 1])
        self.assertEqual(self.root, commands.call_args_list[0].args[1])
        self.assertEqual(7, consumers.call_count)
        first_call, second_call = [call.args for call in consumers.call_args_list[:2]]
        caches = [call.args[4] for call in consumers.call_args_list]
        self.assertEqual(7, len(set(caches)))
        self.assertEqual(self.output / "isolated-gradle-user-home", first_call[4])
        self.assertEqual(self.output / "generated-gradle-user-home", second_call[4])
        wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
        self.assertEqual(str(self.output / "generated-starter" / wrapper), second_call[0])
        report = json.loads((self.output / "consumer-report.json").read_text())
        self.assertEqual("success", report["result"])
        self.assertEqual({
            "hello-rule-java25": "success",
            "finite-difference-domain-java25": "success",
            "solver-adapter-java25": "success",
            "number-theory-plan-java25": "success",
            "extension-runtime-java25": "success",
        }, report["progressiveConsumers"])
        extension_call = consumers.call_args_list[-1].args
        self.assertEqual(self.output / "extension-runtime-java25", extension_call[1])
        self.assertEqual(self.output / "extension-runtime-java25-gradle-cache", extension_call[4])
        self.assertEqual("maintained consumer",
                         (extension_call[1] / "keep.txt").read_text())
        self.assertEqual(self.consumer_results()[-1][0],
                         (self.output / "extension-runtime-java25.log").read_text())
        self.assert_inputs_survive()

    @staticmethod
    def consumer_results():
        # Independent expected outputs of all seven consumers; no Java work is simulated
        # as evidence. This list only lets the real orchestration code reach each check.
        return [
            ("provider=example-geometric-sequence-provider outcome=CONFIRMED multiplier=2",
             "external dependencies", []),
            (f"provider={verifier.GENERATED_PROVIDER} outcome=CONFIRMED multiplier=2",
             "generated dependencies", []),
            ("rule=hello-add-zero replay=VERIFIED", "plugin dependencies", []),
            ("outcome=CONFIRMED sdk.provider.artifactSha256", "domain dependencies", []),
            ("outcome=CONFIRMED outcome=REFUTED sdk.provider.artifactSha256", "solver dependencies", []),
            ("provider=primachsenraum-number-theory-provider bases=[2, 3] 2047 falsePrimes=0 falseCompositeDecisions=0",
             "number theory dependencies", []),
            ("extension=hello origin=greeting-plugin catalog=sha256:" + "a" * 64,
             "extension dependencies", []),
        ]

    def assert_extension_rejected(self, result, message):
        results = self.consumer_results()
        results[-1] = result
        # A stale successful campaign must not survive a failed re-qualification.
        self.write(self.output / "consumer-report.json", '{"result":"success"}')
        with patch.object(sys, "argv", self.argv()), \
                patch.object(verifier, "artifact_files", return_value={}), \
                patch.object(verifier, "verify_pinned_consumer", return_value={}), \
                patch.object(verifier, "execute_consumer", side_effect=results) as consumers, \
                patch.object(verifier, "verify_generated_project_shape", return_value={}), \
                patch.object(verifier, "run", return_value="generated"), \
                redirect_stdout(io.StringIO()), \
                self.assertRaisesRegex(RuntimeError, message):
            verifier.main()
        self.assertEqual(7, consumers.call_count)
        self.assertFalse((self.output / "consumer-report.json").exists())
        self.assertFalse((self.output / "consumer-report.md").exists())
        self.assert_inputs_survive()

    def test_incomplete_extension_output_prevents_success_report(self):
        execution, dependencies, rejected = self.consumer_results()[-1]
        for marker in ("extension=hello", "origin=greeting-plugin", "catalog=sha256:"):
            with self.subTest(missing=marker):
                self.assert_extension_rejected(
                    (execution.replace(marker, ""), dependencies, rejected),
                    "extension-runtime-java25 output is incomplete")

    def test_extension_runtime_dependency_failure_prevents_success_report(self):
        execution, _, _ = self.consumer_results()[-1]
        self.assert_extension_rejected(
            (execution, "spring-context", ["spring-context"]),
            "extension-runtime-java25 has forbidden runtime dependencies")


if __name__ == "__main__":
    unittest.main()
