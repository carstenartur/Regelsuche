#!/usr/bin/env python3
"""Synthetic fail-closed characterization for supply-chain evidence verification."""

from __future__ import annotations

import copy
import importlib.util
import json
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "scripts" / "verify-supply-chain-evidence.py"
POLICY = ROOT / "config" / "quality" / "supply-chain-policy.json"


def load_verifier():
    spec = importlib.util.spec_from_file_location("supply_chain_verifier", VERIFIER)
    if spec is None or spec.loader is None:
        raise AssertionError("cannot load supply-chain verifier")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


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
        repo = temp / "repo"
        policy_target = repo / "config" / "quality" / "supply-chain-policy.json"
        policy_target.parent.mkdir(parents=True)
        policy_target.write_bytes(POLICY.read_bytes())

        first_bom = temp / "first-bom.json"
        second_bom = temp / "second-bom.json"
        write_json(first_bom, sample_bom("2026-08-09T00:00:00Z"))
        write_json(second_bom, sample_bom("2027-01-01T00:00:00Z"))
        first_output = temp / "first"
        second_output = temp / "second"
        module.verify_and_render(repo, first_bom, first_output)
        module.verify_and_render(repo, second_bom, second_output)

        assert (first_output / "dependency-inventory.json").read_bytes() == (
            second_output / "dependency-inventory.json"
        ).read_bytes()
        assert (first_output / "supply-chain-evidence.json").read_bytes() == (
            second_output / "supply-chain-evidence.json"
        ).read_bytes()
        evidence = json.loads((first_output / "supply-chain-evidence.json").read_text())
        assert evidence["componentCount"] == 1
        assert evidence["dependencyEdgeCount"] == 1
        assert evidence["vulnerabilityScanStatus"] == "NOT_EVALUATED"


def expect_failure(module, repo: Path, bom: dict, fragment: str) -> None:
    bom_path = repo.parent / "invalid-bom.json"
    write_json(bom_path, bom)
    try:
        module.verify_and_render(repo, bom_path, repo.parent / "invalid-output")
    except module.VerificationError as exc:
        assert fragment in str(exc), (fragment, str(exc))
    else:
        raise AssertionError(f"expected failure containing {fragment!r}")


def test_serial_number_and_unknown_edges_fail_closed(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = temp / "repo"
        policy_target = repo / "config" / "quality" / "supply-chain-policy.json"
        policy_target.parent.mkdir(parents=True)
        policy_target.write_bytes(POLICY.read_bytes())

        with_serial = sample_bom()
        with_serial["serialNumber"] = "urn:uuid:00000000-0000-0000-0000-000000000000"
        expect_failure(module, repo, with_serial, "serialNumber")

        bad_edge = sample_bom()
        bad_edge["dependencies"][0]["dependsOn"] = ["pkg:maven/missing/component@9"]
        expect_failure(module, repo, bad_edge, "unknown targets")


def test_unbound_policy_cannot_be_silently_promoted(module) -> None:
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        repo = temp / "repo"
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        policy["vulnerabilityPolicy"]["status"] = "COMPLETE"
        write_json(repo / "config" / "quality" / "supply-chain-policy.json", policy)
        bom_path = temp / "bom.json"
        write_json(bom_path, sample_bom())
        try:
            module.verify_and_render(repo, bom_path, temp / "output")
        except module.VerificationError as exc:
            assert "explicitly deferred" in str(exc)
        else:
            raise AssertionError("policy promotion without scanner evidence must fail")


def main() -> int:
    module = load_verifier()
    test_timestamp_is_excluded_from_semantic_evidence(module)
    test_serial_number_and_unknown_edges_fail_closed(module)
    test_unbound_policy_cannot_be_silently_promoted(module)
    print("supply-chain verifier synthetic tests: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
