#!/usr/bin/env python3
"""Validate retained solver-portfolio schemas, hashes, outcomes and negatives."""

from __future__ import annotations

import copy
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-solver-portfolio-verification.sh"
    ) from error

ROOT = Path("regelsuche-solver-portfolio/build/reports/solver-portfolio")
SCHEMAS = Path("docs/schemas")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"


def fail(message: str) -> None:
    raise SystemExit(f"solver portfolio evidence invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def main() -> int:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    report_schema = load(
        SCHEMAS / "regelsuche-solver-portfolio-report-v1.schema.json"
    )
    obligation_schema = load(
        SCHEMAS / "regelsuche-solver-obligation-v1.schema.json"
    )
    translation_schema = load(
        SCHEMAS / "regelsuche-solver-translation-v1.schema.json"
    )
    result_schema = load(SCHEMAS / "regelsuche-solver-result-v1.schema.json")
    execution_schema = load(
        SCHEMAS / "regelsuche-solver-execution-v1.schema.json"
    )
    for schema in (
        report_schema,
        obligation_schema,
        translation_schema,
        result_schema,
        execution_schema,
    ):
        Draft202012Validator.check_schema(schema)

    report_validator = Draft202012Validator(report_schema)
    obligation_validator = Draft202012Validator(obligation_schema)
    translation_validator = Draft202012Validator(translation_schema)
    result_validator = Draft202012Validator(result_schema)
    execution_validator = Draft202012Validator(execution_schema)

    def validate_run(name: str) -> dict:
        run_root = ROOT / name
        obligation = load(run_root / "obligation.json")
        request = load(run_root / "request.json")
        report = load(run_root / "report.json")
        obligation_validator.validate(obligation)
        report_validator.validate(report)

        require(
            request.get("obligationHash") == obligation.get("contentHash"),
            f"{name}: request obligation hash mismatch",
        )
        require(
            report.get("obligationHash") == obligation.get("contentHash"),
            f"{name}: report obligation hash mismatch",
        )
        require(
            report.get("requestHash") == request.get("contentHash"),
            f"{name}: report request hash mismatch",
        )

        traced_hashes = {
            attempt.get("executionHash")
            for attempt in report.get("attempts", [])
            if attempt.get("executionHash")
        }
        retained_hashes: set[str] = set()
        executions_root = run_root / "executions"
        try:
            execution_directories = sorted(
                path for path in executions_root.iterdir() if path.is_dir()
            )
        except OSError as error:
            fail(f"cannot enumerate {executions_root}: {error}")

        for attempt_root in execution_directories:
            translation = load(attempt_root / "translation.json")
            result = load(attempt_root / "result.json")
            execution = load(attempt_root / "execution.json")
            translation_validator.validate(translation)
            result_validator.validate(result)
            execution_validator.validate(execution)

            require(
                translation.get("obligationHash") == obligation.get("contentHash"),
                f"{name}: translation obligation hash mismatch",
            )
            require(
                result.get("obligationHash") == obligation.get("contentHash"),
                f"{name}: result obligation hash mismatch",
            )
            require(
                execution.get("obligationHash") == obligation.get("contentHash"),
                f"{name}: execution obligation hash mismatch",
            )
            require(
                execution.get("translationHash") == translation.get("contentHash"),
                f"{name}: execution translation hash mismatch",
            )
            require(
                execution.get("resultHash") == result.get("contentHash"),
                f"{name}: execution result hash mismatch",
            )
            require(
                execution.get("backendId")
                == translation.get("backendId")
                == result.get("backendId"),
                f"{name}: backend identity mismatch",
            )
            require(
                execution.get("backendVersion")
                == translation.get("backendVersion")
                == result.get("backendVersion"),
                f"{name}: backend version mismatch",
            )
            require(
                execution.get("translationStatus")
                == translation.get("status")
                == result.get("translationStatus"),
                f"{name}: translation status mismatch",
            )
            require(
                execution.get("resultStatus") == result.get("status"),
                f"{name}: result status mismatch",
            )
            retained_hashes.add(execution.get("contentHash"))

        require(retained_hashes == traced_hashes, f"{name}: retained/traced execution drift")
        require(
            len(execution_directories) == len(retained_hashes),
            f"{name}: duplicate or unretained execution directory",
        )
        selected = report.get("selectedExecutionHash")
        if selected:
            require(selected in retained_hashes, f"{name}: selected execution not retained")
        return report

    formal = validate_run("formal")
    guidance = validate_run("guidance")
    manifest = load(ROOT / "manifest.json")

    require(formal.get("outcome") == "CONFIRMED", "formal outcome drift")
    require(formal.get("proofAuthorized") is True, "formal proof not authorized")
    require(formal.get("selectedBackendId") == "z3-smt-proof", "Z3 not selected")
    executed = [
        attempt.get("backendId")
        for attempt in formal.get("attempts", [])
        if attempt.get("disposition") == "EXECUTED"
    ]
    require("polynomial-normal-form" in executed, "polynomial stage not executed")
    require("z3-smt-proof" in executed, "Z3 stage not executed")
    require(guidance.get("outcome") == "CONFIRMED", "guidance outcome drift")
    require(
        guidance.get("selectedBackendId") == "regelsuche-search",
        "search guidance backend not selected",
    )
    require(
        formal.get("telemetryNotice")
        == "EXECUTION_TELEMETRY_NOT_MATHEMATICAL_EVIDENCE",
        "telemetry claim boundary drift",
    )
    require(manifest.get("allThreeRolesConfigured") is True, "role configuration drift")
    require(manifest.get("formalMultiStage") is True, "formal multistage flag missing")
    require(
        manifest.get("allFormalExecutionsRetained") is True,
        "formal execution retention incomplete",
    )
    require(manifest.get("formalProofAuthorized") is True, "manifest proof flag drift")
    require(
        manifest.get("searchGuidanceExecuted") is True,
        "search guidance was not executed",
    )

    invalid_refutation = copy.deepcopy(formal)
    invalid_refutation["outcome"] = "REFUTED"
    invalid_refutation["proofAuthorized"] = False
    invalid_refutation["promotionBlocked"] = False
    require(
        bool(list(report_validator.iter_errors(invalid_refutation))),
        "schema accepted an invalid non-blocking refutation",
    )

    invalid_conflict = copy.deepcopy(formal)
    invalid_conflict["outcome"] = "CONFLICT"
    invalid_conflict["proofAuthorized"] = False
    invalid_conflict["promotionBlocked"] = True
    invalid_conflict["conflictExecutionHashes"] = [
        attempt.get("executionHash")
        for attempt in formal.get("attempts", [])
        if attempt.get("executionHash")
    ]
    require(
        bool(list(report_validator.iter_errors(invalid_conflict))),
        "schema accepted an invalid conflict retaining every execution",
    )

    print(f"jsonschema={installed}")
    print("solver-portfolio-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
