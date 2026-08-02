#!/usr/bin/env python3
"""Validate one retained comparative benchmark evidence root."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-comparative-benchmarks-verification.sh"
    ) from error

SCHEMA_PATH = Path("docs/schemas/regelsuche-comparative-benchmark-v1.schema.json")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_SCORE_POLICY = "NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY"
EXPECTED_VALIDATION_BACKENDS = {
    "polynomial-normal-form",
    "sympy-cas-equality",
    "z3-smt-proof",
}
EXPECTED_SIMPLIFICATION_BACKENDS = {
    "regelsuche-untargeted-best-first",
    "sympy-cas-simplifier",
}
EXPECTED_COVERAGE_GAPS = {
    "SIMPLIFICATION_COMPETITION",
    "HIDDEN_RULE_REDISCOVERY",
    "OPEN_TARGET_DISCOVERY",
    "CROSS_FAMILY_TRANSFER",
    "AUTONOMOUS_CAMPAIGN",
    "DISCOVERY_COMPONENT_ABLATION",
    "CONTROLLER_ABLATION",
}


def fail(message: str) -> None:
    raise SystemExit(f"comparative benchmark invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def indexed(items: list[dict], name: str) -> dict[str, dict]:
    hashes = [item.get("contentHash") for item in items]
    require(all(isinstance(value, str) and value for value in hashes), f"{name} item without contentHash")
    require(len(hashes) == len(set(hashes)), f"duplicate {name} contentHash")
    return {item["contentHash"]: item for item in items}


def load_all(directory: Path) -> list[dict]:
    require(directory.is_dir(), f"missing retained directory: {directory}")
    paths = sorted(directory.glob("*.json"))
    require(paths, f"no retained JSON objects under {directory}")
    return [load(path) for path in paths]


def configurations_by_hash_track(report: dict, result: dict) -> str:
    for configuration in report["configurations"]:
        if configuration.get("contentHash") == result.get("configurationHash"):
            return configuration.get("track")
    return ""


def validate(root: Path) -> None:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    schema = load(SCHEMA_PATH)
    report = load(root / "report.json")
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema)
    validator.validate(report)

    require(report.get("scorePolicy") == EXPECTED_SCORE_POLICY, "score policy drift")
    require(len(report.get("parityManifests", [])) == 3, "parity manifest count drift")
    require(len(report.get("configurations", [])) == 8, "configuration count drift")
    require(len(report.get("cases", [])) == 11, "case count drift")
    require(len(report.get("results", [])) == 27, "result count drift")
    require(len(report.get("claims", [])) == 3, "claim count drift")
    require(
        all(result.get("disposition") == "EXECUTED" for result in report["results"]),
        "not every result was executed",
    )

    # An incorrect result is retained evidence about a configured competitor and
    # must stay visible. What is forbidden is a claim that is stronger than the
    # results of its own track.
    for claim in report["claims"]:
        track_results = [
            result for result in report["results"] if result.get("track") == claim.get("track")
        ]
        require(track_results, f"claim without track evidence: {claim.get('id')}")
        derived = (
            "SUPPORTED"
            if all(result.get("correct") is True for result in track_results)
            else "NEGATIVE"
        )
        require(
            claim.get("status") == derived,
            f"claim {claim.get('id')} declares {claim.get('status')} but its track implies {derived}",
        )

    configurations = indexed(report["configurations"], "configuration")
    cases = indexed(report["cases"], "case")
    results = indexed(report["results"], "result")
    manifests = indexed(report["parityManifests"], "parity manifest")

    for configuration in report["configurations"]:
        parity_hash = configuration.get("parityManifestHash")
        require(parity_hash in manifests, "configuration references unknown parity manifest")
        require(
            manifests[parity_hash].get("track") == configuration.get("track"),
            "configuration/parity track mismatch",
        )

    validation_backends = {
        item.get("backendId")
        for item in report["configurations"]
        if item.get("track") == "EQUALITY_VALIDATION"
    }
    require(
        validation_backends == EXPECTED_VALIDATION_BACKENDS,
        f"equality backend set drift: {sorted(validation_backends)}",
    )

    search_results = [
        result
        for result in report["results"]
        if result.get("track") == "TARGET_DIRECTED_SEARCH"
    ]
    validation_results = [
        result
        for result in report["results"]
        if result.get("track") == "EQUALITY_VALIDATION"
    ]
    simplification_results = [
        result
        for result in report["results"]
        if result.get("track") == "SIMPLIFICATION_COMPETITION"
    ]
    require(len(search_results) == 9, "target-directed result count drift")
    require(len(validation_results) == 6, "equality-validation result count drift")
    require(
        len(simplification_results) == 12,
        "simplification-competition result count drift",
    )

    simplification_backends = {
        item.get("backendId")
        for item in report["configurations"]
        if item.get("track") == "SIMPLIFICATION_COMPETITION"
    }
    require(
        simplification_backends == EXPECTED_SIMPLIFICATION_BACKENDS,
        f"simplification competitor set drift: {sorted(simplification_backends)}",
    )

    # The head-to-head track is only informative when no competitor is handed
    # the reference simplest form.
    simplification_parity = [
        manifest
        for manifest in report["parityManifests"]
        if manifest.get("track") == "SIMPLIFICATION_COMPETITION"
    ]
    require(len(simplification_parity) == 1, "simplification parity manifest drift")
    require(
        simplification_parity[0].get("targetVisible") is False,
        "simplification competitors must not see the reference form",
    )
    require(
        simplification_parity[0].get("hiddenReferenceVisible") is False,
        "simplification competitors must not see a hidden reference",
    )
    require(
        all(
            result.get("configurationHash")
            and configurations_by_hash_track(report, result) == "SIMPLIFICATION_COMPETITION"
            for result in simplification_results
        ),
        "simplification result references a foreign track configuration",
    )

    for result in report["results"]:
        configuration_hash = result.get("configurationHash")
        case_hash = result.get("caseHash")
        require(configuration_hash in configurations, "result references unknown configuration")
        require(case_hash in cases, "result references unknown case")
        configuration = configurations[configuration_hash]
        case = cases[case_hash]
        require(
            result.get("track") == configuration.get("track") == case.get("track"),
            "result/configuration/case track mismatch",
        )
        resources = result.get("resources", {})
        require(
            resources.get("configuredWork")
            == resources.get("executedWork", 0)
            + resources.get("skippedWork", 0)
            + resources.get("remainingWork", 0),
            "resource ledger does not balance",
        )
        require(
            resources.get("completedMandatoryEvaluations", 0)
            <= resources.get("mandatoryEvaluations", 0),
            "completed mandatory evaluations exceed configured evaluations",
        )
        require("elapsedMillis" not in result, "elapsedMillis leaked into semantic result")
        require("durationMillis" not in result, "durationMillis leaked into semantic result")

    for claim in report["claims"]:
        evidence_hashes = claim.get("evidenceResultHashes", [])
        require(evidence_hashes, "claim has no evidence results")
        require(
            all(value in results for value in evidence_hashes),
            "claim references unknown result",
        )
        require(
            all(results[value].get("track") == claim.get("track") for value in evidence_hashes),
            "claim references cross-track evidence",
        )

    actual_gaps = {gap.get("track") for gap in report.get("coverageGaps", [])}
    require(actual_gaps == EXPECTED_COVERAGE_GAPS, "coverage gap set drift")

    formal_results: list[tuple[dict, dict]] = []
    for result in validation_results:
        configuration = configurations[result["configurationHash"]]
        if result.get("evidence", {}).get("proofStatus") == "FORMAL_CERTIFICATE_RETAINED":
            formal_results.append((configuration, result))
    require(formal_results, "no formal certificate retained")
    require(
        all(configuration.get("backendId") == "z3-smt-proof" for configuration, _ in formal_results),
        "formal certificate retained from a non-Z3 backend",
    )

    retained_pairs = (
        ("parity-manifests", report["parityManifests"]),
        ("configurations", report["configurations"]),
        ("cases", report["cases"]),
        ("results", report["results"]),
        ("claims", report["claims"]),
        ("coverage-gaps", report["coverageGaps"]),
    )
    for directory, expected in retained_pairs:
        require(load_all(root / directory) == expected, f"retained {directory} objects drift from report")

    invalid_score = copy.deepcopy(report)
    invalid_score["scorePolicy"] = "UNIVERSAL_LEADERBOARD"
    require(
        bool(list(validator.iter_errors(invalid_score))),
        "schema accepted a universal score policy",
    )

    invalid_result = copy.deepcopy(report)
    correct_index = next(
        index
        for index, result in enumerate(invalid_result["results"])
        if result.get("correct") is True
    )
    invalid_result["results"][correct_index]["disposition"] = "FILTERED_UNSUPPORTED"
    require(
        bool(list(validator.iter_errors(invalid_result))),
        "schema accepted a correct filtered result",
    )

    print(f"comparativeBenchmarkRoot={root}")
    print(f"jsonschema={installed}")
    print("comparative-benchmark-contract=valid")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    validate(args.root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
