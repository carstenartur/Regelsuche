#!/usr/bin/env python3
"""Synthetic fail-closed characterization for supply-chain evidence verification."""

from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

# Loading the verifier is part of the checkout-local test lifecycle. Prevent
# importlib from creating scripts/__pycache__ and dirtying the worktree.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-supply-chain-evidence.py"
POLICY = ROOT / "config" / "quality" / "supply-chain-policy.json"


def load_verifier(path: Path = VERIFIER):
    spec = importlib.util.spec_from_file_location("supply_chain_verifier", path)
    if spec is None or spec.loader is None:
        raise AssertionError("cannot load supply-chain verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_verifier_without_bytecode():
    cache_directory = VERIFIER.parent / "__pycache__"
    before = set(cache_directory.iterdir()) if cache_directory.is_dir() else set()
    module = None
    try:
        module = load_verifier()
    finally:
        after = set(cache_directory.iterdir()) if cache_directory.is_dir() else set()
        created = after - before
        for entry in created:
            entry.unlink()
        if not before and cache_directory.is_dir():
            cache_directory.rmdir()
    if created:
        raise AssertionError(
            "loading the verifier created Python bytecode: "
            + ", ".join(sorted(entry.name for entry in created))
        )
    if module is None:
        raise AssertionError("cannot load supply-chain verifier")
    return module


def create_repository(parent: Path) -> Path:
    repository = parent / "repo"
    policy_target = (
        repository / "config" / "quality" / "supply-chain-policy.json"
    )
    policy_target.parent.mkdir(parents=True)
    policy_target.write_bytes(POLICY.read_bytes())
    return repository


def sample_bom(timestamp: str = "2026-08-09T00:00:00Z") -> dict:
    root_ref = "pkg:maven/de.regelsuche/Regelsuche@0.2.0"
    component_ref = "pkg:maven/org.example/library@1.2.3"
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "version": 1,
        "metadata": {
            "timestamp": timestamp,
            "component": {
                "type": "application",
                "bom-ref": root_ref,
                "group": "de.regelsuche",
                "name": "Regelsuche",
                "version": "0.2.0",
                "purl": root_ref,
            },
        },
        "components": [
            {
                "type": "library",
                "bom-ref": component_ref,
                "group": "org.example",
                "name": "library",
                "version": "1.2.3",
                "purl": component_ref,
                "scope": "required",
                "licenses": [{"license": {"id": "Apache-2.0"}}],
                "hashes": [{"alg": "SHA-256", "content": "ABCD"}],
            }
        ],
        "dependencies": [
            {"ref": root_ref, "dependsOn": [component_ref]},
            {"ref": component_ref, "dependsOn": []},
        ],
    }


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def test_timestamp_is_excluded_from_semantic_evidence(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = create_repository(temp)

        first_bom = repo / "build" / "first-bom.json"
        second_bom = repo / "build" / "second-bom.json"
        write_json(first_bom, sample_bom("2026-08-09T00:00:00Z"))
        write_json(second_bom, sample_bom("2027-01-01T00:00:00Z"))
        first_output = repo / "build" / "first"
        second_output = repo / "build" / "second"
        module.verify_and_render(repo, first_bom, first_output)
        module.verify_and_render(repo, second_bom, second_output)

        assert (first_output / "dependency-inventory.json").read_bytes() == (
            second_output / "dependency-inventory.json"
        ).read_bytes()
        assert (first_output / "supply-chain-evidence.json").read_bytes() == (
            second_output / "supply-chain-evidence.json"
        ).read_bytes()
        evidence = json.loads(
            (first_output / "supply-chain-evidence.json").read_text()
        )
        assert evidence["componentCount"] == 1
        assert evidence["dependencyEdgeCount"] == 1
        assert evidence["vulnerabilityScanStatus"] == "NOT_EVALUATED"


def expect_failure(module, repo: Path, bom: dict, fragment: str) -> None:
    bom_path = repo / "build" / "invalid-bom.json"
    write_json(bom_path, bom)
    try:
        module.verify_and_render(
            repo,
            bom_path,
            repo / "build" / "invalid-output",
        )
    except module.VerificationError as exc:
        assert fragment in str(exc), (fragment, str(exc))
    else:
        raise AssertionError(f"expected failure containing {fragment!r}")


def test_serial_number_and_unknown_edges_fail_closed(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = create_repository(temp)

        with_serial = sample_bom()
        with_serial["serialNumber"] = (
            "urn:uuid:00000000-0000-0000-0000-000000000000"
        )
        expect_failure(module, repo, with_serial, "serialNumber")

        bad_edge = sample_bom()
        bad_edge["dependencies"][0]["dependsOn"] = [
            "pkg:maven/missing/component@9"
        ]
        expect_failure(module, repo, bad_edge, "unknown targets")


def test_unbound_policy_cannot_be_silently_promoted(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = temp / "repo"
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        policy["vulnerabilityPolicy"]["status"] = "COMPLETE"
        write_json(
            repo / "config" / "quality" / "supply-chain-policy.json",
            policy,
        )
        bom_path = repo / "build" / "bom.json"
        write_json(bom_path, sample_bom())
        try:
            module.verify_and_render(
                repo,
                bom_path,
                repo / "build" / "output",
            )
        except module.VerificationError as exc:
            assert "explicitly deferred" in str(exc)
        else:
            raise AssertionError("policy promotion without scanner evidence must fail")


def expect_path_failure(
    module,
    repository: Path,
    bom: Path,
    output: Path,
    fragment: str,
) -> None:
    try:
        module.verify_and_render(repository, bom, output)
    except module.VerificationError as exc:
        assert fragment in str(exc), (fragment, str(exc))
    else:
        raise AssertionError(f"expected path failure containing {fragment!r}")


def test_checkout_path_boundary_fails_closed(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = create_repository(temp)
        bom = repo / "build" / "bom.json"
        write_json(bom, sample_bom())

        outside_bom = temp / "outside-bom.json"
        write_json(outside_bom, sample_bom())
        expect_path_failure(
            module,
            repo,
            outside_bom,
            repo / "build" / "outside-bom-output",
            "inside the repository root",
        )
        expect_path_failure(
            module,
            repo,
            bom,
            temp / "outside-output",
            "inside the repository root",
        )
        expect_path_failure(
            module,
            repo,
            repo / "build" / ".." / "build" / "bom.json",
            repo / "build" / "dotdot-output",
            "must not contain '..'",
        )

        probe_target = temp / "symlink-probe-target"
        probe_target.write_text("probe", encoding="utf-8")
        probe_link = temp / "symlink-probe"
        try:
            probe_link.symlink_to(probe_target)
        except (NotImplementedError, OSError) as exc:
            if os.name == "nt":
                return
            raise AssertionError(
                f"cannot create required symbolic-link fixture: {exc}"
            ) from exc
        probe_link.unlink()

        repository_link = temp / "repo-link"
        repository_link.symlink_to(repo, target_is_directory=True)
        expect_path_failure(
            module,
            repository_link,
            repository_link / "build" / "bom.json",
            repository_link / "build" / "output",
            "repository root must not be symbolic",
        )

        bom_link = repo / "build" / "bom-link.json"
        bom_link.symlink_to(bom.name)
        expect_path_failure(
            module,
            repo,
            bom_link,
            repo / "build" / "output",
            "symbolic path",
        )

        real_output = repo / "build" / "real-output"
        real_output.mkdir()
        output_link = repo / "build" / "output-link"
        output_link.symlink_to(real_output.name, target_is_directory=True)
        expect_path_failure(module, repo, bom, output_link, "symbolic path")

        output = repo / "build" / "output-files"
        output.mkdir()
        outside = temp / "outside.json"
        outside.write_text("sentinel", encoding="utf-8")
        (output / "dependency-inventory.json").symlink_to(outside)
        expect_path_failure(module, repo, bom, output, "must not be symbolic")
        assert outside.read_text(encoding="utf-8") == "sentinel"

        policy = repo / "config" / "quality" / "supply-chain-policy.json"
        external_policy = temp / "external-policy.json"
        external_policy.write_bytes(policy.read_bytes())
        policy.unlink()
        policy.symlink_to(external_policy)
        expect_path_failure(module, repo, bom, output, "symbolic path")


def main() -> int:
    module = load_verifier_without_bytecode()
    test_timestamp_is_excluded_from_semantic_evidence(module)
    test_serial_number_and_unknown_edges_fail_closed(module)
    test_unbound_policy_cannot_be_silently_promoted(module)
    test_checkout_path_boundary_fails_closed(module)
    print("supply-chain verifier synthetic tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
