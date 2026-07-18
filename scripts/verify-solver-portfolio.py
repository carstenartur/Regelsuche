#!/usr/bin/env python3
"""Verify the canonical solver-portfolio evidence bundle fail-closed."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator
except ImportError as exc:  # pragma: no cover - environment diagnosis
    raise SystemExit(
        "jsonschema==4.25.1 is required; install it before running the verifier"
    ) from exc

REPORT_SCHEMA = "regelsuche-solver-portfolio-report-v1.schema.json"
OBLIGATION_SCHEMA = "regelsuche-solver-obligation-v1.schema.json"
TRANSLATION_SCHEMA = "regelsuche-solver-translation-v1.schema.json"
RESULT_SCHEMA = "regelsuche-solver-result-v1.schema.json"
EXECUTION_SCHEMA = "regelsuche-solver-execution-v1.schema.json"
EXPECTED_TELEMETRY_NOTICE = "EXECUTION_TELEMETRY_NOT_MATHEMATICAL_EVIDENCE"


class VerificationError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise VerificationError(message)


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise VerificationError(f"cannot read JSON {path}: {exc}") from exc
    require(isinstance(value, dict), f"{path} must contain a JSON object")
    return value


def load_schema(path: Path) -> dict[str, Any]:
    schema = load_json(path)
    Draft202012Validator.check_schema(schema)
    return schema


def validate_document(
    document: dict[str, Any], schema: dict[str, Any], context: str
) -> None:
    errors = sorted(
        Draft202012Validator(schema).iter_errors(document),
        key=lambda error: list(error.absolute_path),
    )
    if errors:
        rendered = "; ".join(
            f"{'.'.join(map(str, error.absolute_path)) or '<root>'}: {error.message}"
            for error in errors
        )
        raise VerificationError(f"{context} violates its schema: {rendered}")


def validate_run(
    root: Path,
    name: str,
    obligation_schema: dict[str, Any],
    report_schema: dict[str, Any],
    translation_schema: dict[str, Any],
    result_schema: dict[str, Any],
    execution_schema: dict[str, Any],
) -> dict[str, Any]:
    run_root = root / name
    require(run_root.is_dir(), f"missing solver run directory {run_root}")

    obligation = load_json(run_root / "obligation.json")
    request = load_json(run_root / "request.json")
    report = load_json(run_root / "report.json")
    validate_document(obligation, obligation_schema, f"{name} obligation")
    validate_document(report, report_schema, f"{name} report")

    require(
        request.get("obligationHash") == obligation.get("contentHash"),
        f"{name} request does not bind the obligation",
    )
    require(
        report.get("obligationHash") == obligation.get("contentHash"),
        f"{name} report does not bind the obligation",
    )
    require(
        report.get("requestHash") == request.get("contentHash"),
        f"{name} report does not bind the request",
    )

    attempts = report.get("attempts")
    require(isinstance(attempts, list), f"{name} report attempts are missing")
    traced_hashes = {
        attempt.get("executionHash")
        for attempt in attempts
        if isinstance(attempt, dict) and attempt.get("executionHash")
    }

    executions_root = run_root / "executions"
    require(executions_root.is_dir(), f"missing {name} execution directory")
    execution_directories = sorted(
        path for path in executions_root.iterdir() if path.is_dir()
    )
    retained_hashes: set[str] = set()
    for attempt_root in execution_directories:
        translation = load_json(attempt_root / "translation.json")
        result = load_json(attempt_root / "result.json")
        execution = load_json(attempt_root / "execution.json")
        validate_document(
            translation, translation_schema, f"{name}/{attempt_root.name} translation"
        )
        validate_document(result, result_schema, f"{name}/{attempt_root.name} result")
        validate_document(
            execution, execution_schema, f"{name}/{attempt_root.name} execution"
        )

        obligation_hash = obligation.get("contentHash")
        require(
            translation.get("obligationHash") == obligation_hash,
            f"{attempt_root} translation obligation mismatch",
        )
        require(
            result.get("obligationHash") == obligation_hash,
            f"{attempt_root} result obligation mismatch",
        )
        require(
            execution.get("obligationHash") == obligation_hash,
            f"{attempt_root} execution obligation mismatch",
        )
        require(
            execution.get("translationHash") == translation.get("contentHash"),
            f"{attempt_root} execution translation mismatch",
        )
        require(
            execution.get("resultHash") == result.get("contentHash"),
            f"{attempt_root} execution result mismatch",
        )
        for field in ("backendId", "backendVersion"):
            require(
                execution.get(field) == translation.get(field) == result.get(field),
                f"{attempt_root} {field} mismatch",
            )
        require(
            execution.get("translationStatus") == translation.get("status"),
            f"{attempt_root} translation status mismatch",
        )
        require(
            execution.get("translationStatus") == result.get("translationStatus"),
            f"{attempt_root} result translation status mismatch",
        )
        require(
            execution.get("resultStatus") == result.get("status"),
            f"{attempt_root} result status mismatch",
        )
        content_hash = execution.get("contentHash")
        require(isinstance(content_hash, str), f"{attempt_root} execution hash missing")
        require(
            content_hash not in retained_hashes,
            f"duplicate retained execution hash {content_hash}",
        )
        retained_hashes.add(content_hash)

    require(
        retained_hashes == traced_hashes,
        f"{name} report/retained execution set mismatch: "
        f"reported={sorted(traced_hashes)} retained={sorted(retained_hashes)}",
    )
    require(
        len(execution_directories) == len(retained_hashes),
        f"{name} contains duplicate execution directories",
    )
    selected = report.get("selectedExecutionHash")
    if selected:
        require(selected in retained_hashes, f"{name} selected execution is not retained")
    return report


def verify_semantics(
    root: Path,
    schemas: Path,
) -> None:
    report_schema = load_schema(schemas / REPORT_SCHEMA)
    obligation_schema = load_schema(schemas / OBLIGATION_SCHEMA)
    translation_schema = load_schema(schemas / TRANSLATION_SCHEMA)
    result_schema = load_schema(schemas / RESULT_SCHEMA)
    execution_schema = load_schema(schemas / EXECUTION_SCHEMA)

    formal = validate_run(
        root,
        "formal",
        obligation_schema,
        report_schema,
        translation_schema,
        result_schema,
        execution_schema,
    )
    guidance = validate_run(
        root,
        "guidance",
        obligation_schema,
        report_schema,
        translation_schema,
        result_schema,
        execution_schema,
    )
    manifest = load_json(root / "manifest.json")

    require(formal.get("outcome") == "CONFIRMED", "formal outcome is not CONFIRMED")
    require(formal.get("proofAuthorized") is True, "formal proof is not authorized")
    require(
        formal.get("selectedBackendId") == "z3-smt-proof",
        "formal selection did not retain the Z3 proof backend",
    )
    formal_attempts = formal.get("attempts", [])
    executed = [
        attempt.get("backendId")
        for attempt in formal_attempts
        if isinstance(attempt, dict) and attempt.get("disposition") == "EXECUTED"
    ]
    require(
        "polynomial-normal-form" in executed,
        "formal run did not execute polynomial normalization",
    )
    require("z3-smt-proof" in executed, "formal run did not execute Z3 proof")
    require(
        guidance.get("outcome") == "CONFIRMED",
        "guidance outcome is not CONFIRMED",
    )
    require(
        guidance.get("selectedBackendId") == "regelsuche-search",
        "guidance run selected the wrong backend",
    )
    require(
        formal.get("telemetryNotice") == EXPECTED_TELEMETRY_NOTICE,
        "formal telemetry/evidence boundary drift",
    )

    expected_manifest = {
        "allThreeRolesConfigured": True,
        "formalMultiStage": True,
        "allFormalExecutionsRetained": True,
        "formalProofAuthorized": True,
        "searchGuidanceExecuted": True,
    }
    for field, expected in expected_manifest.items():
        require(manifest.get(field) is expected, f"manifest {field} drift")

    # Mutation checks prove that semantic decision fields are fail-closed even
    # if all content-hash fields still have the correct shape.
    report_validator = Draft202012Validator(report_schema)
    invalid_refutation = copy.deepcopy(formal)
    invalid_refutation["outcome"] = "REFUTED"
    invalid_refutation["proofAuthorized"] = False
    invalid_refutation["promotionBlocked"] = False
    require(
        bool(list(report_validator.iter_errors(invalid_refutation))),
        "report schema accepted an unauthorized refutation mutation",
    )

    invalid_conflict = copy.deepcopy(formal)
    invalid_conflict["outcome"] = "CONFLICT"
    invalid_conflict["proofAuthorized"] = False
    invalid_conflict["promotionBlocked"] = True
    invalid_conflict["conflictExecutionHashes"] = [
        attempt.get("executionHash")
        for attempt in formal_attempts
        if isinstance(attempt, dict) and attempt.get("executionHash")
    ]
    require(
        bool(list(report_validator.iter_errors(invalid_conflict))),
        "report schema accepted an unauthorized conflict mutation",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("regelsuche-solver-portfolio/build/reports/solver-portfolio"),
    )
    parser.add_argument(
        "--schemas", type=Path, default=Path("docs/schemas")
    )
    args = parser.parse_args()
    try:
        verify_semantics(args.root, args.schemas)
    except VerificationError as exc:
        print(f"solver portfolio verification failed: {exc}", file=sys.stderr)
        return 1
    print("solver-portfolio-contract=valid")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
