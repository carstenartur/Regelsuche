"""Existing ReleaseRun/MatrixReport v1 hash and artifact bindings, without replay."""

from __future__ import annotations

from pathlib import Path
from release_readiness_identities import (
    java_hash, java_list, java_string_key, sha256, verify_artifact_identities,
)

PROFILES = {
    "SEARCH_REPRODUCIBILITY", "HIDDEN_RULE_REDISCOVERY", "OPEN_TARGET_DISCOVERY",
    "AUTONOMOUS_CAMPAIGN", "EXTERNAL_NOVELTY_REVIEW",
}


def profile_material(profile: dict) -> str:
    checks = sorted(profile["checks"], key=lambda item: java_string_key(item["code"]))
    check_material = (
        item["code"] + "|passed=" + str(item["passed"]).lower()
        + "|actual=" + item["actual"] + "|required=" + item["required"]
        for item in checks
    )
    blockers = sorted(set(profile["blockers"]), key=java_string_key)
    return (
        profile["profile"] + "|" + profile["status"] + "|"
        + str(profile["authorizesAutonomyClaim"]).lower() + "|"
        + java_list(check_material) + "|" + java_list(blockers)
    )


def matrix_hash(matrix: dict) -> str:
    # ReleaseReadinessMatrix.matrixHash / ProfileResult / RequirementCheck.
    profiles = sorted(matrix["profiles"], key=lambda item: java_string_key(item["profile"]))
    material = (
        matrix["schema"] + "\nevidence=" + matrix["evidenceHash"]
        + "\nhiddenRuleEvidence=" + matrix["hiddenRuleEvidenceHash"]
        + "\nprofiles=" + java_list(profile_material(item) for item in profiles)
        + "\nautonomy=" + matrix["autonomousCampaignStatus"]
    )
    return java_hash(material)


def run_hash(run: dict) -> str:
    # ReleaseReadinessRunner.runHash: the order and labels are part of v1.
    material = (
        run["schema"] + "\nprofileCatalog=" + run["profileCatalogHash"]
        + "\nevidence=" + run["evidenceHash"]
        + "\nhiddenRuleEvidence=" + run["hiddenRuleEvidenceHash"]
        + "\nqualificationEvidence=" + run["qualificationEvidenceHash"]
        + "\nmatrix=" + run["matrixHash"]
        + "\ncampaign=" + run["campaignManifestHash"]
    )
    return java_hash(material)


def profiles_by_name(document: dict, label: str, require) -> dict:
    profiles = document.get("profiles")
    require(isinstance(profiles, list), label + " profiles must be an array")
    require(
        all(isinstance(item, dict) and isinstance(item.get("profile"), str) for item in profiles),
        label + " profiles must name their profile",
    )
    names = [item["profile"] for item in profiles]
    require(len(names) == len(PROFILES) and set(names) == PROFILES,
            label + " must contain every release profile exactly once")
    return {item["profile"]: item for item in profiles}


def verify_matrix_profiles(matrix: dict, catalog: dict, require) -> None:
    profiles = profiles_by_name(matrix, "matrix", require)
    declared = profiles_by_name(catalog, "catalog", require)
    require(catalog.get("schema") == "regelsuche.release-evidence-profile-catalog/v1",
            "unsupported profile catalog schema")
    require(catalog.get("autonomyClaimProfile") == "AUTONOMOUS_CAMPAIGN"
            and catalog.get("externalNoveltyProfile") == "EXTERNAL_NOVELTY_REVIEW",
            "catalog claim profile binding differs")
    for name, profile in profiles.items():
        authority = name == "AUTONOMOUS_CAMPAIGN"
        require(declared[name].get("authorizesAutonomyClaim") is authority,
                "catalog profile authority differs: " + name)
        require(profile["claim"] == declared[name].get("claim"),
                "matrix profile claim differs from catalog: " + name)
        ready = profile["status"] == "READY"
        require(ready == (not profile["blockers"]),
                "profile status and blockers disagree: " + name)
        require(profile["authorizesAutonomyClaim"] is (authority and ready),
                "profile claim authority is inconsistent: " + name)
    autonomy = profiles["AUTONOMOUS_CAMPAIGN"]
    require(matrix["autonomousCampaignStatus"] == autonomy["status"]
            and matrix["autonomyClaimAuthorized"] == autonomy["authorizesAutonomyClaim"],
            "matrix autonomy summary is inconsistent")


def verify_root_bindings(root: Path, load, require, read_bytes) -> None:
    run = load(root / "release-readiness-run.json")
    matrix = load(root / "release-readiness-report.json")
    campaign = load(root / "campaign/production-campaign-manifest.json")
    evidence = load(root / "evidence-summary.json")
    hidden = load(root / "hidden-rule-release-evidence.json")
    qualification = load(root / "qualification/candidate-qualification-evidence.json")
    qualification_run = load(root / "qualification/candidate-qualification-run.json")
    catalog = load(root / "profiles.json")

    verify_artifact_identities(campaign, evidence, hidden, qualification, qualification_run, require)

    expected = {
        "campaignManifestHash": campaign.get("contentHash"),
        "profileCatalogHash": sha256(read_bytes(root / "profiles.json")),
        "evidenceHash": evidence.get("evidenceHash"),
        "hiddenRuleEvidenceHash": hidden.get("evidenceHash"),
        "qualificationEvidenceHash": qualification.get("contentHash"),
        "matrixHash": matrix["contentHash"],
    }
    for field, value in expected.items():
        require(run[field] == value, "run " + field + " artifact binding differs")
    campaign_hash = run["campaignManifestHash"]
    for label, document in (("campaign evidence", evidence), ("qualification evidence", qualification),
                            ("qualification run", qualification_run)):
        require(document.get("campaignManifestHash") == campaign_hash,
                label + " belongs to another campaign")
    require(qualification_run.get("qualificationEvidenceHash") == run["qualificationEvidenceHash"],
            "qualification run evidence binding differs")
    require(matrix["evidenceHash"] == run["evidenceHash"], "matrix campaign evidence binding differs")
    require(matrix["hiddenRuleEvidenceHash"] == run["hiddenRuleEvidenceHash"],
            "matrix hidden-rule evidence binding differs")
    require(run["autonomousCampaignStatus"] == matrix["autonomousCampaignStatus"]
            and run["autonomyClaimAuthorized"] == matrix["autonomyClaimAuthorized"],
            "run autonomy summary differs from matrix")

    verify_matrix_profiles(matrix, catalog, require)
    require(matrix["contentHash"] == matrix_hash(matrix), "matrix canonical content hash differs")
    require(run["contentHash"] == run_hash(run), "run canonical content hash differs")
