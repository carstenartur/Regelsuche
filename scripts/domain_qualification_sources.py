"""Bind release composition to verified domain export, manifest and receipt bytes."""
import hashlib
import json
from pathlib import Path


FILES = {"DOMAIN_DESCRIPTOR": "domain.json", "DISCOVERY_EVIDENCE": "evidence.json",
         "LIFECYCLE_HANDOFF": "lifecycle-handoff.json"}
DOMAINS = {"expression": "expression-rewrite", "sequence": "integer-sequence-finite-difference"}
SCIENTIFIC_STATUS = ("proofStatus", "externalNoveltyStatus", "promotionStatus", "publicEvidenceStatus")


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def check(condition, description):
    if not condition:
        raise ValueError(f"domain qualification source mismatch: {description}")


def original(path):
    check(path.is_file() and not path.is_symlink(), path.name)
    return path.read_bytes()


def source_binding(directory, name, domain_id):
    source = directory / name
    check(not source.is_symlink() and {p.name for p in source.iterdir()} == {*FILES.values(), "export-manifest.json"},
          "export members")
    raw_manifest = original(source / "export-manifest.json")
    manifest = json.loads(raw_manifest)
    receipt_bytes = original(directory / "verification-receipts" / (name + ".json"))
    receipt = json.loads(receipt_bytes)
    check(manifest["domainId"] == domain_id and receipt["domainId"] == domain_id, "domain identity")
    check(receipt["manifestContentHash"] == manifest["contentHash"] and
          receipt["manifestByteHash"] == digest(raw_manifest), "manifest identity and original bytes")
    for key in ("campaignId", "domainRevision", "domainDescriptorHash", "discoveryEvidenceHash", "lifecycleHandoffHash"):
        check(receipt[key] == manifest[key], key)
    check(receipt["schema"] == "regelsuche.domain-discovery-export-verification/v1"
          and receipt["identityBindingStatus"] == "VERIFIED" and receipt["mathematicalValidationStatus"] == "NOT_EVALUATED",
          "source verification boundary")
    artifacts = {a["role"]: a for a in manifest["artifacts"]}
    check(len(artifacts) == len(manifest["artifacts"]) == receipt["verifiedArtifactCount"] == len(FILES)
          and set(artifacts) == set(FILES), "complete artifact roles")
    records = []
    payloads = {}
    for role, filename in FILES.items():
        artifact = artifacts[role]
        check(artifact["fileName"] == filename, "artifact name")
        raw = original(source / filename)
        check(artifact["byteHash"] == digest(raw) and artifact["byteLength"] == len(raw), filename)
        payloads[role] = json.loads(raw)
        check(payloads[role]["contentHash"] == artifact["sourceContentHash"], "artifact semantic identity")
        records.append(f"{role}|{artifact['byteHash']}|{len(raw)}")
    material = "|".join(f"{len(row)}:{row}" for row in records)
    check(receipt["artifactSetHash"] == digest(material.encode()), "verified artifact set")
    material = "|".join(f"{len(key)}:{key}={len(str(v))}:{v}" for key, v in sorted(receipt.items()) if key != "contentHash")
    check(receipt["contentHash"] == digest(material.encode()), "verification receipt root")
    field_order = ("schema", "manifestContentHash", "manifestByteHash", "campaignId", "domainId", "domainRevision",
                   "domainDescriptorHash", "discoveryEvidenceHash", "lifecycleHandoffHash", "artifactSetHash",
                   "verifiedArtifactCount", "identityBindingStatus", "mathematicalValidationStatus", "contentHash")
    check(tuple(receipt) == field_order, "receipt fields or order")
    canonical = json.dumps(receipt, ensure_ascii=False, separators=(",", ":")).encode()
    check(canonical == receipt_bytes, "receipt encoding")
    descriptor, evidence, handoff = (payloads[role] for role in FILES)
    expected = {k: descriptor[k] for k in ("domainId", "stateType", "candidateType", "certificateType")}
    resources = evidence["resources"]
    expected.update(domainRevision=descriptor["revision"], domainDescriptorHash=descriptor["contentHash"],
                    discoveryEvidenceHash=evidence["contentHash"], lifecycleHandoffHash=handoff["contentHash"],
                    exportVerificationHash=receipt["contentHash"], identityBindingStatus=receipt["identityBindingStatus"],
                    outcome=evidence["outcome"], selectedCandidateHash=evidence.get("selectedCandidateHash") or "",
                    certificateHash=(evidence.get("certificate") or {}).get("contentHash", ""),
                    resourceNames=sorted(r["resource"] for r in resources),
                    resourcesBalanced=all(r["configured"] == r["executed"] + r["skipped"] + r["remaining"] for r in resources))
    forbidden = {"payload", "canonicalState", "seedExpression", "selectedExpression", "sequenceTerms", "states", "path"}
    expected["representationFreeHandoff"] = not forbidden.intersection(field_names(handoff))
    for key in SCIENTIFIC_STATUS:
        check(evidence[key] == handoff[key], key)
        expected[key] = evidence[key]
    return f"{domain_id}|{manifest['contentHash']}|{receipt['contentHash']}", expected


def field_names(value):
    if isinstance(value, dict):
        for key, child in value.items():
            yield key
            yield from field_names(child)
    elif isinstance(value, list):
        for child in value:
            yield from field_names(child)


