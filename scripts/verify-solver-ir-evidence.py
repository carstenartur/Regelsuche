#!/usr/bin/env python3
"""Validate the retained Solver IR schemas, examples and backend outcomes."""

from __future__ import annotations

import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-solver-ir-verification.sh"
    ) from error

ROOT = Path("regelsuche-solver-ir/build/reports/solver-ir")
SCHEMAS = Path("docs/schemas")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"


def fail(message: str) -> None:
    raise SystemExit(f"solver IR evidence invalid: {message}")


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def main() -> int:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    obligation_schema = load(
        SCHEMAS / "regelsuche-solver-obligation-v1.schema.json"
    )
    result_schema = load(SCHEMAS / "regelsuche-solver-result-v1.schema.json")
    Draft202012Validator.check_schema(obligation_schema)
    Draft202012Validator.check_schema(result_schema)
    obligation_validator = Draft202012Validator(obligation_schema)
    result_validator = Draft202012Validator(result_schema)

    obligation_paths = (
        ROOT / "obligation.json",
        ROOT / "assumption-obligation.json",
    )
    result_paths = (
        ROOT / "regelsuche-search-result.json",
        ROOT / "polynomial-normal-form-result.json",
        ROOT / "unsupported-result.json",
    )
    for path in obligation_paths:
        obligation_validator.validate(load(path))
    for path in result_paths:
        result_validator.validate(load(path))

    obligation = load(ROOT / "obligation.json")
    search = load(ROOT / "regelsuche-search-result.json")
    polynomial = load(ROOT / "polynomial-normal-form-result.json")
    unsupported = load(ROOT / "unsupported-result.json")
    manifest = load(ROOT / "manifest.json")

    require(search.get("status") == "CONFIRMED", "search backend not confirmed")
    require(polynomial.get("status") == "CONFIRMED", "polynomial backend not confirmed")
    require(search.get("translationStatus") == "LOSSLESS", "search translation not lossless")
    require(
        polynomial.get("translationStatus") == "LOSSLESS",
        "polynomial translation not lossless",
    )
    require(
        search.get("obligationHash") == obligation.get("contentHash"),
        "search result obligation hash mismatch",
    )
    require(
        polynomial.get("obligationHash") == obligation.get("contentHash"),
        "polynomial result obligation hash mismatch",
    )
    require(
        polynomial.get("backendId") == "polynomial-normal-form",
        "unexpected polynomial backend",
    )
    require(
        polynomial.get("message") == "matching deterministic polynomial normal form",
        "unexpected polynomial result message",
    )
    require(
        "DETERMINISTIC_POLYNOMIAL_NORMAL_FORM"
        in polynomial.get("usedCapabilities", []),
        "polynomial capability missing",
    )
    require(unsupported.get("status") == "UNSUPPORTED", "unsupported case status drift")
    require(
        unsupported.get("translationStatus") == "REJECTED",
        "unsupported case translation status drift",
    )
    require(
        "ASSUMPTIONS_NOT_SUPPORTED" in unsupported.get("translationIssues", []),
        "unsupported assumption issue missing",
    )
    require(
        manifest.get("sameObligationSubmittedToBothBackends") is True,
        "backend information parity not retained",
    )
    require(
        manifest.get("unsupportedBeforeExecution") is True,
        "unsupported case was not rejected before execution",
    )

    print(f"jsonschema={installed}")
    print("solver-ir-schemas=valid")
    print("solver-ir-backend-outcomes=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
