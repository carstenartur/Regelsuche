#!/usr/bin/env python3
"""Independently verify an autonomous-discovery result card and evidence bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator


REQUIRED_ROLES = {
    "campaignManifest",
    "candidateLineage",
    "counterexampleReport",
    "fullMiningEvidence",
    "observationBundle",
    "pairedUtility",
    "projectNoveltyReport",
    "proofReport",
    "qualificationEvaluation",
    "qualificationEvidence",
    "releaseEvidence",
    "releaseReadinessMatrix",
    "releaseReadinessRun",
    "researchBrief",
    "validationReport",
}


def load_unique(path: Path) -> Any:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r} in {path}")
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=hook)


def canonical(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def digest_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def safe_artifact(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    resolved_root = root.resolve()
    require(
        candidate.is_relative_to(resolved_root),
        f"artifact escapes walkthrough root: {relative}",
    )
    require(candidate.is_file(), f"artifact is missing: {relative}")
    return candidate


def paired_summary(cases: list[dict[str, Any]]) -> dict[str, int]:
    baseline_reached = [item for item in cases if item["baselineReached"]]
    candidate_reached = [item for item in cases if item["candidateReached"]]
    both = [
        item
        for item in cases
        if item["baselineReached"] and item["candidateReached"]
    ]
    baseline_states = sum(item["baselineExploredStates"] for item in cases)
    candidate_states = sum(item["candidateExploredStates"] for item in cases)
    return {
        "caseCount": len(cases),
        "baselineReachedCount": len(baseline_reached),
        "candidateReachedCount": len(candidate_reached),
        "bothReachedCount": len(both),
        "baselinePathLengthTotalForReachedCases": sum(
            item["baselinePathLength"] for item in baseline_reached
        ),
        "candidatePathLengthTotalForReachedCases": sum(
            item["candidatePathLength"] for item in candidate_reached
        ),
        "pairedPathLengthDeltaForBothReached": sum(
            item["candidatePathLength"] - item["baselinePathLength"]
            for item in both
        ),
        "baselineExploredStatesTotal": baseline_states,
        "candidateExploredStatesTotal": candidate_states,
        "exploredStatesDelta": candidate_states - baseline_states,
        "materialGainCount": sum(item["materialGain"] for item in cases),
        "correctnessRegressionCount": sum(item["regression"] for item in cases),
        "unsupportedCandidateCaseCount": len(cases) - len(candidate_reached),
    }


def verify(root: Path, schema_path: Path) -> str:
    card_path = root / "result-card.json"
    schema = load_unique(schema_path)
    card = load_unique(card_path)
    Draft202012Validator.check_schema(schema)
    Draft202012Validator(schema).validate(card)

    material = dict(card)
    supplied_card_hash = material.pop("contentHash")
    require(
        digest_bytes(canonical(material)) == supplied_card_hash,
        "result-card contentHash does not match canonical fields",
    )

    artifacts: dict[str, tuple[dict[str, Any], Path, Any]] = {}
    for item in card["artifacts"]:
        role = item["role"]
        require(role not in artifacts, f"duplicate artifact role: {role}")
        path = safe_artifact(root, item["relativePath"])
        require(
            digest_bytes(path.read_bytes()) == item["fileSha256"],
            f"file hash mismatch for {role}",
        )
        artifacts[role] = (item, path, load_unique(path))
    require(set(artifacts) == REQUIRED_ROLES, "artifact role set is incomplete")

    def artifact(role: str) -> tuple[dict[str, Any], Any]:
        item, _, value = artifacts[role]
        return item, value

    campaign_item, campaign = artifact("campaignManifest")
    brief_item, brief = artifact("researchBrief")
    observations_item, observations = artifact("observationBundle")
    mining_item, mining = artifact("fullMiningEvidence")
    lineage_item, lineage = artifact("candidateLineage")
    validation_item, validation = artifact("validationReport")
    counterexample_item, counterexample = artifact("counterexampleReport")
    novelty_item, novelty = artifact("projectNoveltyReport")
    proof_item, proof = artifact("proofReport")
    qualification_item, qualification = artifact("qualificationEvidence")
    evaluation_item, qualification_evaluation = artifact(
        "qualificationEvaluation"
    )
    utility_item, utility = artifact("pairedUtility")
    release_evidence_item, release_evidence = artifact("releaseEvidence")
    matrix_item, matrix = artifact("releaseReadinessMatrix")
    release_item, release = artifact("releaseReadinessRun")

    embedded_hashes = {
        "campaignManifest": campaign["contentHash"],
        "researchBrief": brief["contentHash"],
        "observationBundle": observations["contentHash"],
        "fullMiningEvidence": mining["contentHash"],
        "candidateLineage": lineage["contentHash"],
        "qualificationEvidence": qualification["contentHash"],
        "pairedUtility": utility["contentHash"],
        "releaseEvidence": release_evidence["evidenceHash"],
        "releaseReadinessMatrix": matrix["contentHash"],
        "releaseReadinessRun": release["contentHash"],
    }
    for role, expected in embedded_hashes.items():
        require(
            artifacts[role][0]["semanticHash"] == expected,
            f"semantic hash mismatch for {role}",
        )
    require(
        validation_item["semanticHash"]
        == digest_bytes(artifacts["validationReport"][1].read_bytes()),
        "validation semantic hash differs from retained bytes",
    )
    require(
        counterexample_item["semanticHash"]
        == digest_bytes(artifacts["counterexampleReport"][1].read_bytes()),
        "counterexample semantic hash differs from retained bytes",
    )
    require(
        novelty_item["semanticHash"]
        == digest_bytes(artifacts["projectNoveltyReport"][1].read_bytes()),
        "novelty semantic hash differs from retained bytes",
    )
    require(
        proof_item["semanticHash"] == proof["evidenceHash"],
        "proof semantic hash mismatch",
    )
    require(
        evaluation_item["semanticHash"]
        == digest_bytes(artifacts["qualificationEvaluation"][1].read_bytes()),
        "qualification-evaluation semantic hash differs from retained bytes",
    )

    research = card["researchBrief"]
    require(card["runIdentity"] == release["contentHash"], "run identity drift")
    require(
        release["campaignManifestHash"] == campaign["contentHash"],
        "release/campaign hash link is broken",
    )
    require(
        campaign["briefHash"] == brief["contentHash"] == research["briefHash"],
        "research brief identity drift",
    )
    require(
        qualification["briefHash"] == research["briefHash"],
        "qualification was formed from another brief",
    )
    require(
        campaign["targetProvided"] is False
        and research["targetProvided"] is False
        and research["targetOrExpectedAnswerAccess"] == "ABSENT",
        "candidate formation was not demonstrably target-free",
    )
    require(
        research["seedFamilyCount"] == campaign["seedFamilyCount"],
        "seed-family count drift",
    )
    require(
        research["observationCount"] == campaign["observationCount"],
        "observation count drift",
    )

    candidate = card["candidate"]
    candidate_fields = {
        "conjectureId": "conjectureId",
        "candidateBranchId": "candidateBranchId",
        "leftPattern": "leftPattern",
        "rightPattern": "rightPattern",
        "lineageRoot": "lineageHash",
        "miningEvidenceHash": "miningEvidenceHash",
        "supportingObservationIds": "supportingObservationIds",
        "sourceObservationBranchHashes": "sourceObservationBranchHashes",
        "parameterRelations": "parameterRelations",
        "assumptions": "assumptions",
    }
    for card_field, source_field in candidate_fields.items():
        require(
            candidate[card_field] == qualification[source_field],
            f"candidate field drift: {card_field}",
        )
    require(
        candidate["supportingObservationCount"]
        == len(qualification["supportingObservationIds"]),
        "supporting-observation count drift",
    )
    require(
        candidate["lineageRoot"] == lineage_item["semanticHash"],
        "candidate lineage root drift",
    )
    require(
        candidate["miningEvidenceHash"] == mining_item["semanticHash"],
        "candidate mining root drift",
    )

    lifecycle = card["lifecycle"]
    require(
        lifecycle["validationStatus"] == validation["status"],
        "validation status drift",
    )
    require(
        lifecycle["counterexampleStatus"] == counterexample["status"],
        "counterexample status drift",
    )
    require(
        lifecycle["counterexampleStrategyCount"]
        == len(set(counterexample["attemptedSources"])),
        "counterexample strategy count drift",
    )
    require(
        lifecycle["projectNoveltyStatus"] == novelty["status"],
        "project-novelty status drift",
    )
    require(
        lifecycle["externalNoveltyStatus"]
        == novelty["externalNoveltyStatus"]
        == "NOT_EVALUATED",
        "external novelty boundary was inflated",
    )
    require(
        lifecycle["proofEvidenceStatus"] == proof["proofStatus"],
        "proof status drift",
    )
    require(
        lifecycle["proofBackendStatus"] == proof["backendStatus"],
        "proof backend status drift",
    )
    require(
        lifecycle["proofTranslationStatus"] == proof["translationStatus"],
        "proof translation status drift",
    )
    require(
        lifecycle["formalProofStatus"] == proof["formalProofStatus"],
        "formal-proof status drift",
    )

    shown_qualification = card["qualification"]
    qualification_fields = [
        "qualified",
        "heldOutFamilyOrClusterCount",
        "configuredPositiveHoldouts",
        "executedPositiveHoldouts",
        "configuredNegativeHoldouts",
        "executedNegativeHoldouts",
        "mandatorySkippedWorkCount",
        "refutingHoldouts",
        "counterexamplesFound",
        "correctnessRegressionCount",
    ]
    for field in qualification_fields:
        require(
            shown_qualification[field] == qualification[field],
            f"qualification field drift: {field}",
        )
    expected_failures = sum(
        qualification[field]
        for field in (
            "mandatorySkippedWorkCount",
            "refutingHoldouts",
            "counterexamplesFound",
            "correctnessRegressionCount",
        )
    )
    require(
        shown_qualification["failureCount"] == expected_failures,
        "qualification failure count drift",
    )
    require(
        shown_qualification["evaluationStatus"]
        == qualification_evaluation["status"],
        "qualification evaluation status drift",
    )
    require(
        shown_qualification["counterexampleStatus"]
        == qualification_evaluation["counterexampleStatus"],
        "qualification counterexample status drift",
    )
    require(qualification["qualified"] is True, "candidate is not qualified")

    shown_utility = card["pairedUtility"]
    require(shown_utility["cases"] == utility["cases"], "paired cases drift")
    require(
        shown_utility["summary"] == paired_summary(utility["cases"]),
        "paired utility summary drift",
    )
    require(
        shown_utility["gainPermille"] == utility["gainPermille"],
        "paired utility gain drift",
    )
    require(
        shown_utility["beneficial"] == utility["beneficial"] is True,
        "paired utility is not beneficial",
    )
    expected_representative = next(
        (item["id"] for item in utility["cases"] if item["materialGain"]),
        utility["cases"][0]["id"],
    )
    require(
        shown_utility["representativeCaseId"] == expected_representative,
        "representative utility case drift",
    )

    reproducibility = card["reproducibility"]
    require(
        reproducibility["cleanRunCount"] == release_evidence["cleanRunCount"],
        "clean-run count drift",
    )
    require(
        reproducibility["cleanRunsIdentical"]
        == release_evidence["cleanRunsIdentical"]
        is True,
        "clean runs are not identical",
    )
    require(
        reproducibility["cleanRunManifestHashes"]
        == release_evidence["cleanRunManifestHashes"],
        "clean-run manifest set drift",
    )

    boundaries = card["claimBoundaries"]
    require(
        boundaries["autonomyClaimAuthorized"]
        == release["autonomyClaimAuthorized"]
        == matrix["autonomyClaimAuthorized"]
        is True,
        "autonomy claim was not qualified",
    )
    require(
        boundaries["externalNoveltyStatus"] == "NOT_EVALUATED"
        and boundaries["externalNoveltyClaimAuthorized"] is False,
        "external novelty claim boundary was inflated",
    )
    require(
        boundaries["promotionStatus"]
        == release["promotionStatus"]
        == campaign["promotionStatus"]
        == "NOT_EVALUATED",
        "promotion boundary was inflated",
    )
    require(
        boundaries["publicEvidenceStatus"]
        == release["publicEvidenceStatus"]
        == campaign["publicEvidenceStatus"]
        == "NOT_EVALUATED",
        "Public Evidence boundary was inflated",
    )
    require(
        release["qualificationEvidenceHash"] == qualification["contentHash"],
        "release run is not bound to qualification evidence",
    )
    require(
        card["claimBanner"] == "NO EXTERNAL NOVELTY CLAIM",
        "claim banner is missing",
    )

    for figure in (
        "sequence.svg",
        "paired-utility.svg",
        "candidate-lineage.svg",
        "representative-search.svg",
    ):
        path = root / "figures" / figure
        require(path.is_file() and path.stat().st_size > 0, f"missing figure: {figure}")
        require(
            "NO EXTERNAL NOVELTY CLAIM" in path.read_text(encoding="utf-8"),
            f"claim banner missing from figure: {figure}",
        )

    return supplied_card_hash


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    args = parser.parse_args()
    print(verify(args.root, args.schema))


if __name__ == "__main__":
    main()
