#!/usr/bin/env python3
"""Validate the claim-bounded paper evaluation and its benchmark table."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path
from typing import Any

EXPECTED_MANIFEST_SCHEMA = "regelsuche.paper-artifact-manifest/v2"
EXPECTED_MANIFEST_STATUS = "EVALUATION_IN_PROGRESS"
EXPECTED_BENCHMARK_SCHEMA = (
    "regelsuche.candidate-independent-benchmark-execution/v2"
)
EXPECTED_BENCHMARK_STATUS = "COMPLETE_FROZEN_CHALLENGE_EXECUTION"
EXPECTED_BENCHMARK_TOTALS = {
    "configuredCampaigns": 12,
    "executedCampaigns": 12,
    "configuredCaseSlots": 72,
    "executedCaseSlots": 72,
    "successfulCaseSlots": 52,
    "noResultCaseSlots": 20,
    "detailedEvaluationRows": 120,
    "correctnessRegressions": 0,
}


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def fail(message: str) -> None:
    raise SystemExit(f"paper evaluation invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_unique(path: Path) -> Any:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ValueError(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=pairs)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        fail(f"cannot parse {path}: {error}")


def reject_symbolic_absolute_components(path: Path, label: str) -> Path:
    absolute = path.absolute()
    if absolute.anchor:
        current = Path(absolute.anchor)
        components = absolute.parts[1:]
    else:  # pragma: no cover
        current = Path()
        components = absolute.parts
    for component in components:
        current = current / component
        if current.is_symlink():
            fail(f"{label} contains a symbolic path component: {current}")
    return absolute


def require_regular_input(path: Path, label: str) -> Path:
    absolute = reject_symbolic_absolute_components(path, label)
    if not absolute.is_file():
        fail(f"{label} is missing or non-regular: {path}")
    return absolute.resolve()


def reject_symbolic_components(root: Path, relative: Path) -> None:
    current = root
    for component in relative.parts:
        current = current / component
        if current.is_symlink():
            fail(f"paper source path contains a symbolic link: {relative}")


def resolve_paper_source(root: Path, value: str) -> Path:
    relative = Path(value)
    if relative.is_absolute() or not relative.parts:
        fail(f"paper source path is not relative: {value}")
    if relative.parts[0] != "paper" or ".." in relative.parts:
        fail(f"paper source escapes paper/: {value}")
    reject_symbolic_components(root, relative)
    paper_root = (root / "paper").resolve()
    resolved = (root / relative).resolve()
    if resolved.parent != paper_root and paper_root not in resolved.parents:
        fail(f"paper source escapes paper/: {value}")
    return resolved


def prepare_output(path: Path) -> Path:
    absolute = reject_symbolic_absolute_components(
        path, "paper verification output"
    )
    absolute.mkdir(parents=True, exist_ok=True)
    reject_symbolic_absolute_components(
        absolute, "paper verification output"
    )
    resolved = absolute.resolve()
    if not resolved.is_dir():
        fail(f"paper verification output is not a directory: {path}")
    return resolved


def verify_content_hash(value: dict[str, Any], label: str) -> str:
    retained = value.get("contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = sha256_bytes(canonical_bytes(material))
    require(retained == expected, f"{label} contentHash mismatch")
    return expected


def verify_benchmark(execution: dict[str, Any]) -> str:
    require(
        execution.get("schema") == EXPECTED_BENCHMARK_SCHEMA,
        "unexpected benchmark execution schema",
    )
    require(
        execution.get("benchmarkStatus") == EXPECTED_BENCHMARK_STATUS,
        "candidate-independent benchmark is incomplete",
    )
    require(
        execution.get("totals") == EXPECTED_BENCHMARK_TOTALS,
        f"candidate-independent benchmark totals changed: {execution.get('totals')}",
    )
    require(
        execution.get("publicationAuthorized") is False,
        "benchmark execution unexpectedly authorizes publication",
    )
    require(
        execution.get("formalProofStatus")
        == "NOT_EVALUATED_AT_BENCHMARK_AGGREGATE",
        "benchmark aggregate proof status changed",
    )
    require(
        execution.get("externalNoveltyStatus") == "NOT_EVALUATED",
        "benchmark aggregate external-novelty status changed",
    )
    challenges = execution.get("challengeExecutions")
    require(isinstance(challenges, list), "challengeExecutions is not an array")
    require(
        [item.get("challengeId") for item in challenges]
        == [
            "finite-difference-recurrences",
            "rational-assumption-rewrites",
            "reusable-search-macros",
        ],
        "benchmark challenge identity or order changed",
    )
    for challenge in challenges:
        require(
            challenge.get("executedCampaigns") == 4,
            f"challenge campaign count changed: {challenge.get('challengeId')}",
        )
        require(
            challenge.get("caseSlots") == 24,
            f"challenge case-slot count changed: {challenge.get('challengeId')}",
        )
        require(
            challenge.get("successfulCaseSlots", 0)
            + challenge.get("noResultCaseSlots", 0)
            == 24,
            f"challenge case-slot accounting incomplete: {challenge.get('challengeId')}",
        )
        require(
            challenge.get("correctnessRegressions") == 0,
            f"challenge correctness regression: {challenge.get('challengeId')}",
        )
    return verify_content_hash(execution, "benchmark execution")


def verify_evidence_statuses(manifest: dict[str, Any]) -> None:
    statuses = {
        item["issue"]: item["status"]
        for item in manifest["requiredEvidence"]
    }
    require(len(statuses) == len(manifest["requiredEvidence"]),
        "requiredEvidence contains duplicate issue ids")
    expected = {
        383: "COMPLETE",
        235: "PENDING",
        384: "PARTIAL",
        385: "COMPLETE",
        387: "COMPLETE",
        389: "OPTIONAL",
    }
    require(statuses == expected, f"paper evidence status drift: {statuses}")


def verify_table(
    table_path: Path,
    benchmark_hash: str,
) -> None:
    require(
        table_path.is_file() and not table_path.is_symlink(),
        f"generated benchmark table is missing or symbolic: {table_path}",
    )
    text = table_path.read_text(encoding="utf-8")
    for token in (
        benchmark_hash,
        "| **Total** | **12** | **72** | **52** | **20** | **120** | **0** |",
        "does not authorize formal-proof",
    ):
        require(token in text, f"generated benchmark table is missing {token}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--benchmark-execution", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = Path.cwd().resolve()
    manifest_path = require_regular_input(args.manifest, "paper manifest")
    schema_path = require_regular_input(args.schema, "paper manifest schema")
    benchmark_path = require_regular_input(
        args.benchmark_execution, "candidate-independent benchmark execution"
    )
    manifest = load_unique(manifest_path)
    schema = load_unique(schema_path)
    benchmark = load_unique(benchmark_path)

    try:
        from jsonschema import Draft202012Validator
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "jsonschema is required to verify the paper evaluation"
        ) from exc

    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(manifest)
    require(
        manifest.get("schema") == EXPECTED_MANIFEST_SCHEMA,
        "unexpected paper manifest schema",
    )
    require(
        manifest.get("status") == EXPECTED_MANIFEST_STATUS,
        "paper status is not EVALUATION_IN_PROGRESS",
    )
    require(
        manifest.get("benchmarkExecutionSchema") == EXPECTED_BENCHMARK_SCHEMA,
        "paper manifest benchmark schema binding drift",
    )
    verify_evidence_statuses(manifest)

    listed_paths: set[str] = set()
    for item in manifest["files"]:
        value = item["path"]
        require(value not in listed_paths,
            f"paper source path appears more than once: {value}")
        listed_paths.add(value)
        path = resolve_paper_source(root, value)
        require(path.is_file(), f"missing paper source: {value}")
        actual = sha256_bytes(path.read_bytes())
        require(actual == item["sha256"], f"paper source hash mismatch: {value}")

    verify_content_hash(manifest, "paper artifact manifest")
    benchmark_hash = verify_benchmark(benchmark)

    claims = resolve_paper_source(root, manifest["claimRegistry"]).read_text(
        encoding="utf-8"
    )
    limitations = resolve_paper_source(root, manifest["limitations"]).read_text(
        encoding="utf-8"
    )
    manuscript = resolve_paper_source(root, manifest["manuscript"]).read_text(
        encoding="utf-8"
    )
    for token in (
        "SUPPORTED_BOUNDED_383",
        "PENDING_235",
        "PARTIAL_384",
        "NOT_AUTHORIZED",
    ):
        require(token in claims, f"claim registry is missing {token}")
    for phrase in (
        "Project-internal novelty",
        "Automated tools",
        "Byte-identical local and container runs",
    ):
        require(phrase in limitations,
            f"limitations are missing required section: {phrase}")
    require(
        "paper/generated/candidate-independent-benchmark.md" in manuscript,
        "manuscript does not cite the generated benchmark table",
    )
    require(
        re.search(
            r"externally novel mathematics (?:was|is) (?:found|discovered|confirmed)",
            manuscript,
            flags=re.IGNORECASE,
        )
        is None,
        "manuscript contains a premature external-novelty claim",
    )

    output = prepare_output(args.output)
    table_path = root / manifest["benchmarkResultsOutput"]
    verify_table(table_path, benchmark_hash)
    summary = {
        "schema": "regelsuche.paper-evaluation-verification/v2",
        "manifestHash": manifest["contentHash"],
        "status": manifest["status"],
        "benchmarkExecutionHash": benchmark_hash,
        "benchmarkStatus": EXPECTED_BENCHMARK_STATUS,
        "requiredEvidenceIssues": sorted(
            item["issue"] for item in manifest["requiredEvidence"]
        ),
        "centralClaimsPending": True,
        "baselineClaimsAuthorized": False,
        "completeAmortizationAuthorized": False,
        "externalNoveltyAuthorized": False,
    }
    (output / "evaluation-verification.json").write_text(
        json.dumps(
            summary,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    (output / "evaluation-summary.md").write_text(
        "# Paper evaluation verification\n\n"
        f"- Status: `{manifest['status']}`\n"
        f"- Manifest: `{manifest['contentHash']}`\n"
        f"- Candidate-independent benchmark: `{benchmark_hash}`\n"
        "- Benchmark execution: complete and bounded\n"
        "- Information-parity comparisons: pending\n"
        "- Complete lifecycle amortization: pending\n"
        "- External mathematical novelty: not authorized\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
