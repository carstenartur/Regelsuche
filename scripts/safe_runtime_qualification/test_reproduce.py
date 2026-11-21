"""Fail-closed checks for clean-checkout and container reproduction."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


MODULE = Path(__file__).with_name("reproduce.py")
REPOSITORY = MODULE.resolve().parents[2]
REVISION_RESOURCE = Path("app/build/generated/source-revision/META-INF/regelsuche/source-revision")


def load_reproducer():
    spec = importlib.util.spec_from_file_location("safe_runtime_reproduce", MODULE)
    if spec is None or spec.loader is None:
        raise AssertionError("cannot load safe runtime reproducer")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(command: list[str], cwd: Path) -> str:
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode != 0:
        raise AssertionError(f"command failed: {command!r}\n{result.stdout}")
    return result.stdout.strip()


def git_repository(root: Path) -> tuple[Path, str]:
    repository = root / "repository"
    repository.mkdir()
    run(["git", "init", "--initial-branch=main"], repository)
    run(["git", "config", "user.name", "Qualification Test"], repository)
    run(["git", "config", "user.email", "qualification@invalid.example"], repository)
    (repository / "tracked.txt").write_text("fixed\n", encoding="utf-8")
    run(["git", "add", "tracked.txt"], repository)
    run(["git", "commit", "-m", "fixed source"], repository)
    return repository, run(["git", "rev-parse", "HEAD"], repository)


def write_canonical(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )


def output_tree(
    root: Path,
    implementation: str = "a" * 64,
    inventory: tuple[str, ...] = ("rule-a", "rule-b"),
) -> None:
    write_canonical(
        root / "qualification.json",
        {"schema": "regelsuche.safe-runtime-product-qualification/v1", "cases": 14},
    )
    write_canonical(
        root / "artifacts/case/DIRECT_V1/cli-analysis.json",
        {
            "implementation": {"contentHash": "sha256:" + implementation},
            "inventory": list(inventory),
        },
    )


def revision_build_fixture(root: Path) -> tuple[Path, str]:
    repository, revision = git_repository(root)
    (repository / "settings.gradle").write_text("rootProject.name = 'revision-control'\ninclude 'app'\n")
    app = repository / "app"
    app.mkdir()
    source = (REPOSITORY / "app/build.gradle").read_text()
    task = source.split("def sourceRevisionOutput =", 1)[1].split("tasks.named('processResources')", 1)[0]
    (app / "build.gradle").write_text("plugins { id 'java' }\ndef sourceRevisionOutput =" + task)
    shutil.copytree(REPOSITORY / "app/src/buildProvenance", app / "src/buildProvenance")
    return repository, revision


def revision_build(repository: Path, declared: str | None = None, environment: str | None = None,
                   configuration_cache: bool = False):
    command = [str(REPOSITORY / "gradlew"), "--offline", "--no-daemon",
               "--configuration-cache" if configuration_cache else "--no-configuration-cache",
               "--max-workers=1", "-Dorg.gradle.jvmargs=-Xmx192m -XX:MaxMetaspaceSize=192m",
               "-p", str(repository), ":app:writeSourceRevision"]
    if declared is not None:
        command.append("-PregelsucheRepositoryRevision=" + declared)
    env = os.environ.copy()
    env.pop("REGELSUCHE_PREPARATION_REVISION", None)
    env.pop("ORG_GRADLE_PROJECT_regelsucheRepositoryRevision", None)
    if environment is not None:
        env["REGELSUCHE_PREPARATION_REVISION"] = environment
    return subprocess.run(command, cwd=repository, env=env, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)


class SafeRuntimeReproductionTest(unittest.TestCase):
    def test_git_free_build_preserves_the_exact_checkout_revision_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository, revision = revision_build_fixture(root)
            original = revision_build(repository)
            self.assertEqual(0, original.returncode, original.stdout)
            expected = (revision + "\n").encode("ascii")
            self.assertEqual(expected, (repository / REVISION_RESOURCE).read_bytes())
            container_source = root / "git-free"
            shutil.copytree(repository, container_source,
                            ignore=shutil.ignore_patterns(".git", ".gradle", "build"))
            self.assertFalse((container_source / ".git").exists())
            observed = revision_build(container_source, revision, configuration_cache=True)
            self.assertEqual(0, observed.returncode, observed.stdout)
            self.assertEqual(expected, (container_source / REVISION_RESOURCE).read_bytes())
            repeated = revision_build(container_source, revision, configuration_cache=True)
            self.assertEqual(0, repeated.returncode, repeated.stdout)
            self.assertIn("Configuration cache entry reused", repeated.stdout)
            self.assertEqual(expected, (container_source / REVISION_RESOURCE).read_bytes())

    def test_declared_revision_cannot_hide_conflicting_or_malformed_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            repository, revision = revision_build_fixture(Path(directory))
            for name, declared, environment in (
                ("checkout conflict", "0" * 40, None),
                ("environment conflict", revision, "0" * 40),
                ("empty declaration", "", None),
                ("invalid declaration", "not-a-commit", None),
            ):
                with self.subTest(name=name):
                    observed = revision_build(repository, declared, environment)
                    self.assertNotEqual(0, observed.returncode, observed.stdout)
                    self.assertFalse((repository / REVISION_RESOURCE).exists())

    def test_source_revision_must_be_the_clean_checked_out_commit(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            repository, revision = git_repository(Path(directory))
            module.require_clean_revision(repository, revision)

            with self.assertRaisesRegex(module.ReproductionError, "revision"):
                module.require_clean_revision(repository, "0" * 40)

            (repository / "tracked.txt").write_text("changed\n", encoding="utf-8")
            with self.assertRaisesRegex(module.ReproductionError, "dirty"):
                module.require_clean_revision(repository, revision)

    def test_missing_docker_or_container_output_is_never_skipped(self):
        module = load_reproducer()
        with self.assertRaisesRegex(module.ReproductionError, "docker"):
            module.require_executable("docker", search_path="")

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_tree(root / "checkout-a")
            output_tree(root / "checkout-b")
            with self.assertRaisesRegex(module.ReproductionError, "container"):
                module.compare_canonical_outputs(
                    root / "checkout-a",
                    root / "checkout-b",
                    root / "container",
                )

    def test_extra_canonical_artifact_is_a_reproduction_failure(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("checkout-a", "checkout-b", "container"):
                output_tree(root / name)
            write_canonical(
                root / "checkout-b/artifacts/unexpected.json", {"unexpected": True}
            )

            with self.assertRaisesRegex(module.ReproductionError, "file set"):
                module.compare_canonical_outputs(
                    root / "checkout-a", root / "checkout-b", root / "container"
                )

    def test_qualification_byte_mismatch_reports_first_field_without_values(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("checkout-a", "checkout-b", "container"):
                output_tree(root / name)
            write_canonical(
                root / "container/qualification.json",
                {"schema": "regelsuche.safe-runtime-product-qualification/v1", "cases": 15},
            )
            with self.assertRaisesRegex(
                module.ReproductionError,
                r"canonical output bytes differ for container/qualification\.json"
                r" \(first differing JSON field: \$\.cases\)",
            ):
                module.compare_canonical_outputs(
                    root / "checkout-a", root / "checkout-b", root / "container"
                )

    def test_runtime_implementation_identity_bytes_must_match(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_tree(root / "checkout-a")
            output_tree(root / "checkout-b", "b" * 64)
            output_tree(root / "container")

            with self.assertRaisesRegex(module.ReproductionError, "bytes differ"):
                module.compare_canonical_outputs(
                    root / "checkout-a", root / "checkout-b", root / "container"
                )

    def test_runtime_inventory_bytes_must_match(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_tree(root / "checkout-a")
            output_tree(root / "checkout-b", inventory=("rule-a", "rule-c"))
            output_tree(root / "container")

            with self.assertRaisesRegex(module.ReproductionError, "bytes differ"):
                module.compare_canonical_outputs(
                    root / "checkout-a", root / "checkout-b", root / "container"
                )

    def test_diagnostics_are_outside_the_canonical_byte_comparison(self):
        module = load_reproducer()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("checkout-a", "checkout-b", "container"):
                output_tree(root / name)
            (root / "checkout-a/diagnostics").mkdir()
            (root / "checkout-b/diagnostics").mkdir()
            (root / "container/diagnostics").mkdir()
            (root / "checkout-a/diagnostics/runtime.log").write_text("port 41001\n")
            (root / "checkout-b/diagnostics/runtime.log").write_text("port 41002\n")
            (root / "container/diagnostics/runtime.log").write_text("port 41003\n")

            digest = module.compare_canonical_outputs(
                root / "checkout-a", root / "checkout-b", root / "container"
            )
            self.assertRegex(digest, r"^sha256:[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
