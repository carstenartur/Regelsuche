#!/usr/bin/env python3
"""Generate and verify public capability status from retained Regelsuche evidence.

The generator intentionally reports only bounded claims. It consumes canonical release
and domain-generic qualification artifacts, verifies their cross-document roots, and
uses source-contract hashes only for IMPLEMENTED plugin capabilities. It never turns
implementation presence into a qualification, novelty, proof, promotion, or public-
evidence decision.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

SCHEMA = "regelsuche.capability-status/v1"
POLICY = "EVIDENCE_DERIVED_FAIL_CLOSED"
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
REVISION_RE = re.compile(r"^(?:WORKTREE|[0-9a-f]{40})$")
ALLOWED_STATUSES = {
    "IMPLEMENTED",
    "QUALIFIED",
    "EXPERIMENTAL",
    "BLOCKED",
    "NOT_EVALUATED",
    "OUT_OF_SCOPE",
}

README_PATH = Path("README.md")
DISCOVERY_STATUS_PATH = Path("docs/discovery-status.md")
DOC_INDEX_PATH = Path("docs/README.md")
MARKER_START = "<!-- capability-status:start -->"
MARKER_END = "<!-- capability-status:end -->"

STALE_OR_PROHIBITED_PHRASES = (
    "forms a proof of the complete transformation",
    "Signaturmetadaten werden derzeit noch nicht kryptografisch verifiziert",
)

PLUGIN_CONTRACTS: dict[str, tuple[str, tuple[str, ...]]] = {
    "PLUGIN_ARTIFACT_TRUST": (
        "Local detached-signature verification, publisher trust policy and verified-byte staging are implemented.",
        (
            "app/src/main/java/de/regelsuche/plugin/PluginArtifactVerifier.java",
            "app/src/main/java/de/regelsuche/plugin/TrustedPluginRuntime.java",
            "docs/schemas/regelsuche-plugin-artifact-verification-v1.schema.json",
            ".github/workflows/plugin-artifact-trust.yml",
        ),
    ),
    "PLUGIN_INDEX_AUTHENTICATION": (
        "Immutable local index revisions can be authenticated through curator Ed25519 signatures.",
        (
            "app/src/main/java/de/regelsuche/plugin/PluginArtifactIndexVerifier.java",
            "docs/schemas/regelsuche-plugin-artifact-index-signature-v1.schema.json",
            "docs/schemas/regelsuche-plugin-artifact-index-verification-v1.schema.json",
            ".github/workflows/plugin-artifact-index.yml",
        ),
    ),
    "PLUGIN_TRUST_STATE_REVISIONS": (
        "Hash-chained trust-state revisions implement pinned-authority, replay, gap and fork checks.",
        (
            "app/src/main/java/de/regelsuche/plugin/PluginTrustStoreRevisionVerifier.java",
            "docs/schemas/regelsuche-plugin-trust-store-revision-v1.schema.json",
            "docs/schemas/regelsuche-plugin-trust-store-chain-checkpoint-v1.schema.json",
            "docs/schemas/regelsuche-plugin-trust-store-revision-verification-v1.schema.json",
            ".github/workflows/plugin-trust-store-revision.yml",
        ),
    ),
}


class ContractError(ValueError):
    """Raised when retained evidence or documentation violates the public contract."""


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON field: {key}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ContractError(f"cannot read JSON artifact {path}: {exc}") from exc
    try:
        value = json.loads(text, object_pairs_hook=_reject_duplicate_pairs)
    except (json.JSONDecodeError, ContractError) as exc:
        raise ContractError(f"invalid strict JSON in {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ContractError(f"JSON root must be an object: {path}")
    return value


def require_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ContractError(f"{field} must be non-blank text")
    return value


def require_sha(value: Any, field: str) -> str:
    text = require_text(value, field)
    if not SHA256_RE.fullmatch(text):
        raise ContractError(f"{field} must be sha256:<64 lowercase hex>")
    return text


def require_bool(value: Any, field: str) -> bool:
    if not isinstance(value, bool):
        raise ContractError(f"{field} must be boolean")
    return value


def sha256_bytes(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def file_hash(root: Path, relative: str) -> str:
    path = root / relative
    if not path.is_file():
        raise ContractError(f"required implementation contract is missing: {relative}")
    return sha256_bytes(path.read_bytes())


def profile_map(report: dict[str, Any]) -> dict[str, dict[str, Any]]:
    profiles = report.get("profiles")
    if not isinstance(profiles, list) or not profiles:
        raise ContractError("release report profiles must be a non-empty array")
    result: dict[str, dict[str, Any]] = {}
    for item in profiles:
        if not isinstance(item, dict):
            raise ContractError("release profile entry must be an object")
        name = require_text(item.get("profile"), "profile")
        if name in result:
            raise ContractError(f"duplicate release profile: {name}")
        result[name] = item
    return result


def profile_capability(name: str, profile: dict[str, Any]) -> dict[str, Any]:
    raw_status = require_text(profile.get("status"), f"{name}.status")
    if raw_status not in {"READY", "BLOCKED"}:
        raise ContractError(f"unsupported release profile status: {name}={raw_status}")
    blockers = profile.get("blockers")
    if not isinstance(blockers, list) or any(not isinstance(x, str) or not x for x in blockers):
        raise ContractError(f"{name}.blockers must be an array of identifiers")
    status = "QUALIFIED" if raw_status == "READY" else "BLOCKED"
    if (status == "QUALIFIED") != (not blockers):
        raise ContractError(f"{name} status/blocker invariant is inconsistent")
    return {
        "capability": name,
        "status": status,
        "claim": require_text(profile.get("claim"), f"{name}.claim"),
        "evidenceRoots": [],
        "blockers": sorted(set(blockers)),
        "notes": [
            "Status is derived from regelsuche.release-readiness-matrix/v1.",
        ],
    }


def implementation_capabilities(root: Path) -> list[dict[str, Any]]:
    capabilities: list[dict[str, Any]] = []
    for name, (claim, paths) in sorted(PLUGIN_CONTRACTS.items()):
        roots = [file_hash(root, path) for path in paths]
        capabilities.append(
            {
                "capability": name,
                "status": "IMPLEMENTED",
                "claim": claim,
                "evidenceRoots": sorted(roots),
                "blockers": [],
                "notes": [
                    "IMPLEMENTED is a source-contract claim, not a public distribution or installation qualification.",
                    "Required source, schema and dedicated workflow files are hash-bound by this status artifact.",
                ],
            }
        )
    return capabilities


def derive_status(
    root: Path,
    release_report: dict[str, Any],
    release_run: dict[str, Any],
    domain_report: dict[str, Any],
    domain_run: dict[str, Any],
    repository_revision: str,
) -> dict[str, Any]:
    if not REVISION_RE.fullmatch(repository_revision):
        raise ContractError("repositoryRevision must be WORKTREE or a 40-character lowercase commit SHA")

    if release_report.get("schema") != "regelsuche.release-readiness-matrix/v1":
        raise ContractError("unsupported release readiness report schema")
    if release_run.get("schema") != "regelsuche.release-readiness-run/v1":
        raise ContractError("unsupported release readiness run schema")
    release_report_hash = require_sha(release_report.get("contentHash"), "releaseReport.contentHash")
    if require_sha(release_run.get("matrixHash"), "releaseRun.matrixHash") != release_report_hash:
        raise ContractError("release run does not bind the supplied release matrix")
    release_run_hash = require_sha(release_run.get("contentHash"), "releaseRun.contentHash")

    if domain_report.get("schema") != "regelsuche.domain-generic-discovery-qualification/v1":
        raise ContractError("unsupported domain-generic qualification report schema")
    if domain_run.get("schema") != "regelsuche.domain-generic-discovery-qualification-run/v1":
        raise ContractError("unsupported domain-generic qualification run schema")
    domain_report_hash = require_sha(domain_report.get("contentHash"), "domainReport.contentHash")
    if require_sha(domain_run.get("qualificationReportHash"), "domainRun.qualificationReportHash") != domain_report_hash:
        raise ContractError("domain run does not bind the supplied qualification report")
    domain_run_hash = require_sha(domain_run.get("contentHash"), "domainRun.contentHash")

    profiles = profile_map(release_report)
    required_profiles = {
        "SEARCH_REPRODUCIBILITY",
        "HIDDEN_RULE_REDISCOVERY",
        "OPEN_TARGET_DISCOVERY",
        "AUTONOMOUS_CAMPAIGN",
        "EXTERNAL_NOVELTY_REVIEW",
    }
    if set(profiles) != required_profiles:
        raise ContractError(f"unexpected release profile set: {sorted(profiles)}")

    capabilities = [profile_capability(name, profiles[name]) for name in sorted(profiles)]
    for capability in capabilities:
        capability["evidenceRoots"] = [release_report_hash, release_run_hash]

    autonomy = next(item for item in capabilities if item["capability"] == "AUTONOMOUS_CAMPAIGN")
    autonomy_authorized = require_bool(release_report.get("autonomyClaimAuthorized"), "autonomyClaimAuthorized")
    if autonomy_authorized != (autonomy["status"] == "QUALIFIED"):
        raise ContractError("autonomy authorization disagrees with AUTONOMOUS_CAMPAIGN status")

    domain_ready = domain_report.get("status") == "READY"
    domain_authorized = require_bool(
        domain_report.get("domainGenericClaimAuthorized"),
        "domainGenericClaimAuthorized",
    )
    if domain_authorized != domain_ready:
        raise ContractError("domain-generic status/authorization invariant is inconsistent")
    if require_bool(
        domain_report.get("autonomousCampaignClaimAuthorized"),
        "domain.autonomousCampaignClaimAuthorized",
    ):
        raise ContractError("domain-generic qualification must not authorize AUTONOMOUS_CAMPAIGN")
    domain_blockers = domain_report.get("blockers")
    if not isinstance(domain_blockers, list):
        raise ContractError("domain blockers must be an array")
    capabilities.append(
        {
            "capability": "DOMAIN_GENERIC_DISCOVERY",
            "status": "QUALIFIED" if domain_ready else "BLOCKED",
            "claim": require_text(domain_report.get("claim"), "domain.claim"),
            "evidenceRoots": [domain_report_hash, domain_run_hash],
            "blockers": sorted(set(str(x) for x in domain_blockers)),
            "notes": [
                "This separate profile does not broaden AUTONOMOUS_CAMPAIGN.",
                "Formal proof, external novelty, promotion and public evidence remain NOT_EVALUATED.",
            ],
        }
    )

    capabilities.extend(implementation_capabilities(root))
    capabilities.extend(
        [
            {
                "capability": "FORMAL_PROOF_OF_RETAINED_CANDIDATE",
                "status": "NOT_EVALUATED",
                "claim": "A formal theorem-prover proof of the retained production candidate.",
                "evidenceRoots": [release_report_hash, release_run_hash],
                "blockers": ["FORMAL_PROOF_NOT_RETAINED"],
                "notes": [
                    "The qualified release retains symbolic verification; that must not be relabeled as formal proof.",
                ],
            },
            {
                "capability": "PUBLIC_PLUGIN_DISTRIBUTION",
                "status": "BLOCKED",
                "claim": "Hosted discovery, authenticated transport, download, installation, update, removal and rollback of published extensions.",
                "evidenceRoots": [],
                "blockers": [
                    "HOSTED_OR_FEDERATED_INDEX_NOT_BOUND",
                    "DOWNLOAD_INSTALL_UPDATE_REMOVE_ROLLBACK_NOT_BOUND",
                    "PUBLIC_PUBLISHING_AND_INCIDENT_PROCESS_NOT_BOUND",
                ],
                "notes": [
                    "Local cryptographic verification is implemented, but the end-to-end public ecosystem tracked in issue #104 is incomplete.",
                ],
            },
            {
                "capability": "PROMOTION",
                "status": require_text(release_report.get("promotionStatus"), "promotionStatus"),
                "claim": "Promotion of the retained production candidate into active reusable knowledge.",
                "evidenceRoots": [release_report_hash, release_run_hash],
                "blockers": ["PROMOTION_GATE_NOT_EXECUTED"],
                "notes": ["Release qualification does not itself activate or promote the candidate."],
            },
            {
                "capability": "PUBLIC_EVIDENCE",
                "status": require_text(release_report.get("publicEvidenceStatus"), "publicEvidenceStatus"),
                "claim": "Publication-authorized public evidence for the retained production candidate.",
                "evidenceRoots": [release_report_hash, release_run_hash],
                "blockers": ["PUBLIC_EVIDENCE_GATE_NOT_EXECUTED"],
                "notes": ["A qualified internal autonomy claim is not a public novelty claim."],
            },
        ]
    )

    for capability in capabilities:
        status = capability["status"]
        if status not in ALLOWED_STATUSES:
            raise ContractError(f"unsupported public status {status!r} for {capability['capability']}")
        if status in {"QUALIFIED", "IMPLEMENTED"} and capability["blockers"]:
            raise ContractError(f"successful status carries blockers: {capability['capability']}")
        capability["evidenceRoots"] = sorted(set(capability["evidenceRoots"]))
        capability["blockers"] = sorted(set(capability["blockers"]))
        capability["notes"] = list(dict.fromkeys(capability["notes"]))

    capabilities.sort(key=lambda item: item["capability"])
    payload: dict[str, Any] = {
        "schema": SCHEMA,
        "policy": POLICY,
        "repositoryRevision": repository_revision,
        "sourceEvidence": {
            "releaseReadinessReportHash": release_report_hash,
            "releaseReadinessRunHash": release_run_hash,
            "domainGenericQualificationReportHash": domain_report_hash,
            "domainGenericQualificationRunHash": domain_run_hash,
        },
        "capabilities": capabilities,
    }
    payload["contentHash"] = sha256_bytes(canonical_bytes(payload))
    return payload


def render_markdown(status: dict[str, Any]) -> str:
    source = status["sourceEvidence"]
    lines = [
        "# Generated capability and claim status",
        "",
        "> This file is generated from retained canonical evidence and hash-bound implementation contracts.",
        "> It reports only the bounded claim named in each row.",
        "",
        f"- Policy: `{status['policy']}`",
        f"- Repository revision mode: `{status['repositoryRevision']}`",
        f"- Status content hash: `{status['contentHash']}`",
        f"- Release run: `{source['releaseReadinessRunHash']}`",
        f"- Domain-generic run: `{source['domainGenericQualificationRunHash']}`",
        "",
        "| Capability | Status | Bounded claim |",
        "|---|---|---|",
    ]
    for item in status["capabilities"]:
        claim = item["claim"].replace("|", "\\|").replace("\n", " ")
        lines.append(f"| `{item['capability']}` | `{item['status']}` | {claim} |")
    lines.extend(
        [
            "",
            "## Interpretation",
            "",
            "- `IMPLEMENTED` means that the named software contracts, schemas and dedicated workflow are present and hash-bound; it is not a qualification of a wider service.",
            "- `QUALIFIED` means that the named evidence profile is ready for exactly its recorded claim.",
            "- `BLOCKED` and `NOT_EVALUATED` remain visible and must not be paraphrased as success.",
            "- Project novelty, external novelty, symbolic validation, formal proof, promotion and Public Evidence remain distinct.",
            "",
        ]
    )
    return "\n".join(lines)


def render_embedded(status: dict[str, Any], heading: str, full_status_link: str) -> str:
    wanted = {
        "AUTONOMOUS_CAMPAIGN",
        "DOMAIN_GENERIC_DISCOVERY",
        "EXTERNAL_NOVELTY_REVIEW",
        "PLUGIN_ARTIFACT_TRUST",
        "PLUGIN_INDEX_AUTHENTICATION",
        "PLUGIN_TRUST_STATE_REVISIONS",
        "PUBLIC_PLUGIN_DISTRIBUTION",
        "FORMAL_PROOF_OF_RETAINED_CANDIDATE",
        "PROMOTION",
        "PUBLIC_EVIDENCE",
    }
    rows = [item for item in status["capabilities"] if item["capability"] in wanted]
    lines = [
        MARKER_START,
        heading,
        "",
        f"Die folgende Kurzmatrix wird aus den kanonischen Release-, Domain- und Trust-Verträgen erzeugt. Die vollständige Matrix mit Evidence-Roots steht in [`capability-status.md`]({full_status_link}).",
        "",
        "| Capability | Status |",
        "|---|---|",
    ]
    for item in rows:
        lines.append(f"| `{item['capability']}` | `{item['status']}` |")
    lines.extend(
        [
            "",
            "`QUALIFIED` autorisiert nur den jeweils benannten Claim. Externe mathematische Neuheit, formaler Beweis, Promotion und Public Evidence werden nicht aus einem anderen erfolgreichen Profil abgeleitet.",
            MARKER_END,
        ]
    )
    return "\n".join(lines)


def replace_marker_block(text: str, block: str, insertion_marker: str) -> str:
    if MARKER_START in text or MARKER_END in text:
        if text.count(MARKER_START) != 1 or text.count(MARKER_END) != 1:
            raise ContractError("capability status markers must occur exactly once")
        start = text.index(MARKER_START)
        end = text.index(MARKER_END, start) + len(MARKER_END)
        return text[:start] + block + text[end:]
    if insertion_marker not in text:
        raise ContractError(f"documentation insertion marker not found: {insertion_marker}")
    return text.replace(insertion_marker, block + "\n\n" + insertion_marker, 1)


def rewrite_docs(root: Path, status: dict[str, Any]) -> None:
    readme_path = root / README_PATH
    discovery_path = root / DISCOVERY_STATUS_PATH
    readme = readme_path.read_text(encoding="utf-8")
    discovery = discovery_path.read_text(encoding="utf-8")
    readme = replace_marker_block(
        readme,
        render_embedded(status, "## Verifizierter Capability- und Claim-Status", "docs/generated/capability-status.md"),
        "## Discovery evidence",
    )
    discovery = replace_marker_block(
        discovery,
        render_embedded(status, "## Maschinengebundener Capability-Status", "generated/capability-status.md"),
        "## Kurzfassung",
    )
    readme_path.write_text(readme, encoding="utf-8")
    discovery_path.write_text(discovery, encoding="utf-8")


def check_relative_markdown_links(root: Path, paths: Iterable[Path]) -> None:
    link_re = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
    missing: list[str] = []
    for relative in paths:
        path = root / relative
        text = path.read_text(encoding="utf-8")
        for target in link_re.findall(text):
            target = target.strip()
            if not target or target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            target = target.split("#", 1)[0]
            resolved = (path.parent / target).resolve()
            try:
                resolved.relative_to(root.resolve())
            except ValueError:
                missing.append(f"{relative}: link escapes repository: {target}")
                continue
            if not resolved.exists():
                missing.append(f"{relative}: missing link target: {target}")
    if missing:
        raise ContractError("broken local documentation links:\n" + "\n".join(missing))


def check_docs(root: Path, status: dict[str, Any]) -> None:
    expected_readme = render_embedded(status, "## Verifizierter Capability- und Claim-Status", "docs/generated/capability-status.md")
    expected_discovery = render_embedded(status, "## Maschinengebundener Capability-Status", "generated/capability-status.md")
    for relative, expected in (
        (README_PATH, expected_readme),
        (DISCOVERY_STATUS_PATH, expected_discovery),
    ):
        text = (root / relative).read_text(encoding="utf-8")
        if expected not in text:
            raise ContractError(f"generated capability block is stale in {relative}")
        for phrase in STALE_OR_PROHIBITED_PHRASES:
            if phrase in text:
                raise ContractError(f"prohibited stale claim in {relative}: {phrase}")
    index = (root / DOC_INDEX_PATH).read_text(encoding="utf-8")
    required_links = (
        "capability-status.md",
        "generated/capability-status.md",
        "schemas/regelsuche-capability-status-v1.schema.json",
    )
    for link in required_links:
        if link not in index:
            raise ContractError(f"documentation index is missing link: {link}")
    check_relative_markdown_links(
        root,
        (README_PATH, DISCOVERY_STATUS_PATH, DOC_INDEX_PATH, Path("docs/capability-status.md"), Path("docs/generated/capability-status.md")),
    )


def write_or_check(path: Path, content: str, check: bool) -> None:
    encoded = content.encode("utf-8")
    if check:
        try:
            current = path.read_bytes()
        except OSError as exc:
            raise ContractError(f"missing generated output {path}: {exc}") from exc
        if current != encoded:
            raise ContractError(f"generated output differs: {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--release-report", type=Path, required=True)
    parser.add_argument("--release-run", type=Path, required=True)
    parser.add_argument("--domain-report", type=Path, required=True)
    parser.add_argument("--domain-run", type=Path, required=True)
    parser.add_argument("--repository-revision", default="WORKTREE")
    parser.add_argument("--json-output", type=Path, required=True)
    parser.add_argument("--markdown-output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--rewrite-docs", action="store_true")
    parser.add_argument("--check-docs", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    root = args.repository_root.resolve()
    try:
        status = derive_status(
            root,
            load_json(args.release_report),
            load_json(args.release_run),
            load_json(args.domain_report),
            load_json(args.domain_run),
            args.repository_revision,
        )
        json_text = json.dumps(status, ensure_ascii=False, indent=2) + "\n"
        markdown = render_markdown(status)
        write_or_check(args.json_output, json_text, args.check)
        write_or_check(args.markdown_output, markdown, args.check)
        if args.rewrite_docs:
            rewrite_docs(root, status)
        if args.check_docs:
            check_docs(root, status)
    except ContractError as exc:
        print(f"capability status verification failed: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
