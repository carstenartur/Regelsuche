#!/usr/bin/env python3
"""Validate retained evidence for the generic discovery-domain adapters."""

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
        "jsonschema is required; run scripts/run-generic-discovery-domains-verification.sh"
    ) from error

ROOT = Path("regelsuche-discovery/build/reports/domain-discovery")
SCHEMA_ROOT = Path("docs/schemas")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_RESOURCES = {
    "EXPLORED_STATES",
    "GENERATED_SUCCESSORS",
    "CANDIDATE_EVALUATIONS",
    "COUNTEREXAMPLE_ATTEMPTS",
    "CERTIFICATE_ATTEMPTS",
}


def fail(message: str) -> None:
    raise SystemExit(f"generic discovery-domain evidence invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def require_rejection(validator: Draft202012Validator, value: dict, label: str) -> None:
    require(bool(list(validator.iter_errors(value))), f"{label} unexpectedly passed schema validation")


def validate_domain(
    name: str,
    descriptor_validator: Draft202012Validator,
    evidence_validator: Draft202012Validator,
) -> dict:
    directory = ROOT / name
    report = load(directory / "report.json")
    descriptor = load(directory / "domain.json")
    descriptor_validator.validate(descriptor)
    evidence_validator.validate(report)

    require(report.get("domain") == descriptor, f"{name}: embedded descriptor drift")
    require(
        report.get("domain", {}).get("domainId")
        == report.get("seed", {}).get("domainId"),
        f"{name}: descriptor/seed domain mismatch",
    )
    require(report.get("outcome") == "CONFIRMED", f"{name}: outcome drift")
    require(report.get("selectedCandidateHash") is not None, f"{name}: candidate hash missing")
    require(report.get("certificate") is not None, f"{name}: certificate missing")
    for status_name in (
        "proofStatus",
        "externalNoveltyStatus",
        "promotionStatus",
        "publicEvidenceStatus",
    ):
        require(
            report.get(status_name) == "NOT_EVALUATED",
            f"{name}: {status_name} drift",
        )

    summary = report.get("summary", {})
    states = report.get("states", [])
    transitions = report.get("transitions", [])
    attempts = report.get("candidateAttempts", [])
    require(summary.get("exploredStates") == len(states), f"{name}: explored-state summary drift")
    require(
        summary.get("generatedTransitions") == len(transitions),
        f"{name}: generated-transition summary drift",
    )
    require(
        summary.get("candidateAttempts") == len(attempts),
        f"{name}: candidate-attempt summary drift",
    )
    state_hashes = [state.get("stateHash") for state in states]
    require(len(state_hashes) == len(set(state_hashes)), f"{name}: duplicate state hash")

    resources = {
        line.get("resource"): line
        for line in report.get("resources", [])
        if isinstance(line, dict)
    }
    require(set(resources) == EXPECTED_RESOURCES, f"{name}: resource role set drift")
    for resource, line in resources.items():
        require(
            line.get("configured")
            == line.get("executed", 0)
            + line.get("skipped", 0)
            + line.get("remaining", 0),
            f"{name}: resource ledger does not balance for {resource}",
        )
    return report


def main() -> int:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    descriptor_schema = load(
        SCHEMA_ROOT / "regelsuche-discovery-domain-descriptor-v1.schema.json"
    )
    evidence_schema = load(
        SCHEMA_ROOT / "regelsuche-domain-discovery-evidence-v1.schema.json"
    )
    Draft202012Validator.check_schema(descriptor_schema)
    Draft202012Validator.check_schema(evidence_schema)
    descriptor_validator = Draft202012Validator(descriptor_schema)
    evidence_validator = Draft202012Validator(evidence_schema)

    expression = validate_domain("expression", descriptor_validator, evidence_validator)
    sequence = validate_domain("sequence", descriptor_validator, evidence_validator)

    require(
        expression.get("domain", {}).get("domainId") == "expression-rewrite",
        "expression domain identity drift",
    )
    require(
        expression.get("certificate", {}).get("kind")
        == "CANONICAL_EQUIVALENCE_TRACE",
        "expression certificate kind drift",
    )
    expression_properties = {
        item.get("key"): item.get("value")
        for item in expression.get("domainEvidence", {}).get("properties", [])
    }
    require(
        expression_properties.get("formalProofStatus") == "NOT_EVALUATED",
        "expression evidence inflated formal proof",
    )
    try:
        rule_count = int(expression_properties.get("ruleCount", "0"))
    except (TypeError, ValueError):
        fail("expression ruleCount is not an integer")
    require(rule_count >= 1, "expression evidence retained no rules")

    require(
        sequence.get("domain", {}).get("domainId")
        == "integer-sequence-finite-difference",
        "sequence domain identity drift",
    )
    require(
        sequence.get("certificate", {}).get("kind")
        == "FINITE_DIFFERENCE_WITNESS",
        "sequence certificate kind drift",
    )
    sequence_properties = {
        item.get("key"): item.get("value")
        for item in sequence.get("domainEvidence", {}).get("properties", [])
    }
    require(sequence_properties.get("differenceOrder") == "2", "difference-order drift")
    require(sequence_properties.get("holdoutTerms") == "[25,36]", "holdout-term drift")

    invalid_proof = copy.deepcopy(sequence)
    invalid_proof["proofStatus"] = "CONFIRMED"
    require_rejection(evidence_validator, invalid_proof, "proof inflation")

    invalid_descriptor = copy.deepcopy(sequence["domain"])
    invalid_descriptor["deterministic"] = False
    require_rejection(
        descriptor_validator,
        invalid_descriptor,
        "non-deterministic v1 domain",
    )

    print(f"jsonschema={installed}")
    print("generic-discovery-domain-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
