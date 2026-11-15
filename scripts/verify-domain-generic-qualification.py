#!/usr/bin/env python3
"""Validate retained domain-generic qualification evidence and claim boundaries."""

from __future__ import annotations

import copy
import json
import shutil
import tempfile
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

from domain_qualification_sources import verify_qualification

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-domain-generic-qualification-verification.sh"
    ) from error

ROOT = Path("regelsuche-release/build/reports/domain-generic-qualification")
SCHEMA_ROOT = Path("docs/schemas")
DOCUMENTATION = Path("docs/domain-generic-qualification.md")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_CLAIM = (
    "reproducible generation, bounded search, counterexample search, "
    "validation, certificate rendering and evidence across distinct "
    "mathematical object types"
)
EXPECTED_CHECKS = [
    "AT_LEAST_TWO_DISTINCT_DOMAINS",
    "BALANCED_RESOURCE_ACCOUNTING",
    "CONFIRMED_CANDIDATES_WITH_CERTIFICATES",
    "DISTINCT_MATHEMATICAL_STATE_TYPES",
    "EXPRESSION_REWRITE_DOMAIN_RETAINED",
    "EXTERNAL_NOVELTY_STATUS_NOT_EVALUATED",
    "NON_EXPRESSION_DOMAIN_RETAINED",
    "PROMOTION_STATUS_NOT_EVALUATED",
    "PROOF_STATUS_NOT_EVALUATED",
    "PUBLIC_EVIDENCE_STATUS_NOT_EVALUATED",
    "REPRESENTATION_FREE_LIFECYCLE_HANDOFF",
    "SHARED_RESOURCE_ACCOUNTING",
    "THREE_CLEAN_MULTI_DOMAIN_RUNS",
    "VERIFIED_EXPORT_SNAPSHOTS",
]
EXPECTED_RESOURCES = [
    "CANDIDATE_EVALUATIONS",
    "CERTIFICATE_ATTEMPTS",
    "COUNTEREXAMPLE_ATTEMPTS",
    "EXPLORED_STATES",
    "GENERATED_SUCCESSORS",
]
SCHEMA_FILES = {
    "catalog": "regelsuche-domain-generic-evidence-profile-catalog-v1.schema.json",
    "report": "regelsuche-domain-generic-discovery-qualification-v1.schema.json",
    "run": "regelsuche-domain-generic-discovery-qualification-run-v1.schema.json",
}


def fail(message: str) -> None:
    raise SystemExit(f"domain-generic qualification invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")


def require_nonempty(path: Path) -> None:
    require(path.is_file(), f"missing file: {path}")
    require(path.stat().st_size > 0, f"empty file: {path}")


def require_rejection(validator: Draft202012Validator, value: dict, label: str) -> None:
    require(bool(list(validator.iter_errors(value))), f"{label} unexpectedly passed schema validation")


def verify_source_rejection_controls(root, report, run):
    def rejects(directory, supplied_report, supplied_run, reason):
        try:
            verify_qualification(directory, supplied_report, supplied_run)
        except ValueError:
            return
        fail(f"accepted altered {reason}")

    with tempfile.TemporaryDirectory() as temporary:
        retained = Path(temporary) / "qualification"
        shutil.copytree(root, retained)
        # Byte drift replicated identically in every run must still fail the original byte hash.
        for index in range(1, 4):
            path = retained / f"runs/run-{index}/sequence/export-manifest.json"
            path.write_bytes(path.read_bytes() + b"\n")
        rejects(retained, report, run, "manifest originals")
    forged = copy.deepcopy(report)
    forged["domains"][0]["certificateHash"] = "sha256:" + "0" * 64
    rejects(root, forged, run, "domain row")
    forged = copy.deepcopy(report)
    forged["cleanRunCount"] += 1
    rejects(root, forged, run, "clean-run count")