def verify_sources(root: Path, report, run):
    fingerprints = []
    domains = {d["domainId"]: d for d in report["domains"]}
    check(set(domains) == set(DOMAINS.values()) and len(domains) == len(report["domains"]), "reference domains")
    for index in range(1, 4):
        rows = []
        for name, domain_id in DOMAINS.items():
            material, summary = source_binding(root / "runs" / f"run-{index}", name, domain_id)
            rows.append(material)
            if index == 1:
                check(domains[domain_id] == summary, "report domain summary from verified sources")
        material = "regelsuche.domain-generic-clean-run/v1\nexports=[" + ", ".join(sorted(rows)) + "]"
        fingerprints.append(digest(material.encode()))
    identical = len(set(fingerprints)) == 1
    check(run["cleanRunFingerprints"] == fingerprints and report["cleanRunCount"] == len(fingerprints)
          and run["cleanRunsIdentical"] is identical and report["cleanRunsIdentical"] is identical,
          "observed clean-run count, fingerprints and equality")
    check(identical or report["status"] == "BLOCKED", "authorization with divergent runs")


def java_bool(value: bool) -> str:
    return "true" if value else "false"


def java_list(values: list[str]) -> str:
    return "[" + ", ".join(values) + "]"


def verify_qualification(root: Path, report, run):
    verify_sources(root, report, run)
    domains = report["domains"]
    checks = report["checks"]
    check(report["status"] == run["domainGenericQualificationStatus"]
          and report["domainGenericClaimAuthorized"] == run["domainGenericClaimAuthorized"], "qualification decision")
    for field in ("autonomousCampaignClaimAuthorized", *SCIENTIFIC_STATUS):
        check(report[field] == run[field], field)
    domain_material: list[str] = []
    for domain in domains:
        domain_material.append(
            "|".join(
                [
                    domain["domainId"],
                    domain["domainRevision"],
                    domain["stateType"],
                    domain["candidateType"],
                    domain["certificateType"],
                    domain["domainDescriptorHash"],
                    domain["discoveryEvidenceHash"],
                    domain["lifecycleHandoffHash"],
                    domain["exportVerificationHash"],
                    domain["identityBindingStatus"],
                    domain["outcome"],
                    domain["selectedCandidateHash"],
                    domain["certificateHash"],
                    java_list(domain["resourceNames"]),
                    java_bool(domain["resourcesBalanced"]),
                    java_bool(domain["representationFreeHandoff"]),
                    domain["proofStatus"],
                    domain["externalNoveltyStatus"],
                    domain["promotionStatus"],
                    domain["publicEvidenceStatus"],
                ]
            )
        )
    check_material = [
        check["code"]
        + "|passed="
        + java_bool(check["passed"])
        + "|actual="
        + check["actual"]
        + "|required="
        + check["required"]
        for check in checks
    ]
    report_material = (
        "regelsuche.domain-generic-discovery-qualification/v1"
        + "\nprofile="
        + report["profile"]
        + "\nstatus="
        + report["status"]
        + "\ndomainGenericClaimAuthorized="
        + java_bool(report["domainGenericClaimAuthorized"])
        + "\nautonomousCampaignClaimAuthorized="
        + java_bool(report["autonomousCampaignClaimAuthorized"])
        + "\ncleanRunCount="
        + str(report["cleanRunCount"])
        + "\ncleanRunsIdentical="
        + java_bool(report["cleanRunsIdentical"])
        + "\ndomains="
        + java_list(domain_material)
        + "\nchecks="
        + java_list(check_material)
        + "\nblockers="
        + java_list(report["blockers"])
        + "\nproofStatus="
        + report["proofStatus"]
        + "\nexternalNoveltyStatus="
        + report["externalNoveltyStatus"]
        + "\npromotionStatus="
        + report["promotionStatus"]
        + "\npublicEvidenceStatus="
        + report["publicEvidenceStatus"]
    ).encode("utf-8")
    check(report.get("contentHash") == digest(report_material), "qualification report content hash drift")

    check(run.get("profileCatalogHash") == digest((root / "profile-catalog.json").read_bytes()), "profile catalog hash drift")
    check(run.get("qualificationReportHash") == report.get("contentHash"), "run/report hash mismatch")
    run_material = (
        "regelsuche.domain-generic-discovery-qualification-run/v1"
        + "\nprofileCatalogHash="
        + run["profileCatalogHash"]
        + "\nqualificationReportHash="
        + run["qualificationReportHash"]
        + "\ncleanRunFingerprints="
        + java_list(run["cleanRunFingerprints"])
        + "\ncleanRunsIdentical="
        + java_bool(run["cleanRunsIdentical"])
        + "\ndomainGenericQualificationStatus="
        + run["domainGenericQualificationStatus"]
        + "\ndomainGenericClaimAuthorized="
        + java_bool(run["domainGenericClaimAuthorized"])
        + "\nautonomousCampaignClaimAuthorized="
        + java_bool(run["autonomousCampaignClaimAuthorized"])
        + "\nproofStatus="
        + run["proofStatus"]
        + "\nexternalNoveltyStatus="
        + run["externalNoveltyStatus"]
        + "\npromotionStatus="
        + run["promotionStatus"]
        + "\npublicEvidenceStatus="
        + run["publicEvidenceStatus"]
    ).encode("utf-8")
    check(run.get("contentHash") == digest(run_material), "qualification run content hash drift")
