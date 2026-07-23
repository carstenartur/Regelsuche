#!/usr/bin/env python3
"""Reproduce and independently verify the claim-bounded paper evaluation."""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run ./gradlew prepareVerificationEnvironment"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_OUTPUT_FILES = (
    "candidate-independent-benchmark.md",
    "evaluation-summary.md",
    "evaluation-verification.json",
)


def fail(message: str) -> None:
    raise SystemExit(f"paper evaluation invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_unique(path: Path) -> Any:
    def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=pairs_hook)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        fail(f"cannot parse {path}: {error}")


def tree_entries(root: Path) -> list[tuple[str, str]]:
    require(
        root.is_dir() and not root.is_symlink(),
        f"directory is missing or symbolic: {root}",
    )
    entries: list[tuple[str, str]] = []
    for current, directory_names, file_names in os.walk(
        root,
        topdown=True,
        followlinks=False,
    ):
        current_path = Path(current)
        directory_names.sort()
        file_names.sort()
        for name in directory_names:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic link is forbidden: {path}")
            require(path.is_dir(), f"unsupported directory entry: {path}")
            entries.append(("directory", path.relative_to(root).as_posix()))
        for name in file_names:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic link is forbidden: {path}")
            require(path.is_file(), f"unsupported file entry: {path}")
            entries.append(("file", path.relative_to(root).as_posix()))
    return sorted(entries)


def require_identical(first: Path, second: Path, label: str) -> None:
    first_entries = tree_entries(first)
    second_entries = tree_entries(second)
    require(first_entries == second_entries, f"{label} trees differ")
    for kind, relative in first_entries:
        if kind != "file":
            continue
        require(
            (first / relative).read_bytes() == (second / relative).read_bytes(),
            f"{label} differs at {relative}",
        )


