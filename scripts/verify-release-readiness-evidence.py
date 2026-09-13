#!/usr/bin/env python3
"""Validate one retained qualified release-readiness evidence root."""

from __future__ import annotations

import argparse
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

# The verifier is also invoked without -B by the existing Gradle Exec task.
# Loading its checkout-local binding helper must not dirty a clean checkout.
sys.dont_write_bytecode = True
from release_readiness_bindings import verify_root_bindings
from release_readiness_files import EvidenceFiles

try:
    from jsonschema import Draft202012Validator
    from jsonschema.exceptions import ValidationError
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-release-readiness-verification.sh"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
SCHEMAS = Path("docs/schemas")

ROOT_FILES = (
    "profiles.json",
    "evidence-summary.json",
    "hidden-rule-release-evidence.json",
    "release-readiness-report.json",
    "release-readiness-run.json",
)
CAMPAIGN_FILES = (
    "production-campaign-manifest.json",
    "campaign-resource-ledger.json",
    "feedback-reallocation.json",
    "proof-report.json",
    "solver-obligation.json",
    "solver-result.json",
    "production-lifecycle-run.json",
)
QUALIFICATION_FILES = (
    "qualification-suite.json",
    "qualification-split-audit.json",
    "qualification-evaluation.json",
    "qualification-utility.json",
    "candidate-qualification-evidence.json",
    "candidate-qualification-run.json",
)
SCHEMA_PAIRS = (
    (
        "regelsuche-release-evidence-profile-catalog-v1.schema.json",
        "profiles.json",
    ),
    (
        "regelsuche-autonomous-campaign-release-evidence-v1.schema.json",
        "evidence-summary.json",
    ),
    (
        "regelsuche-hidden-rule-release-evidence-v1.schema.json",
        "hidden-rule-release-evidence.json",
    ),
    (
        "regelsuche-autonomous-production-campaign-v2.schema.json",
        "campaign/production-campaign-manifest.json",
    ),
    (
        "regelsuche-autonomous-candidate-qualification-suite-v1.schema.json",
        "qualification/qualification-suite.json",
    ),
    (
        "regelsuche-autonomous-candidate-qualification-split-v1.schema.json",
        "qualification/qualification-split-audit.json",
    ),
    (
        "regelsuche-open-target-conjecture-evaluation-v1.schema.json",
        "qualification/qualification-evaluation.json",
    ),
    (
        "regelsuche-autonomous-candidate-qualified-utility-v1.schema.json",
        "qualification/qualification-utility.json",
    ),
    (
        "regelsuche-autonomous-candidate-qualification-v1.schema.json",
        "qualification/candidate-qualification-evidence.json",
    ),
    (
        "regelsuche-autonomous-candidate-qualification-run-v1.schema.json",
        "qualification/candidate-qualification-run.json",
    ),
    (
        "regelsuche-release-readiness-matrix-v1.schema.json",
        "release-readiness-report.json",
    ),
    (
        "regelsuche-release-readiness-run-v1.schema.json",
        "release-readiness-run.json",
    ),
    (
        "regelsuche-solver-obligation-v1.schema.json",
        "campaign/solver-obligation.json",
    ),
    (
        "regelsuche-solver-result-v1.schema.json",
        "campaign/solver-result.json",
    ),
    (
        "regelsuche-open-target-conjecture-proof-v2.schema.json",
        "campaign/proof-report.json",
    ),
    (
        "regelsuche-autonomous-production-lifecycle-v3.schema.json",
        "campaign/production-lifecycle-run.json",
    ),
)


def fail(message: str) -> None:
    raise SystemExit(f"release readiness evidence invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def parse_document(payload: bytes, label: str) -> dict:
    try:
        value = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=lambda constant: fail("invalid JSON numeric constant: " + constant),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        fail(f"cannot read {label}: {error}")
    require(isinstance(value, dict), f"JSON root must be an object: {label}")
    return value


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field: " + key)
        result[key] = value
    return result


def validate(root: Path) -> None:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )
    try:
        with EvidenceFiles(root) as files, EvidenceFiles(SCHEMAS) as schemas:
            validate_owned(files, schemas)
    except (OSError, ValueError, ValidationError) as error:
        fail(str(error))
    print(f"releaseReadinessRoot={root}")
    print(f"jsonschema={installed}")
    print("release-readiness-contract=valid")


