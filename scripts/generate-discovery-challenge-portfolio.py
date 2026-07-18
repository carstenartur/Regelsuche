#!/usr/bin/env python3
"""Generate the canonical evaluator-backed discovery challenge artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


VALID_DECISIONS = {"SELECTED_FOR_PREREGISTRATION", "DEFERRED_AFTER_PILOT"}
VALID_COMPLEXITY = {"INTERMEDIATE", "ADVANCED"}
REQUIRED_CHALLENGE_FIELDS = {
    "challengeId",
    "title",
    "decision",
    "complexityTier",
    "mathematicalObject",
    "candidateRepresentation",
    "formationInformation",
    "prohibitedFormationInformation",
    "independentEvaluator",
    "certificateRoute",
    "splitUnit",
    "leakageControls",
    "baselineProfiles",
    "multipleSolutionPolicy",
    "nullResultPolicy",
    "budget",
    "externalReviewSources",
    "pilotRequirement",
    "selectionRationale",
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


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def digest(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def with_hash(value: dict[str, Any]) -> dict[str, Any]:
    if "contentHash" in value:
        raise ValueError("contentHash must be generated")
    result = dict(value)
    result["contentHash"] = digest(value)
    return result


def require_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be non-blank text")
    return value


def require_text_list(value: Any, field: str, minimum: int = 1) -> list[str]:
    if not isinstance(value, list) or len(value) < minimum:
        raise ValueError(f"{field} requires at least {minimum} entries")
    normalized = [require_text(item, field) for item in value]
    if len(set(normalized)) != len(normalized):
        raise ValueError(f"{field} must not contain duplicates")
    return normalized


def validate_source(source: dict[str, Any]) -> list[dict[str, Any]]:
    if source.get("schema") != "regelsuche.discovery-challenge-plan-source/v1":
        raise ValueError("unsupported challenge plan source schema")
    if source.get("claimPolicy") != (
        "EVALUATOR_BACKED_DOES_NOT_IMPLY_EXTERNAL_NOVELTY"
    ):
        raise ValueError("challenge claim policy is not conservative")
    require_text(source.get("portfolioId"), "portfolioId")
    selection = source.get("selectionPolicy")
    if not isinstance(selection, dict):
        raise ValueError("selectionPolicy must be an object")
    for flag in (
        "requireIndependentEvaluator",
        "requireFrozenSplitUnit",
        "requireInformationParityBaselines",
    ):
        if selection.get(flag) is not True:
            raise ValueError(f"selectionPolicy.{flag} must remain true")
    minimum_assessed = selection.get("minimumAssessedClasses")
    minimum_selected = selection.get("minimumSelectedClasses")
    if not isinstance(minimum_assessed, int) or minimum_assessed < 5:
        raise ValueError("minimumAssessedClasses must be at least 5")
    if not isinstance(minimum_selected, int) or minimum_selected < 2:
        raise ValueError("minimumSelectedClasses must be at least 2")
    if selection.get("externalSearchVisibility") != "POST_FORMATION_ONLY":
        raise ValueError("external search must remain post-formation")
    if selection.get("pilotEvidenceStatus") != "DEVELOPMENT_ONLY":
        raise ValueError("pilot evidence must remain development-only")
    challenges = source.get("challenges")
    if not isinstance(challenges, list):
        raise ValueError("challenges must be an array")
    if len(challenges) < minimum_assessed:
        raise ValueError("insufficient assessed challenge classes")

    ids: set[str] = set()
    titles: set[str] = set()
    selected = 0
    for challenge in challenges:
        if not isinstance(challenge, dict):
            raise ValueError("challenge entries must be objects")
        if set(challenge) != REQUIRED_CHALLENGE_FIELDS:
            missing = sorted(REQUIRED_CHALLENGE_FIELDS - set(challenge))
            extra = sorted(set(challenge) - REQUIRED_CHALLENGE_FIELDS)
            raise ValueError(
                f"challenge fields disagree: missing={missing}, extra={extra}"
            )
        challenge_id = require_text(challenge["challengeId"], "challengeId")
        if challenge_id in ids:
            raise ValueError(f"duplicate challengeId: {challenge_id}")
        ids.add(challenge_id)
        title = require_text(challenge["title"], "title")
        if title in titles:
            raise ValueError(f"duplicate challenge title: {title}")
        titles.add(title)
        if challenge["decision"] not in VALID_DECISIONS:
            raise ValueError(f"unsupported decision for {challenge_id}")
        if challenge["complexityTier"] not in VALID_COMPLEXITY:
            raise ValueError(
                f"unsupported complexity tier for {challenge_id}"
            )
        require_text_list(
            challenge["formationInformation"], "formationInformation"
        )
        prohibited = require_text_list(
            challenge["prohibitedFormationInformation"],
            "prohibitedFormationInformation",
        )
        prohibited_material = " ".join(prohibited).lower()
        if "target" not in prohibited_material and (
            "expected" not in prohibited_material
        ):
            raise ValueError(
                f"target/expected-answer prohibition missing for {challenge_id}"
            )
        evaluator = challenge["independentEvaluator"]
        if not isinstance(evaluator, dict) or set(evaluator) != {
            "evaluatorId",
            "method",
            "resultStrength",
            "unsupportedBoundary",
        }:
            raise ValueError(
                f"invalid independent evaluator for {challenge_id}"
            )
        for field in evaluator:
            require_text(
                evaluator[field], f"independentEvaluator.{field}"
            )
        require_text(challenge["splitUnit"], "splitUnit")
        require_text_list(
            challenge["leakageControls"], "leakageControls", 3
        )
        require_text_list(
            challenge["baselineProfiles"], "baselineProfiles", 2
        )
        require_text_list(
            challenge["externalReviewSources"], "externalReviewSources"
        )
        budget = challenge["budget"]
        if not isinstance(budget, dict) or set(budget) != {
            "maximumCampaigns",
            "maximumStatesPerCampaign",
            "maximumCandidateEvaluations",
            "maximumCounterexampleAttempts",
            "maximumProofAttempts",
        }:
            raise ValueError(f"invalid budget for {challenge_id}")
        for field, value in budget.items():
            if not isinstance(value, int) or value < 0:
                raise ValueError(
                    f"{challenge_id}.{field} must be non-negative integer"
                )
        if (
            budget["maximumCampaigns"] <= 0
            or budget["maximumCandidateEvaluations"] <= 0
        ):
            raise ValueError(
                f"{challenge_id} has no executable campaign budget"
            )
        if challenge["decision"] == "SELECTED_FOR_PREREGISTRATION":
            selected += 1
    if selected < minimum_selected:
        raise ValueError("insufficient selected challenge classes")
    return sorted(challenges, key=lambda item: item["challengeId"])


def write(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    source = load_unique(args.source)
    challenges = validate_source(source)
    source_hash = digest(source)
    selected = [
        item
        for item in challenges
        if item["decision"] == "SELECTED_FOR_PREREGISTRATION"
    ]
    deferred = [
        item
        for item in challenges
        if item["decision"] == "DEFERRED_AFTER_PILOT"
    ]

    landscape = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-landscape/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "claimPolicy": source["claimPolicy"],
            "assessedChallengeCount": len(challenges),
            "selectedChallengeCount": len(selected),
            "deferredChallengeCount": len(deferred),
            "challenges": [
                {
                    "challengeId": item["challengeId"],
                    "title": item["title"],
                    "decision": item["decision"],
                    "complexityTier": item["complexityTier"],
                    "mathematicalObject": item["mathematicalObject"],
                    "selectionRationale": item["selectionRationale"],
                }
                for item in challenges
            ],
        }
    )

    feasibility = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-feasibility/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "evaluations": [
                {
                    "challengeId": item["challengeId"],
                    "decision": item["decision"],
                    "complexityTier": item["complexityTier"],
                    "candidateRepresentation": item[
                        "candidateRepresentation"
                    ],
                    "independentEvaluator": item["independentEvaluator"],
                    "certificateRoute": item["certificateRoute"],
                    "splitUnit": item["splitUnit"],
                    "pilotRequirement": item["pilotRequirement"],
                    "multipleSolutionPolicy": item[
                        "multipleSolutionPolicy"
                    ],
                    "nullResultPolicy": item["nullResultPolicy"],
                }
                for item in challenges
            ],
            "pilotEvidenceStatus": "DEVELOPMENT_ONLY",
            "evaluatedCampaignStatus": "NOT_STARTED",
            "externalNoveltyStatus": "NOT_EVALUATED",
        }
    )

    split_policy = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-split-policy/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "externalSearchVisibility": source["selectionPolicy"][
                "externalSearchVisibility"
            ],
            "challengePolicies": [
                {
                    "challengeId": item["challengeId"],
                    "splitUnit": item["splitUnit"],
                    "formationInformation": item["formationInformation"],
                    "prohibitedFormationInformation": item[
                        "prohibitedFormationInformation"
                    ],
                    "leakageControls": item["leakageControls"],
                }
                for item in challenges
            ],
            "crossSplitCollisionDisposition": "BLOCK_PUBLICATION",
            "correctedSplitDisposition": "NEW_PORTFOLIO_IDENTITY",
        }
    )

    baseline_plan = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-baseline-plan/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "universalScorePolicy": (
                "NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY"
            ),
            "challengeBaselines": [
                {
                    "challengeId": item["challengeId"],
                    "baselineProfiles": item["baselineProfiles"],
                    "informationParityRequired": True,
                    "validationOnlySystemsUseSeparateTrack": True,
                    "externalReviewSources": item[
                        "externalReviewSources"
                    ],
                }
                for item in challenges
            ],
        }
    )

    run_budget = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-run-budget/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "challengeBudgets": [
                {
                    "challengeId": item["challengeId"],
                    **item["budget"],
                }
                for item in challenges
            ],
            "overspendDisposition": "INVALID_RUN",
            "missingMandatoryEvaluationDisposition": "INVALID_RUN",
        }
    )

    portfolio = with_hash(
        {
            "schema": "regelsuche.discovery-challenge-portfolio/v1",
            "portfolioId": source["portfolioId"],
            "sourceHash": source_hash,
            "claimPolicy": source["claimPolicy"],
            "selectedChallengeIds": [
                item["challengeId"] for item in selected
            ],
            "deferredChallengeIds": [
                item["challengeId"] for item in deferred
            ],
            "landscapeHash": landscape["contentHash"],
            "feasibilityHash": feasibility["contentHash"],
            "splitPolicyHash": split_policy["contentHash"],
            "baselinePlanHash": baseline_plan["contentHash"],
            "runBudgetHash": run_budget["contentHash"],
            "pilotEvidenceStatus": "DEVELOPMENT_ONLY",
            "evaluatedCampaignStatus": "NOT_STARTED",
            "externalNoveltyStatus": "NOT_EVALUATED",
            "consumptionTarget": (
                "issue-383-candidate-independent-benchmark"
            ),
        }
    )

    outputs = {
        "challenge-landscape.json": landscape,
        "challenge-feasibility-report.json": feasibility,
        "challenge-split-policy.json": split_policy,
        "challenge-baseline-plan.json": baseline_plan,
        "challenge-run-budget.json": run_budget,
        "challenge-portfolio.json": portfolio,
    }
    for name, value in outputs.items():
        write(args.output / name, value)

    summary = [
        "# Evaluator-backed discovery challenge portfolio",
        "",
        f"- Portfolio: `{source['portfolioId']}`",
        f"- Assessed classes: {len(challenges)}",
        f"- Selected for preregistration: {len(selected)}",
        f"- Deferred pending development pilots: {len(deferred)}",
        "- Evaluated campaign status: `NOT_STARTED`",
        "- External novelty status: `NOT_EVALUATED`",
        "",
        "## Selected challenges",
        "",
    ]
    for item in selected:
        summary.append(
            f"- `{item['challengeId']}` — {item['title']} "
            f"({item['complexityTier']})"
        )
    summary.extend(["", "## Deferred challenges", ""])
    for item in deferred:
        summary.append(
            f"- `{item['challengeId']}` — {item['title']}: "
            f"{item['pilotRequirement']}"
        )
    summary.extend(
        [
            "",
            (
                "Selection is a preregistration decision, not evidence "
                "that a campaign has succeeded."
            ),
        ]
    )
    (args.output / "challenge-portfolio-summary.md").write_text(
        "\n".join(summary) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