def run_generator(
    generator: Path,
    benchmark_execution: Path,
    output: Path,
    expect_success: bool,
    label: str,
) -> None:
    result = subprocess.run(
        [
            sys.executable,
            str(generator),
            "--execution",
            str(benchmark_execution),
            "--output",
            str(output),
        ],
        cwd=generator.parent.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    if expect_success and result.returncode != 0:
        fail(
            f"{label} failed with {result.returncode}: "
            f"{result.stdout}\n{result.stderr}"
        )
    if not expect_success and result.returncode == 0:
        fail(f"{label} unexpectedly passed")


def run_verifier(
    verifier: Path,
    manifest: Path,
    schema: Path,
    benchmark_execution: Path,
    output: Path,
    expect_success: bool,
    label: str,
) -> None:
    result = subprocess.run(
        [
            sys.executable,
            str(verifier),
            "--manifest",
            str(manifest),
            "--schema",
            str(schema),
            "--benchmark-execution",
            str(benchmark_execution),
            "--output",
            str(output),
        ],
        cwd=verifier.parent.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    if expect_success and result.returncode != 0:
        fail(
            f"{label} failed with {result.returncode}: "
            f"{result.stdout}\n{result.stderr}"
        )
    if not expect_success and result.returncode == 0:
        fail(f"{label} unexpectedly passed")


def reject_manifest_mutations(
    verifier: Path,
    manifest: dict[str, Any],
    schema: Path,
    benchmark_execution: Path,
    temporary_root: Path,
) -> None:
    duplicate_issue = copy.deepcopy(manifest)
    duplicate_issue["requiredEvidence"].append(
        {
            "issue": duplicate_issue["requiredEvidence"][0]["issue"],
            "purpose": "ambiguous duplicate issue",
            "status": "OPTIONAL",
        }
    )

    escaping_path = copy.deepcopy(manifest)
    escaping_path["files"][0]["path"] = "paper/../README.md"

    duplicate_path = copy.deepcopy(manifest)
    duplicate_path["files"].append(copy.deepcopy(duplicate_path["files"][0]))

    premature_status = copy.deepcopy(manifest)
    premature_status["status"] = "COMPLETE"

    benchmark_pending = copy.deepcopy(manifest)
    next(
        item
        for item in benchmark_pending["requiredEvidence"]
        if item["issue"] == 383
    )["status"] = "PENDING"

    for index, (label, mutation) in enumerate(
        (
            ("duplicate evidence issue", duplicate_issue),
            ("escaping paper path", escaping_path),
            ("duplicate paper path", duplicate_path),
            ("premature complete status", premature_status),
            ("benchmark status regression", benchmark_pending),
        )
    ):
        mutation_root = temporary_root / f"mutation-{index}"
        mutation_root.mkdir(parents=True)
        path = mutation_root / "manifest.json"
        path.write_text(json.dumps(mutation), encoding="utf-8")
        run_verifier(
            verifier,
            path,
            schema,
            benchmark_execution,
            mutation_root / "output",
            False,
            label,
        )


def reject_benchmark_mutations(
    generator: Path,
    benchmark: dict[str, Any],
    temporary_root: Path,
) -> None:
    inflated = copy.deepcopy(benchmark)
    inflated["publicationAuthorized"] = True
    missing_no_results = copy.deepcopy(benchmark)
    missing_no_results["totals"]["noResultCaseSlots"] = 0
    regression = copy.deepcopy(benchmark)
    regression["totals"]["correctnessRegressions"] = 1

    for index, (label, mutation) in enumerate(
        (
            ("inflated publication", inflated),
            ("removed no-result accounting", missing_no_results),
            ("introduced correctness regression", regression),
        )
    ):
        root = temporary_root / f"benchmark-mutation-{index}"
        root.mkdir(parents=True)
        source = root / "benchmark.json"
        source.write_text(json.dumps(mutation), encoding="utf-8")
        run_generator(
            generator,
            source,
            root / "table.md",
            False,
            label,
        )


def verify_claim_boundaries(root: Path) -> None:
    claims = (root / "paper/claims-and-evidence.md").read_text(encoding="utf-8")
    manuscript = (root / "paper/manuscript.md").read_text(encoding="utf-8")
    for token in (
        "SUPPORTED_BOUNDED_383",
        "PENDING_235",
        "PARTIAL_384",
        "NOT_AUTHORIZED",
    ):
        require(token in claims, f"claim registry is missing {token}")
    require(
        re.search(
            r"externally novel mathematics (?:was|is) (?:found|discovered|confirmed)",
            manuscript,
            flags=re.IGNORECASE,
        )
        is None,
        "manuscript contains a premature external-novelty claim",
    )
    require(
        "No baseline-superiority conclusion is made" in manuscript,
        "manuscript lacks explicit baseline non-claim",
    )
    require(
        "authoritative overall lifecycle status remains `NOT_ESTABLISHED`"
        in manuscript,
        "manuscript overstates amortization",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--benchmark-execution", type=Path, required=True)
    arguments = parser.parse_args()
    root = arguments.repository_root.resolve()
    benchmark_execution = arguments.benchmark_execution.resolve()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    verifier = root / "scripts/verify-paper-foundation.py"
    generator = root / "paper/generate-benchmark-results.py"
    manifest_path = root / "paper/paper-artifact-manifest.json"
    schema_path = root / "docs/schemas/regelsuche-paper-artifact-manifest-v2.schema.json"
    committed = root / "paper/generated"
    for path in (
        verifier,
        generator,
        manifest_path,
        schema_path,
        benchmark_execution,
    ):
        require(
            path.is_file() and not path.is_symlink(),
            f"required input is missing, non-regular or symbolic: {path}",
        )

    schema = load_unique(schema_path)
    require(isinstance(schema, dict), "paper manifest schema is not an object")
    require(
        schema.get("additionalProperties") is False,
        "paper manifest schema must fail closed",
    )
    Draft202012Validator.check_schema(schema)
    manifest = load_unique(manifest_path)
    require(isinstance(manifest, dict), "paper manifest is not an object")
    require(
        manifest.get("status") == "EVALUATION_IN_PROGRESS",
        "paper manifest status is not EVALUATION_IN_PROGRESS",
    )
    benchmark = load_unique(benchmark_execution)

    with tempfile.TemporaryDirectory(prefix="regelsuche-paper-") as directory:
        temporary_root = Path(directory)
        first = temporary_root / "first"
        second = temporary_root / "second"
        for output, label in ((first, "first generation"), (second, "second generation")):
            output.mkdir(parents=True)
            run_generator(
                generator,
                benchmark_execution,
                output / "candidate-independent-benchmark.md",
                True,
                f"{label} benchmark table",
            )
            run_verifier(
                verifier,
                manifest_path,
                schema_path,
                benchmark_execution,
                output,
                True,
                label,
            )
        require_identical(first, second, "clean paper generations")
        require_identical(first, committed, "committed paper evaluation")
        require(
            tree_entries(first)
            == sorted(("file", name) for name in EXPECTED_OUTPUT_FILES),
            "paper verification output tree drift",
        )

        verification = load_unique(first / "evaluation-verification.json")
        require(
            verification.get("manifestHash") == manifest.get("contentHash"),
            "evaluation verification manifest binding drift",
        )
        require(
            verification.get("status") == "EVALUATION_IN_PROGRESS",
            "verification status inflated",
        )
        require(
            verification.get("benchmarkExecutionHash")
            == benchmark.get("contentHash"),
            "verification benchmark binding drift",
        )
        require(
            verification.get("benchmarkStatus")
            == "COMPLETE_FROZEN_CHALLENGE_EXECUTION",
            "verification benchmark status drift",
        )
        require(
            verification.get("centralClaimsPending") is True,
            "verification no longer marks remaining claims pending",
        )
        require(
            verification.get("baselineClaimsAuthorized") is False,
            "verification authorizes baseline claims",
        )
        require(
            verification.get("completeAmortizationAuthorized") is False,
            "verification authorizes complete amortization",
        )
        require(
            verification.get("externalNoveltyAuthorized") is False,
            "verification authorizes external novelty",
        )
        reject_manifest_mutations(
            verifier,
            manifest,
            schema_path,
            benchmark_execution,
            temporary_root,
        )
        reject_benchmark_mutations(generator, benchmark, temporary_root)

    verify_claim_boundaries(root)
    print(f"jsonschema={installed}")
    print("paper-evaluation=VERIFIED")
    print(f"generated-files={len(EXPECTED_OUTPUT_FILES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
