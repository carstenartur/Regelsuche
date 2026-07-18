#!/usr/bin/env python3
"""Validate the generated public capability and claim status contract."""

from __future__ import annotations

import hashlib
import json
import sys
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

try:
    from jsonschema import Draft202012Validator
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run scripts/run-capability-status-verification.sh"
    ) from error

SCHEMA_PATH = Path("docs/schemas/regelsuche-capability-status-v1.schema.json")
STATUS_PATH = Path("docs/generated/capability-status.json")
EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
EXPECTED_SCHEMA = "regelsuche.capability-status/v1"
EXPECTED_POLICY = "EVIDENCE_DERIVED_FAIL_CLOSED"
EXPECTED_REVISION = "WORKTREE"
EXPECTED_STATUSES = {
    "AUTONOMOUS_CAMPAIGN": "QUALIFIED",
    "DOMAIN_GENERIC_DISCOVERY": "QUALIFIED",
    "EXTERNAL_NOVELTY_REVIEW": "BLOCKED",
    "FORMAL_PROOF_OF_RETAINED_CANDIDATE": "NOT_EVALUATED",
    "HIDDEN_RULE_REDISCOVERY": "QUALIFIED",
    "OPEN_TARGET_DISCOVERY": "QUALIFIED",
    "PLUGIN_ARTIFACT_TRUST": "IMPLEMENTED",
    "PLUGIN_INDEX_AUTHENTICATION": "IMPLEMENTED",
    "PLUGIN_TRUST_STATE_REVISIONS": "IMPLEMENTED",
    "PROMOTION": "NOT_EVALUATED",
    "PUBLIC_EVIDENCE": "NOT_EVALUATED",
    "PUBLIC_PLUGIN_DISTRIBUTION": "BLOCKED",
    "SEARCH_REPRODUCIBILITY": "QUALIFIED",
}


def fail(message: str) -> None:
    raise SystemExit(f"capability status invalid: {message}")


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

    schema = load(SCHEMA_PATH)
    status = load(STATUS_PATH)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(status)

    require(status.get("schema") == EXPECTED_SCHEMA, "schema identity drift")
    require(status.get("policy") == EXPECTED_POLICY, "publication policy drift")
    require(
        status.get("repositoryRevision") == EXPECTED_REVISION,
        "checked documentation must remain bound to WORKTREE",
    )

    claimed_hash = status.get("contentHash")
    require(isinstance(claimed_hash, str), "contentHash is missing")
    semantic_status = dict(status)
    semantic_status.pop("contentHash", None)
    encoded = json.dumps(
        semantic_status,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    actual_hash = "sha256:" + hashlib.sha256(encoded).hexdigest()
    require(claimed_hash == actual_hash, "contentHash does not match canonical JSON")

    capabilities = status.get("capabilities")
    require(isinstance(capabilities, list), "capabilities must be an array")
    names = [item.get("capability") for item in capabilities if isinstance(item, dict)]
    require(len(names) == len(capabilities), "every capability must be an object")
    require(len(names) == len(set(names)), "duplicate capability entries")
    require(
        set(names) == set(EXPECTED_STATUSES),
        "capability set drift: "
        f"missing={sorted(set(EXPECTED_STATUSES) - set(names))} "
        f"unexpected={sorted(set(names) - set(EXPECTED_STATUSES))}",
    )
    require(names == sorted(names), "capabilities must remain canonically sorted")

    by_name = {item["capability"]: item for item in capabilities}
    for capability, expected_status in EXPECTED_STATUSES.items():
        item = by_name[capability]
        require(
            item.get("status") == expected_status,
            f"{capability} status drift: expected {expected_status}, found {item.get('status')}",
        )
        require(bool(item.get("claim")), f"{capability} has no claim text")
        roots = item.get("evidenceRoots")
        blockers = item.get("blockers")
        require(isinstance(roots, list), f"{capability} evidenceRoots is not an array")
        require(isinstance(blockers, list), f"{capability} blockers is not an array")
        if expected_status in {"QUALIFIED", "IMPLEMENTED"}:
            require(roots, f"{capability} has no evidence root")
            require(not blockers, f"{capability} unexpectedly has blockers")
        if expected_status == "BLOCKED":
            require(blockers, f"{capability} is BLOCKED without blockers")
        if expected_status == "NOT_EVALUATED":
            require(blockers, f"{capability} is NOT_EVALUATED without an explicit reason")

    source_evidence = status.get("sourceEvidence", {})
    expected_roots = {
        "releaseReadinessReportHash",
        "releaseReadinessRunHash",
        "domainGenericQualificationReportHash",
        "domainGenericQualificationRunHash",
    }
    require(set(source_evidence) == expected_roots, "source evidence root set drift")
    for name, digest in source_evidence.items():
        require(
            isinstance(digest, str)
            and digest.startswith("sha256:")
            and len(digest) == 71,
            f"invalid source evidence hash: {name}",
        )

    print(f"jsonschema={installed}")
    print(f"capabilityStatusHash={claimed_hash}")
    print(f"capabilityCount={len(capabilities)}")
    print("capability-status-contract=valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
