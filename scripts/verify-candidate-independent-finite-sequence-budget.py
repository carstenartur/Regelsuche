#!/usr/bin/env python3
"""Bind the evaluated finite-sequence slice to the frozen benchmark budget."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
from typing import Any

SCHEMA = "regelsuche.candidate-independent-finite-sequence-budget-verification/v1"
CHALLENGE = "finite-difference-recurrences"
EXPECTED_CAMPAIGNS = [
    f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)
]
EXPECTED_FORMATION_CASES = ["case-07", "case-08"]


class Invalid(RuntimeError):
    pass


def need(condition: bool, message: str) -> None:
    if not condition:
        raise Invalid(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        need(key not in result, f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def load(path: Path) -> dict[str, Any]:
    need(path.is_file() and not path.is_symlink(), f"missing regular JSON file: {path}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
        )
    except (OSError, json.JSONDecodeError) as error:
        raise Invalid(f"cannot read {path}: {error}") from error
    need(isinstance(value, dict), f"expected JSON object in {path}")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def document_hash(value: dict[str, Any]) -> str:
    material = dict(value)
    material.pop("contentHash", None)
    return semantic_hash(material)


def require_content_hash(value: dict[str, Any], label: str) -> None:
    need(value.get("contentHash") == document_hash(value), f"{label} contentHash drift")


def configured_seed(benchmark_id: str, campaign_id: str, index: int) -> str:
    return semantic_hash(
        {
            "benchmarkId": benchmark_id,
            "campaignId": campaign_id,
            "challengeId": CHALLENGE,
            "index": index,
        }
    )


def non_negative_integer(value: Any, label: str) -> int:
    need(isinstance(value, int) and value >= 0, f"invalid {label}: {value!r}")
    return value


def verify(
    source: dict[str, Any],
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    first: dict[str, Any],
    second: dict[str, Any],
) -> dict[str, Any]:
    require_content_hash(corpus, "case corpus")
    require_content_hash(receipt, "freeze receipt")
    require_content_hash(first, "first adapter run")
    require_content_hash(second, "second adapter run")
    need(first == second, "two adapter runs differ semantically")

    source_hash = semantic_hash(source)
    need(source.get("executionStatus") == "NOT_STARTED", "source no longer represents preregistration")
    need(source.get("publicationAuthorized") is False, "source authorizes publication")
    need(corpus.get("priorBenchmarkSourceContentHash") == source_hash, "corpus/source hash binding drift")
    need(receipt.get("benchmarkSourceContentHash") == source_hash, "receipt/source hash binding drift")
    need(receipt.get("caseCorpusContentHash") == corpus.get("contentHash"), "receipt/corpus binding drift")
    need(first.get("caseCorpusContentHash") == corpus.get("contentHash"), "run/corpus binding drift")
    need(first.get("combinedPreregistrationHash") == receipt.get("combinedPreregistrationHash"), "run/freeze-root binding drift")

    budgets = source.get("budgets")
    need(isinstance(budgets, dict), "source budgets are missing")
    expected_budget = {
        "campaignsPerChallenge": 4,
        "maxCandidateEvaluations": 600,
        "maxProofAttempts": 100,
        "maxStatesPerCampaign": 3000,
    }
    need(budgets == expected_budget, f"frozen budget drift: {budgets!r}")
    need(first.get("configuredCampaigns") == budgets["campaignsPerChallenge"], "campaign count differs from frozen budget")

    campaigns = first.get("campaigns")
    need(isinstance(campaigns, list), "adapter campaigns are missing")
    need([item.get("campaignId") for item in campaigns] == EXPECTED_CAMPAIGNS, "campaign identities changed")

    total_conservative_states = 0
    total_conservative_candidates = 0
    total_proofs = 0
    total_observed_evaluation_states = 0
    total_observed_evaluation_candidates = 0
    total_formation_state_ceiling = 0
    total_formation_candidate_ceiling = 0
    maximum_conservative_states = 0
    maximum_conservative_candidates = 0
    maximum_proofs = 0
    evaluation_count = 0
    campaign_accounting: list[dict[str, Any]] = []

    for index, campaign in enumerate(campaigns, start=1):
        campaign_id = EXPECTED_CAMPAIGNS[index - 1]
        need(
            campaign.get("configuredSeed")
            == configured_seed(source["benchmarkId"], campaign_id, index),
            f"configured seed drift for {campaign_id}",
        )

        formation = campaign.get("formationEvidence")
        need(isinstance(formation, list) and len(formation) == 2, f"formation matrix drift for {campaign_id}")
        need([item.get("caseId") for item in formation] == EXPECTED_FORMATION_CASES, f"formation identities changed for {campaign_id}")
        formation_state_ceiling = 0
        formation_candidate_ceiling = 0
        for item in formation:
            need(isinstance(item, dict), f"non-object formation evidence in {campaign_id}")
            maximum_order = non_negative_integer(item.get("maximumOrder"), "maximumOrder")
            need(1 <= maximum_order <= 8, f"maximumOrder outside frozen profile in {campaign_id}")
            # CandidateIndependentFiniteSequenceAdapterMain constructs the
            # production-domain budget as max(16, order+2) states and
            # max(4, order+1) candidate attempts. The raw formation evidence is
            # hash-bound but not duplicated in this adapter report, therefore
            # the global campaign check conservatively charges the complete
            # local ceiling rather than an unretained observed value.
            formation_state_ceiling += max(16, maximum_order + 2)
            formation_candidate_ceiling += max(4, maximum_order + 1)

        evaluations = campaign.get("evaluations")
        need(isinstance(evaluations, list) and len(evaluations) == 6, f"evaluation matrix drift for {campaign_id}")
        evaluation_states = 0
        evaluation_candidates = 0
        evaluation_proofs = 0
        for evaluation in evaluations:
            need(isinstance(evaluation, dict), f"non-object evaluation in {campaign_id}")
            resource_use = evaluation.get("resourceUse")
            need(isinstance(resource_use, dict), f"resourceUse missing in {campaign_id}")
            states = non_negative_integer(resource_use.get("exploredStates"), "exploredStates")
            candidates = non_negative_integer(resource_use.get("candidateAttempts"), "candidateAttempts")
            proofs = non_negative_integer(resource_use.get("proofAttempts"), "proofAttempts")
            non_negative_integer(resource_use.get("generatedSuccessors"), "generatedSuccessors")
            evaluation_states += states
            evaluation_candidates += candidates
            evaluation_proofs += proofs
            evaluation_count += 1

        conservative_states = formation_state_ceiling + evaluation_states
        conservative_candidates = formation_candidate_ceiling + evaluation_candidates
        need(
            conservative_states <= budgets["maxStatesPerCampaign"],
            f"{campaign_id} exceeds frozen state budget: {conservative_states}",
        )
        need(
            conservative_candidates <= budgets["maxCandidateEvaluations"],
            f"{campaign_id} exceeds frozen candidate budget: {conservative_candidates}",
        )
        need(
            evaluation_proofs <= budgets["maxProofAttempts"],
            f"{campaign_id} exceeds frozen proof budget: {evaluation_proofs}",
        )

        campaign_accounting.append(
            {
                "campaignId": campaign_id,
                "formationConservativeCeiling": {
                    "exploredStates": formation_state_ceiling,
                    "candidateEvaluations": formation_candidate_ceiling,
                    "proofAttempts": 0,
                },
                "evaluationObserved": {
                    "exploredStates": evaluation_states,
                    "candidateEvaluations": evaluation_candidates,
                    "proofAttempts": evaluation_proofs,
                },
                "conservativeCampaignBound": {
                    "exploredStates": conservative_states,
                    "candidateEvaluations": conservative_candidates,
                    "proofAttempts": evaluation_proofs,
                },
                "withinFrozenBudget": True,
            }
        )
        total_conservative_states += conservative_states
        total_conservative_candidates += conservative_candidates
        total_proofs += evaluation_proofs
        total_observed_evaluation_states += evaluation_states
        total_observed_evaluation_candidates += evaluation_candidates
        total_formation_state_ceiling += formation_state_ceiling
        total_formation_candidate_ceiling += formation_candidate_ceiling
        maximum_conservative_states = max(maximum_conservative_states, conservative_states)
        maximum_conservative_candidates = max(maximum_conservative_candidates, conservative_candidates)
        maximum_proofs = max(maximum_proofs, evaluation_proofs)

    need(evaluation_count == 24, f"expected 24 evaluated rows, found {evaluation_count}")
    need(first.get("executedEvaluations") == evaluation_count, "top-level evaluation count drift")
    return {
        "schema": SCHEMA,
        "status": "VERIFIED_FROZEN_RESOURCE_BOUNDARY",
        "benchmarkId": source["benchmarkId"],
        "benchmarkSourceContentHash": source_hash,
        "caseCorpusContentHash": corpus["contentHash"],
        "combinedPreregistrationHash": receipt["combinedPreregistrationHash"],
        "adapterRunContentHash": first["contentHash"],
        "resourceAccountingPolicy": "FORMATION_LOCAL_CEILINGS_PLUS_OBSERVED_EVALUATION_USAGE",
        "frozenBudget": expected_budget,
        "campaignsVerified": len(campaigns),
        "evaluationsVerified": evaluation_count,
        "campaignAccounting": campaign_accounting,
        "maximumConservativePerCampaign": {
            "exploredStates": maximum_conservative_states,
            "candidateEvaluations": maximum_conservative_candidates,
            "proofAttempts": maximum_proofs,
        },
        "totals": {
            "formationConservativeStateCeiling": total_formation_state_ceiling,
            "formationConservativeCandidateCeiling": total_formation_candidate_ceiling,
            "observedEvaluationStates": total_observed_evaluation_states,
            "observedEvaluationCandidateEvaluations": total_observed_evaluation_candidates,
            "conservativeExploredStates": total_conservative_states,
            "conservativeCandidateEvaluations": total_conservative_candidates,
            "proofAttempts": total_proofs,
        },
        "budgetExceeded": False,
        "publicationAuthorized": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--freeze-receipt", type=Path, required=True)
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(
            load(args.source.resolve()),
            load(args.corpus.resolve()),
            load(args.freeze_receipt.resolve()),
            load(args.first.resolve()),
            load(args.second.resolve()),
        )
        report["contentHash"] = semantic_hash(report)
        output = args.report_directory.resolve()
        if output.exists():
            shutil.rmtree(output)
        output.mkdir(parents=True)
        target = output / "verification.json"
        target.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"finiteSequenceBudgetVerification={target}")
        print(f"contentHash={report['contentHash']}")
        print(f"maximumConservativePerCampaign={report['maximumConservativePerCampaign']}")
    except (Invalid, OSError) as error:
        print(f"finite-sequence budget verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
