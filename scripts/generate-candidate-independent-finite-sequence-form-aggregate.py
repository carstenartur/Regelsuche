#!/usr/bin/env python3
"""Generate a canonical aggregate over the two frozen finite-sequence candidate forms."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any

SCHEMA = "regelsuche.candidate-independent-finite-sequence-form-aggregate/v1"
CHALLENGE = "finite-difference-recurrences"
STATUS = "COMPLETE_CANDIDATE_FORM_EXECUTION_WITH_RETAINED_MODEL_CONFLICTS"
BENCHMARK_STATUS = "PARTIAL_OTHER_CHALLENGES_NOT_AGGREGATED"
FORMS = ["FINITE_DIFFERENCE_POLYNOMIAL", "LINEAR_RECURRENCE"]
EXPECTED_CASES = [f"case-{index:02d}" for index in range(7, 13)]
EXPECTED_CAMPAIGNS = [f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)]


class AggregateError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise AggregateError(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON field {key!r}")
        result[key] = value
    return result


def load_json(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        fail(f"expected regular non-symbolic JSON file: {path}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"expected JSON object in {path}")
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


def add_hash(value: dict[str, Any]) -> dict[str, Any]:
    if "contentHash" in value:
        fail("contentHash must be assigned exactly once")
    value["contentHash"] = semantic_hash(value)
    return value


def require_hash(value: dict[str, Any], context: str) -> None:
    retained = value.get("contentHash")
    if not isinstance(retained, str):
        fail(f"{context} has no contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = semantic_hash(material)
    if retained != expected:
        fail(f"{context} contentHash mismatch: {retained} != {expected}")


def require_text(value: dict[str, Any], field: str, context: str) -> str:
    result = value.get(field)
    if not isinstance(result, str) or not result:
        fail(f"{context} has no textual {field}")
    return result


def require_list(value: dict[str, Any], field: str, context: str) -> list[Any]:
    result = value.get(field)
    if not isinstance(result, list):
        fail(f"{context} has no array {field}")
    return result


def index_by(items: list[Any], field: str, context: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            fail(f"{context} item {index} is not an object")
        identity = require_text(item, field, f"{context} item {index}")
        if identity in result:
            fail(f"duplicate {context} identity {identity}")
        result[identity] = item
    return result


def validate_source_run(
    run: dict[str, Any],
    expected_schema: str,
    expected_form: str,
    context: str,
) -> None:
    require_hash(run, context)
    if run.get("schema") != expected_schema:
        fail(f"{context} schema changed: {run.get('schema')!r}")
    if run.get("challengeId") != CHALLENGE:
        fail(f"{context} challenge changed")
    source_form = run.get("availableCandidateForm", run.get("candidateForm"))
    if source_form != expected_form:
        fail(f"{context} candidate form changed: {source_form!r}")
    if run.get("configuredCampaigns") != 4 or run.get("executedCampaigns") != 4:
        fail(f"{context} campaign accounting changed")
    if run.get("configuredEvaluations") != 24 or run.get("executedEvaluations") != 24:
        fail(f"{context} evaluation accounting changed")
    campaigns = require_list(run, "campaigns", context)
    if [item.get("campaignId") for item in campaigns if isinstance(item, dict)] != EXPECTED_CAMPAIGNS:
        fail(f"{context} campaign identities changed")
    for campaign_index, campaign in enumerate(campaigns):
        require_hash(campaign, f"{context} campaign {campaign_index}")
        for evaluation_index, evaluation in enumerate(
            require_list(campaign, "evaluations", f"{context} campaign {campaign_index}")
        ):
            if not isinstance(evaluation, dict):
                fail(f"{context} evaluation is not an object")
            require_hash(
                evaluation,
                f"{context} campaign {campaign_index} evaluation {evaluation_index}",
            )


def source_descriptor(run: dict[str, Any], form: str) -> dict[str, Any]:
    return {
        "candidateForm": form,
        "schema": run["schema"],
        "contentHash": run["contentHash"],
    }


def finite_result(source: dict[str, Any]) -> dict[str, Any]:
    outcome = require_text(source, "outcome", "finite-difference evaluation")
    if outcome == "CONFIRMED_FINITE_DIFFERENCE_FIT":
        disposition = "CONFIRMED"
    elif outcome == "INCOMPLETE_ADAPTER_COVERAGE":
        disposition = "INCONCLUSIVE"
    else:
        fail(f"unexpected finite-difference outcome {outcome}")
    return {
        "candidateForm": "FINITE_DIFFERENCE_POLYNOMIAL",
        "disposition": disposition,
        "sourceOutcome": outcome,
        "reasonCode": require_text(source, "reasonCode", "finite-difference evaluation"),
        "productionOutcome": require_text(
            source, "productionOutcome", "finite-difference evaluation"
        ),
        "sourceEvaluationContentHash": require_text(
            source, "contentHash", "finite-difference evaluation"
        ),
        "productionEvidenceContentHash": require_text(
            source, "productionEvidenceContentHash", "finite-difference evaluation"
        ),
    }


def recurrence_result(source: dict[str, Any]) -> dict[str, Any]:
    outcome = require_text(source, "outcome", "linear-recurrence evaluation")
    if outcome == "CONFIRMED_LINEAR_RECURRENCE_FIT":
        disposition = "CONFIRMED"
    elif outcome == "REFUTED_LINEAR_RECURRENCE_FIT":
        disposition = "REFUTED"
    elif outcome == "NO_UNIQUE_LINEAR_RECURRENCE":
        disposition = "INCONCLUSIVE"
    else:
        fail(f"unexpected linear-recurrence outcome {outcome}")
    coefficients = source.get("coefficients")
    expected = source.get("expectedHoldout")
    predicted = source.get("predictedHoldout")
    if not isinstance(coefficients, list) or not isinstance(expected, list) or not isinstance(predicted, list):
        fail("linear-recurrence model evidence is incomplete")
    return {
        "candidateForm": "LINEAR_RECURRENCE",
        "disposition": disposition,
        "sourceOutcome": outcome,
        "reasonCode": require_text(source, "reasonCode", "linear-recurrence evaluation"),
        "productionOutcome": require_text(
            source, "productionOutcome", "linear-recurrence evaluation"
        ),
        "formedModelStatus": require_text(
            source, "formedModelStatus", "linear-recurrence evaluation"
        ),
        "recurrenceOrder": source.get("recurrenceOrder"),
        "coefficients": coefficients,
        "expectedHoldout": expected,
        "predictedHoldout": predicted,
        "sourceEvaluationContentHash": require_text(
            source, "contentHash", "linear-recurrence evaluation"
        ),
        "productionEvidenceContentHash": require_text(
            source, "productionEvidenceContentHash", "linear-recurrence evaluation"
        ),
    }


def aggregate_case(
    frozen: dict[str, Any],
    finite: dict[str, Any],
    recurrence: dict[str, Any],
) -> dict[str, Any]:
    case_id = require_text(frozen, "caseId", "frozen case")
    for source, name in ((finite, "finite"), (recurrence, "recurrence")):
        if source.get("caseId") != case_id:
            fail(f"{name} evaluation identity mismatch for {case_id}")
        if source.get("caseContentHash") != frozen.get("contentHash"):
            fail(f"{name} case hash mismatch for {case_id}")
        if source.get("split") != frozen.get("split"):
            fail(f"{name} split mismatch for {case_id}")
        if source.get("structuralCluster") != frozen.get("structuralCluster"):
            fail(f"{name} structural cluster mismatch for {case_id}")

    results = [finite_result(finite), recurrence_result(recurrence)]
    supporting = [item["candidateForm"] for item in results if item["disposition"] == "CONFIRMED"]
    refuted = [item["candidateForm"] for item in results if item["disposition"] == "REFUTED"]
    inconclusive = [
        item["candidateForm"] for item in results if item["disposition"] == "INCONCLUSIVE"
    ]
    confirmed = bool(supporting)
    conflict = confirmed and bool(refuted)
    if conflict:
        disposition = "CONFIRMED_WITH_REFUTED_ALTERNATIVE_MODEL"
    elif supporting == ["FINITE_DIFFERENCE_POLYNOMIAL"]:
        disposition = "CONFIRMED_BY_FINITE_DIFFERENCE_ONLY"
    elif supporting == ["LINEAR_RECURRENCE"]:
        disposition = "CONFIRMED_BY_LINEAR_RECURRENCE_ONLY"
    elif supporting == FORMS:
        disposition = "CONFIRMED_BY_BOTH_FORMS"
    else:
        disposition = "UNRESOLVED_BY_AVAILABLE_FORMS"

    return add_hash(
        {
            "caseId": case_id,
            "caseContentHash": frozen["contentHash"],
            "split": frozen["split"],
            "structuralCluster": frozen["structuralCluster"],
            "formResults": results,
            "supportingForms": supporting,
            "refutedForms": refuted,
            "inconclusiveForms": inconclusive,
            "confirmedByAtLeastOneForm": confirmed,
            "modelOutcomeConflict": conflict,
            "disposition": disposition,
            "uniqueInfiniteContinuationClaimAuthorized": False,
            "formalProofStatus": "NOT_EVALUATED",
            "externalNoveltyStatus": "NOT_EVALUATED",
            "publicationEligible": False,
        }
    )


def aggregate_campaign(
    campaign_id: str,
    finite_campaign: dict[str, Any],
    recurrence_campaign: dict[str, Any],
    cases: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    if finite_campaign.get("campaignId") != campaign_id:
        fail(f"finite campaign identity mismatch for {campaign_id}")
    if recurrence_campaign.get("campaignId") != campaign_id:
        fail(f"recurrence campaign identity mismatch for {campaign_id}")
    finite_evaluations = index_by(
        require_list(finite_campaign, "evaluations", campaign_id),
        "caseId",
        f"{campaign_id} finite evaluations",
    )
    recurrence_evaluations = index_by(
        require_list(recurrence_campaign, "evaluations", campaign_id),
        "caseId",
        f"{campaign_id} recurrence evaluations",
    )
    if sorted(finite_evaluations) != EXPECTED_CASES or sorted(recurrence_evaluations) != EXPECTED_CASES:
        fail(f"source evaluation matrix changed for {campaign_id}")
    rows = [
        aggregate_case(cases[case_id], finite_evaluations[case_id], recurrence_evaluations[case_id])
        for case_id in EXPECTED_CASES
    ]
    confirmed = sum(item["confirmedByAtLeastOneForm"] for item in rows)
    conflicts = sum(item["modelOutcomeConflict"] for item in rows)
    unresolved = len(rows) - confirmed
    if (confirmed, conflicts, unresolved) != (6, 1, 0):
        fail(
            f"unexpected aggregate result for {campaign_id}: "
            f"confirmed={confirmed}, conflicts={conflicts}, unresolved={unresolved}"
        )
    return add_hash(
        {
            "campaignId": campaign_id,
            "challengeId": CHALLENGE,
            "sourceCampaignContentHashes": {
                "FINITE_DIFFERENCE_POLYNOMIAL": finite_campaign["contentHash"],
                "LINEAR_RECURRENCE": recurrence_campaign["contentHash"],
            },
            "executedCandidateForms": FORMS,
            "casesConfirmedByAtLeastOneForm": confirmed,
            "casesWithRefutedAlternativeModel": conflicts,
            "casesUnresolved": unresolved,
            "caseEvaluations": rows,
            "uniqueInfiniteContinuationClaimAuthorized": False,
            "formalProofStatus": "NOT_EVALUATED",
            "externalNoveltyStatus": "NOT_EVALUATED",
            "publicationEligible": False,
        }
    )


def build_aggregate(
    corpus: dict[str, Any],
    finite_run: dict[str, Any],
    recurrence_run: dict[str, Any],
) -> dict[str, Any]:
    validate_source_run(
        finite_run,
        "regelsuche.candidate-independent-finite-sequence-adapter-run/v1",
        "FINITE_DIFFERENCE_POLYNOMIAL",
        "finite-difference source run",
    )
    validate_source_run(
        recurrence_run,
        "regelsuche.candidate-independent-linear-recurrence-adapter-run/v1",
        "LINEAR_RECURRENCE",
        "linear-recurrence source run",
    )
    for field in (
        "benchmarkId",
        "challengeId",
        "repositoryRevision",
        "caseCorpusContentHash",
        "formationProfileId",
        "formationProfileContentHash",
        "freezeReceiptContentHash",
        "combinedPreregistrationHash",
    ):
        if finite_run.get(field) != recurrence_run.get(field):
            fail(f"source runs disagree on {field}")
    require_hash(corpus, "case corpus")
    if corpus.get("contentHash") != finite_run.get("caseCorpusContentHash"):
        fail("source runs are not bound to the supplied case corpus")
    cases = index_by(
        [
            item
            for item in require_list(corpus, "cases", "case corpus")
            if isinstance(item, dict) and item.get("challengeId") == CHALLENGE
        ],
        "caseId",
        "finite-sequence cases",
    )
    if sorted(cases) != EXPECTED_CASES:
        fail(f"finite-sequence case identities changed: {sorted(cases)}")
    for case_id, item in cases.items():
        require_hash(item, f"frozen case {case_id}")

    finite_campaigns = index_by(
        require_list(finite_run, "campaigns", "finite source run"),
        "campaignId",
        "finite campaigns",
    )
    recurrence_campaigns = index_by(
        require_list(recurrence_run, "campaigns", "recurrence source run"),
        "campaignId",
        "recurrence campaigns",
    )
    campaigns = [
        aggregate_campaign(
            campaign_id,
            finite_campaigns[campaign_id],
            recurrence_campaigns[campaign_id],
            cases,
        )
        for campaign_id in EXPECTED_CAMPAIGNS
    ]
    return add_hash(
        {
            "schema": SCHEMA,
            "benchmarkId": finite_run["benchmarkId"],
            "challengeId": CHALLENGE,
            "repositoryRevision": finite_run["repositoryRevision"],
            "caseCorpusContentHash": finite_run["caseCorpusContentHash"],
            "formationProfileId": finite_run["formationProfileId"],
            "formationProfileContentHash": finite_run["formationProfileContentHash"],
            "freezeReceiptContentHash": finite_run["freezeReceiptContentHash"],
            "combinedPreregistrationHash": finite_run["combinedPreregistrationHash"],
            "sourceRuns": [
                source_descriptor(finite_run, "FINITE_DIFFERENCE_POLYNOMIAL"),
                source_descriptor(recurrence_run, "LINEAR_RECURRENCE"),
            ],
            "expectedCandidateForms": FORMS,
            "executedCandidateForms": FORMS,
            "completeCandidateFormCoverage": True,
            "configuredCampaigns": 4,
            "executedCampaigns": 4,
            "configuredCases": 6,
            "executedCases": 6,
            "casesConfirmedByAtLeastOneForm": 6,
            "casesWithRefutedAlternativeModel": 1,
            "casesUnresolved": 0,
            "challengeStatus": STATUS,
            "benchmarkStatus": BENCHMARK_STATUS,
            "uniqueInfiniteContinuationClaimAuthorized": False,
            "formalProofStatus": "NOT_EVALUATED",
            "externalNoveltyStatus": "NOT_EVALUATED",
            "publicationAuthorized": False,
            "campaigns": campaigns,
        }
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--finite-run", type=Path, required=True)
    parser.add_argument("--recurrence-run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        aggregate = build_aggregate(
            load_json(args.corpus.resolve()),
            load_json(args.finite_run.resolve()),
            load_json(args.recurrence_run.resolve()),
        )
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            json.dumps(aggregate, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        print(f"finiteSequenceFormAggregate={output}")
        print(f"contentHash={aggregate['contentHash']}")
    except (AggregateError, OSError, KeyError) as error:
        print(f"finite-sequence form aggregate failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