def validate_owned(files: EvidenceFiles, schemas: EvidenceFiles) -> None:
    root = files.root

    def read_bytes(path):
        return files.read_bytes(path.relative_to(root))

    def load(path):
        return parse_document(read_bytes(path), str(path))

    def require_nonempty(path):
        require(bool(read_bytes(path)), f"empty file: {path}")

    for relative in ROOT_FILES:
        require_nonempty(root / relative)
    for relative in CAMPAIGN_FILES:
        require_nonempty(root / "campaign" / relative)
    for relative in QUALIFICATION_FILES:
        require_nonempty(root / "qualification" / relative)
    require(
        not files.present("campaign/proof-obligation.json"),
        "legacy proof-obligation.json must not be retained",
    )

    for schema_name, artifact_relative in SCHEMA_PAIRS:
        artifact_path = root / artifact_relative
        schema = parse_document(schemas.read_bytes(schema_name), schema_name)
        artifact = load(artifact_path)
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(artifact)

    verify_root_bindings(root, load, require, read_bytes)

    obligation = load(root / "campaign/solver-obligation.json")
    result = load(root / "campaign/solver-result.json")
    proof = load(root / "campaign/proof-report.json")
    lifecycle = load(root / "campaign/production-lifecycle-run.json")
    require(
        result.get("obligationHash") == obligation.get("contentHash"),
        "solver result obligation hash mismatch",
    )
    require(
        proof.get("solverObligationHash") == obligation.get("contentHash"),
        "proof obligation hash mismatch",
    )
    require(
        proof.get("solverResultHash") == result.get("contentHash"),
        "proof result hash mismatch",
    )
    require(
        lifecycle.get("solverObligationHash") == obligation.get("contentHash"),
        "lifecycle obligation hash mismatch",
    )
    require(
        lifecycle.get("solverResultHash") == result.get("contentHash"),
        "lifecycle result hash mismatch",
    )
    require(result.get("status") == "CONFIRMED", "solver result is not CONFIRMED")
    require(
        result.get("translationStatus") == "LOSSLESS",
        "solver result translation is not LOSSLESS",
    )

    matrix = load(root / "release-readiness-report.json")
    hidden = load(root / "hidden-rule-release-evidence.json")
    run = load(root / "release-readiness-run.json")
    qualification = load(
        root / "qualification/candidate-qualification-evidence.json"
    )
    split = load(root / "qualification/qualification-split-audit.json")
    utility = load(root / "qualification/qualification-utility.json")

    profiles = {item.get("profile"): item for item in matrix.get("profiles", [])}
    expected_profiles = {
        "HIDDEN_RULE_REDISCOVERY": "READY",
        "OPEN_TARGET_DISCOVERY": "READY",
        "AUTONOMOUS_CAMPAIGN": "READY",
        "EXTERNAL_NOVELTY_REVIEW": "BLOCKED",
    }
    for profile, expected_status in expected_profiles.items():
        require(profile in profiles, f"profile missing: {profile}")
        require(
            profiles[profile].get("status") == expected_status,
            f"profile {profile} status drift",
        )
    require(
        profiles["HIDDEN_RULE_REDISCOVERY"].get("blockers") == [],
        "hidden-rule profile has blockers",
    )
    require(
        profiles["AUTONOMOUS_CAMPAIGN"].get("blockers") == [],
        "autonomous campaign profile has blockers",
    )

    require((hidden.get("cases"), hidden.get("families")) == (20, 4), "hidden-rule corpus drift")
    require(hidden.get("configuredNegativeHoldouts") == 40, "configured hidden negatives drift")
    require(hidden.get("executedNegativeHoldouts") == 38, "executed hidden negatives drift")
    require(hidden.get("skippedNegativeHoldouts") == 2, "skipped hidden negatives drift")
    require(hidden.get("falsePositiveHoldouts") == 0, "hidden false positives detected")
    require(hidden.get("hiddenReferenceIsolated") is True, "hidden reference leakage")
    require(hidden.get("benchmarkComplete") is True, "hidden benchmark incomplete")
    require(
        hidden.get("executableRediscoveryRetained") is True,
        "executable rediscovery not retained",
    )

    require(split.get("passed") is True, "qualification split audit failed")
    require(
        split.get("heldOutFamilyOrClusterCount") == 1,
        "held-out cluster count drift",
    )
    require(split.get("upstreamCollisions") == [], "upstream split collisions")
    require(split.get("internalCollisions") == [], "internal split collisions")

    require(qualification.get("configuredPositiveHoldouts") == 12, "configured positives drift")
    require(qualification.get("executedPositiveHoldouts") == 12, "executed positives drift")
    require(qualification.get("configuredNegativeHoldouts") == 12, "configured negatives drift")
    require(qualification.get("executedNegativeHoldouts") == 12, "executed negatives drift")
    require(qualification.get("mandatorySkippedWorkCount") == 0, "mandatory qualification work skipped")
    require(qualification.get("refutingHoldouts") == 0, "refuting holdouts detected")
    require(qualification.get("counterexamplesFound") == 0, "qualification counterexamples detected")
    require(
        qualification.get("pairedHeldOutUtilityEvaluated") is True,
        "paired held-out utility not evaluated",
    )
    require(qualification.get("pairedUtilityPermille", 0) > 0, "paired utility is not positive")
    require(qualification.get("correctnessRegressionCount") == 0, "qualification correctness regression")
    require(qualification.get("qualified") is True, "candidate is not qualified")
    require(utility.get("beneficial") is True, "paired utility is not beneficial")
    require(utility.get("materialGainCount", 0) > 0, "no material utility gain")
    require(utility.get("correctnessRegressionCount") == 0, "utility correctness regression")

    require(
        matrix.get("hiddenRuleEvidenceHash") == hidden.get("evidenceHash"),
        "matrix hidden-rule hash mismatch",
    )
    require(run.get("hiddenRuleEvidenceStatus") == "BOUND", "hidden-rule evidence not bound")
    require(
        run.get("hiddenRuleEvidenceHash") == hidden.get("evidenceHash"),
        "run hidden-rule hash mismatch",
    )
    require(
        run.get("qualificationEvidenceStatus") == "BOUND",
        "qualification evidence not bound",
    )
    require(
        run.get("qualificationEvidenceHash") == qualification.get("contentHash"),
        "qualification evidence hash mismatch",
    )
    require(matrix.get("autonomousCampaignStatus") == "READY", "matrix autonomy status drift")
    require(matrix.get("autonomyClaimAuthorized") is True, "matrix autonomy claim not authorized")
    require(run.get("autonomousCampaignStatus") == "READY", "run autonomy status drift")
    require(run.get("autonomyClaimAuthorized") is True, "run autonomy claim not authorized")
    require(run.get("promotionStatus") == "NOT_EVALUATED", "promotion status drift")
    require(run.get("publicEvidenceStatus") == "NOT_EVALUATED", "public evidence status drift")

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("regelsuche-release/build/reports/release-readiness-qualified"),
    )
    args = parser.parse_args()
    validate(args.root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