def main() -> int:
    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    for name in ("profile-catalog", "qualification-report", "qualification-run"):
        require_nonempty(ROOT / f"{name}.json")
    for clean_run in (1, 2, 3):
        for directory_name in ("expression", "sequence"):
            directory = ROOT / "runs" / f"run-{clean_run}" / directory_name
            for name in ("domain", "evidence", "lifecycle-handoff", "export-manifest"):
                require_nonempty(directory / f"{name}.json")
            require_nonempty(
                ROOT / "runs" / f"run-{clean_run}" / "verification-receipts" / f"{directory_name}.json"
            )

    require_nonempty(DOCUMENTATION)
    documentation = DOCUMENTATION.read_text(encoding="utf-8")
    require("## Trust Boundary und Beweiswert" in documentation, "trust-boundary documentation missing")
    require("## Consumer-Prüfverfahren" in documentation, "consumer verification documentation missing")

    validators: dict[str, Draft202012Validator] = {}
    for name, file_name in SCHEMA_FILES.items():
        schema = load(SCHEMA_ROOT / file_name)
        Draft202012Validator.check_schema(schema)
        require(schema.get("additionalProperties") is False, f"{name} schema must fail closed")
        validators[name] = Draft202012Validator(schema)

    catalog_path = ROOT / "profile-catalog.json"
    report_path = ROOT / "qualification-report.json"
    run_path = ROOT / "qualification-run.json"
    catalog = load(catalog_path)
    report = load(report_path)
    run = load(run_path)
    validators["catalog"].validate(catalog)
    validators["report"].validate(report)
    validators["run"].validate(run)

    require(report.get("profile") == "DOMAIN_GENERIC_DISCOVERY", "profile identity drift")
    require(report.get("claim") == EXPECTED_CLAIM, "qualified claim drift")
    require(report.get("status") == "READY", "qualification status drift")
    require(report.get("domainGenericClaimAuthorized") is True, "domain-generic claim not authorized")
    require(report.get("autonomousCampaignClaimAuthorized") is False, "AUTONOMOUS_CAMPAIGN was broadened")
    require(report.get("cleanRunCount") == 3, "clean-run count drift")
    require(report.get("cleanRunsIdentical") is True, "clean runs are not identical")
    require(report.get("blockers") == [], "READY report has blockers")
    checks = report.get("checks", [])
    require([check.get("code") for check in checks] == EXPECTED_CHECKS, "qualification check set drift")
    require(all(check.get("passed") is True for check in checks), "required qualification check failed")

    domains = report.get("domains", [])
    require(
        [item.get("domainId") for item in domains]
        == ["expression-rewrite", "integer-sequence-finite-difference"],
        "qualified domain order or identity drift",
    )
    for domain in domains:
        require(domain.get("outcome") == "CONFIRMED", f"{domain.get('domainId')} outcome drift")
        require(str(domain.get("selectedCandidateHash", "")).startswith("sha256:"), "candidate hash missing")
        require(str(domain.get("certificateHash", "")).startswith("sha256:"), "certificate hash missing")
        require(domain.get("resourceNames") == EXPECTED_RESOURCES, "resource role set drift")
        require(domain.get("resourcesBalanced") is True, "resource accounting not balanced")
        require(domain.get("representationFreeHandoff") is True, "handoff is representation-bound")

    for status_name in (
        "proofStatus",
        "externalNoveltyStatus",
        "promotionStatus",
        "publicEvidenceStatus",
    ):
        require(report.get(status_name) == "NOT_EVALUATED", f"report {status_name} drift")
        require(run.get(status_name) == "NOT_EVALUATED", f"run {status_name} drift")
    require(run.get("domainGenericQualificationStatus") == "READY", "run status drift")
    require(run.get("domainGenericClaimAuthorized") is True, "run domain claim not authorized")
    require(run.get("autonomousCampaignClaimAuthorized") is False, "run broadened AUTONOMOUS_CAMPAIGN")

    verify_qualification(ROOT, report, run)
    verify_source_rejection_controls(ROOT, report, run)


    invalid_autonomy = copy.deepcopy(report)
    invalid_autonomy["autonomousCampaignClaimAuthorized"] = True
    require_rejection(validators["report"], invalid_autonomy, "AUTONOMOUS_CAMPAIGN broadening")
    invalid_proof = copy.deepcopy(report)
    invalid_proof["proofStatus"] = "CONFIRMED"
    require_rejection(validators["report"], invalid_proof, "proof-status inflation")
    invalid_ready = copy.deepcopy(report)
    invalid_ready["domains"] = invalid_ready["domains"][:1]
    require_rejection(validators["report"], invalid_ready, "READY report with one domain")
    invalid_claim = copy.deepcopy(report)
    invalid_claim["claim"] = "generic mathematical discovery"
    require_rejection(validators["report"], invalid_claim, "alternative qualified claim")
    invalid_check = copy.deepcopy(report)
    invalid_check["checks"][0]["passed"] = False
    require_rejection(validators["report"], invalid_check, "failed required check")
    invalid_check_code = copy.deepcopy(report)
    invalid_check_code["checks"][0]["code"] = "ALTERNATIVE_REQUIREMENT"
    require_rejection(validators["report"], invalid_check_code, "alternative requirement set")
    invalid_resources = copy.deepcopy(report)
    invalid_resources["domains"][0]["resourceNames"] = invalid_resources["domains"][0]["resourceNames"][:-1]
    require_rejection(validators["report"], invalid_resources, "incomplete resource roles")
    invalid_outcome = copy.deepcopy(report)
    invalid_outcome["domains"][1]["outcome"] = "REFUTED"
    require_rejection(validators["report"], invalid_outcome, "READY report with refuted domain")

    print(f"jsonschema={installed}")
    print("domain-generic-qualification-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
