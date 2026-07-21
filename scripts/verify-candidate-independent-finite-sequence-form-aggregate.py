#!/usr/bin/env python3
"""Independently verify the aggregate over both frozen finite-sequence forms."""

from __future__ import annotations

import argparse
import copy
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import shutil
import sys
from typing import Any, Callable

import jsonschema

SCHEMA = "regelsuche.candidate-independent-finite-sequence-form-aggregate/v1"
VERIFY_SCHEMA = "regelsuche.candidate-independent-finite-sequence-form-aggregate-verification/v1"
CHALLENGE = "finite-difference-recurrences"
FORMS = ["FINITE_DIFFERENCE_POLYNOMIAL", "LINEAR_RECURRENCE"]
EXPECTED_CASES = [f"case-{index:02d}" for index in range(7, 13)]
EXPECTED_CAMPAIGNS = [f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)]
EXPECTED_DISPOSITIONS = {
    "case-07": "CONFIRMED_BY_FINITE_DIFFERENCE_ONLY",
    "case-08": "CONFIRMED_BY_LINEAR_RECURRENCE_ONLY",
    "case-09": "CONFIRMED_BY_LINEAR_RECURRENCE_ONLY",
    "case-10": "CONFIRMED_BY_LINEAR_RECURRENCE_ONLY",
    "case-11": "CONFIRMED_WITH_REFUTED_ALTERNATIVE_MODEL",
    "case-12": "CONFIRMED_BY_LINEAR_RECURRENCE_ONLY",
}


class VerificationError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise VerificationError(message)


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


def exact_hash(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def require_hash(value: dict[str, Any], context: str) -> None:
    retained = value.get("contentHash")
    if not isinstance(retained, str):
        fail(f"{context} has no contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = semantic_hash(material)
    if retained != expected:
        fail(f"{context} contentHash mismatch: {retained} != {expected}")


def require_all_aggregate_hashes(run: dict[str, Any]) -> None:
    campaigns = run.get("campaigns")
    if not isinstance(campaigns, list):
        fail("aggregate campaigns are missing")
    for campaign_index, campaign in enumerate(campaigns):
        if not isinstance(campaign, dict):
            fail(f"campaign {campaign_index} is not an object")
        rows = campaign.get("caseEvaluations")
        if not isinstance(rows, list):
            fail(f"campaign {campaign_index} case evaluations are missing")
        for row_index, row in enumerate(rows):
            if not isinstance(row, dict):
                fail(f"campaign {campaign_index} row {row_index} is not an object")
            require_hash(row, f"campaign {campaign_index} row {row_index}")
        require_hash(campaign, f"campaign {campaign_index}")
    require_hash(run, "finite-sequence form aggregate")


def index_by(items: Any, field: str, context: str) -> dict[str, dict[str, Any]]:
    if not isinstance(items, list):
        fail(f"{context} is not an array")
    result: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(items):
        if not isinstance(item, dict):
            fail(f"{context} item {index} is not an object")
        identity = item.get(field)
        if not isinstance(identity, str) or not identity:
            fail(f"{context} item {index} has no {field}")
        if identity in result:
            fail(f"duplicate {context} identity {identity}")
        result[identity] = item
    return result


def difference(values: list[int]) -> list[int]:
    return [values[index + 1] - values[index] for index in range(len(values) - 1)]


def finite_difference_model(
    observed: list[int], maximum_order: int
) -> tuple[int, list[int]] | None:
    rows = [observed]
    for order in range(1, maximum_order + 1):
        if len(rows[-1]) < 2:
            break
        rows.append(difference(rows[-1]))
        constant = bool(rows[-1]) and all(value == rows[-1][0] for value in rows[-1])
        sufficient_support = len(observed) >= order + 2
        if constant and sufficient_support:
            return order, [row[0] for row in rows]
    return None


def generate_finite(initial_differences: list[int], count: int) -> list[int]:
    levels = list(initial_differences)
    generated: list[int] = []
    for _ in range(count):
        generated.append(levels[0])
        for level in range(len(levels) - 1):
            levels[level] += levels[level + 1]
    return generated


def finite_disposition(item: dict[str, Any]) -> str:
    evaluation = item.get("evaluationInput")
    if not isinstance(evaluation, dict):
        fail(f"case {item.get('caseId')} has no evaluation input")
    observed = evaluation.get("observedPrefix")
    holdout = evaluation.get("holdoutContinuation")
    maximum_order = evaluation.get("maximumOrder")
    if not isinstance(observed, list) or not all(type(value) is int for value in observed):
        fail(f"case {item.get('caseId')} has invalid observed prefix")
    if not isinstance(holdout, list) or not all(type(value) is int for value in holdout):
        fail(f"case {item.get('caseId')} has invalid holdout")
    if type(maximum_order) is not int:
        fail(f"case {item.get('caseId')} has invalid maximum order")
    model = finite_difference_model(observed, maximum_order)
    if model is None:
        return "INCONCLUSIVE"
    _, initial = model
    predicted = generate_finite(initial, len(observed) + len(holdout))[len(observed) :]
    return "CONFIRMED" if predicted == holdout else "INCONCLUSIVE"


def solve_recurrence(
    observed: list[int], maximum_order: int
) -> tuple[int, list[Fraction]] | None:
    for order in range(1, maximum_order + 1):
        if len(observed) < 2 * order:
            continue
        rows: list[list[Fraction]] = []
        for index in range(order, len(observed)):
            row = [Fraction(observed[index - offset - 1]) for offset in range(order)]
            row.append(Fraction(observed[index]))
            rows.append(row)
        pivot_row = 0
        pivot_rows = [-1] * order
        for column in range(order):
            selected = next(
                (
                    row_index
                    for row_index in range(pivot_row, len(rows))
                    if rows[row_index][column] != 0
                ),
                None,
            )
            if selected is None:
                continue
            rows[pivot_row], rows[selected] = rows[selected], rows[pivot_row]
            pivot = rows[pivot_row][column]
            rows[pivot_row] = [value / pivot for value in rows[pivot_row]]
            for row_index in range(len(rows)):
                if row_index == pivot_row or rows[row_index][column] == 0:
                    continue
                factor = rows[row_index][column]
                rows[row_index] = [
                    rows[row_index][item] - factor * rows[pivot_row][item]
                    for item in range(order + 1)
                ]
            pivot_rows[column] = pivot_row
            pivot_row += 1
        if any(
            all(value == 0 for value in row[:order]) and row[order] != 0
            for row in rows
        ):
            continue
        if any(row_index < 0 for row_index in pivot_rows):
            continue
        coefficients = [rows[pivot_rows[column]][order] for column in range(order)]
        generated = generate_recurrence(coefficients, observed, len(observed))
        if generated == [Fraction(value) for value in observed]:
            return order, coefficients
    return None


def generate_recurrence(
    coefficients: list[Fraction], observed: list[int], total_count: int
) -> list[Fraction]:
    order = len(coefficients)
    generated = [Fraction(value) for value in observed[:order]]
    while len(generated) < total_count:
        index = len(generated)
        generated.append(
            sum(
                coefficients[offset] * generated[index - offset - 1]
                for offset in range(order)
            )
        )
    return generated


def rational_text(value: Fraction) -> str:
    return str(value.numerator) if value.denominator == 1 else f"{value.numerator}/{value.denominator}"


def recurrence_expected(item: dict[str, Any]) -> dict[str, Any]:
    evaluation = item["evaluationInput"]
    observed = evaluation["observedPrefix"]
    holdout = evaluation["holdoutContinuation"]
    model = solve_recurrence(observed, evaluation["maximumOrder"])
    if model is None:
        return {
            "disposition": "INCONCLUSIVE",
            "formedModelStatus": "NO_UNIQUE_MODEL",
            "recurrenceOrder": 0,
            "coefficients": [],
            "expectedHoldout": [str(value) for value in holdout],
            "predictedHoldout": [],
        }
    order, coefficients = model
    generated = generate_recurrence(coefficients, observed, len(observed) + len(holdout))
    predicted = generated[len(observed) :]
    return {
        "disposition": (
            "CONFIRMED"
            if predicted == [Fraction(value) for value in holdout]
            else "REFUTED"
        ),
        "formedModelStatus": "UNIQUE_MODEL",
        "recurrenceOrder": order,
        "coefficients": [rational_text(value) for value in coefficients],
        "expectedHoldout": [str(value) for value in holdout],
        "predictedHoldout": [rational_text(value) for value in predicted],
    }


def validate_source_pair(first: dict[str, Any], second: dict[str, Any], context: str) -> None:
    if canonical_bytes(first) != canonical_bytes(second):
        fail(f"two clean {context} source runs are not semantically identical")
    require_hash(first, f"{context} source run")
    campaigns = first.get("campaigns")
    if not isinstance(campaigns, list) or len(campaigns) != 4:
        fail(f"{context} source campaign matrix changed")
    for campaign_index, campaign in enumerate(campaigns):
        if not isinstance(campaign, dict):
            fail(f"{context} campaign is not an object")
        require_hash(campaign, f"{context} campaign {campaign_index}")
        evaluations = campaign.get("evaluations")
        if not isinstance(evaluations, list) or len(evaluations) != 6:
            fail(f"{context} evaluation matrix changed")
        for evaluation_index, evaluation in enumerate(evaluations):
            if not isinstance(evaluation, dict):
                fail(f"{context} evaluation is not an object")
            require_hash(evaluation, f"{context} evaluation {campaign_index}/{evaluation_index}")


def validate_aggregate(
    aggregate: dict[str, Any],
    schema: dict[str, Any],
    corpus: dict[str, Any],
    finite_run: dict[str, Any],
    recurrence_run: dict[str, Any],
) -> None:
    try:
        jsonschema.Draft202012Validator(schema).validate(aggregate)
    except jsonschema.ValidationError as error:
        fail(f"aggregate schema validation failed: {error.message}")
    require_all_aggregate_hashes(aggregate)
    if aggregate.get("schema") != SCHEMA:
        fail("aggregate schema identity changed")
    if aggregate.get("caseCorpusContentHash") != corpus.get("contentHash"):
        fail("aggregate is not bound to the frozen case corpus")
    if aggregate.get("expectedCandidateForms") != FORMS:
        fail("expected candidate-form set changed")
    if aggregate.get("executedCandidateForms") != FORMS:
        fail("executed candidate-form set changed")
    if aggregate.get("completeCandidateFormCoverage") is not True:
        fail("candidate-form coverage is incomplete")
    if aggregate.get("benchmarkStatus") != "PARTIAL_OTHER_CHALLENGES_NOT_AGGREGATED":
        fail("sequence completion was overstated as benchmark completion")
    if aggregate.get("publicationAuthorized") is not False:
        fail("aggregate authorizes publication")
    if aggregate.get("uniqueInfiniteContinuationClaimAuthorized") is not False:
        fail("aggregate authorizes a unique infinite continuation claim")
    expected_sources = [
        {
            "candidateForm": "FINITE_DIFFERENCE_POLYNOMIAL",
            "schema": finite_run["schema"],
            "contentHash": finite_run["contentHash"],
        },
        {
            "candidateForm": "LINEAR_RECURRENCE",
            "schema": recurrence_run["schema"],
            "contentHash": recurrence_run["contentHash"],
        },
    ]
    if aggregate.get("sourceRuns") != expected_sources:
        fail("aggregate source-run roots changed")

    cases = index_by(
        [
            item
            for item in corpus.get("cases", [])
            if isinstance(item, dict) and item.get("challengeId") == CHALLENGE
        ],
        "caseId",
        "frozen finite-sequence cases",
    )
    if sorted(cases) != EXPECTED_CASES:
        fail(f"frozen finite-sequence cases changed: {sorted(cases)}")
    finite_campaigns = index_by(finite_run.get("campaigns"), "campaignId", "finite campaigns")
    recurrence_campaigns = index_by(
        recurrence_run.get("campaigns"), "campaignId", "recurrence campaigns"
    )
    aggregate_campaigns = index_by(
        aggregate.get("campaigns"), "campaignId", "aggregate campaigns"
    )
    if list(aggregate_campaigns) != EXPECTED_CAMPAIGNS:
        fail("aggregate campaign ordering or identities changed")

    for campaign_id in EXPECTED_CAMPAIGNS:
        campaign = aggregate_campaigns[campaign_id]
        finite_campaign = finite_campaigns[campaign_id]
        recurrence_campaign = recurrence_campaigns[campaign_id]
        expected_roots = {
            "FINITE_DIFFERENCE_POLYNOMIAL": finite_campaign["contentHash"],
            "LINEAR_RECURRENCE": recurrence_campaign["contentHash"],
        }
        if campaign.get("sourceCampaignContentHashes") != expected_roots:
            fail(f"source campaign roots changed for {campaign_id}")
        rows = index_by(
            campaign.get("caseEvaluations"), "caseId", f"{campaign_id} aggregate rows"
        )
        if list(rows) != EXPECTED_CASES:
            fail(f"aggregate case matrix changed for {campaign_id}")
        finite_rows = index_by(
            finite_campaign.get("evaluations"), "caseId", f"{campaign_id} finite rows"
        )
        recurrence_rows = index_by(
            recurrence_campaign.get("evaluations"),
            "caseId",
            f"{campaign_id} recurrence rows",
        )
        conflicts = 0
        for case_id in EXPECTED_CASES:
            frozen = cases[case_id]
            row = rows[case_id]
            if row.get("caseContentHash") != frozen.get("contentHash"):
                fail(f"case hash mismatch for {campaign_id}/{case_id}")
            if row.get("split") != frozen.get("split"):
                fail(f"split mismatch for {campaign_id}/{case_id}")
            if row.get("structuralCluster") != frozen.get("structuralCluster"):
                fail(f"cluster mismatch for {campaign_id}/{case_id}")
            form_results = row.get("formResults")
            if not isinstance(form_results, list) or len(form_results) != 2:
                fail(f"form results missing for {campaign_id}/{case_id}")
            finite_result, recurrence_result = form_results
            independent_finite = finite_disposition(frozen)
            independent_recurrence = recurrence_expected(frozen)
            if finite_result.get("disposition") != independent_finite:
                fail(f"finite arithmetic mismatch for {campaign_id}/{case_id}")
            for field, expected in independent_recurrence.items():
                if recurrence_result.get(field) != expected:
                    fail(
                        f"recurrence field {field} mismatch for "
                        f"{campaign_id}/{case_id}: {recurrence_result.get(field)!r} != {expected!r}"
                    )
            if finite_result.get("sourceEvaluationContentHash") != finite_rows[case_id].get("contentHash"):
                fail(f"finite source root mismatch for {campaign_id}/{case_id}")
            if recurrence_result.get("sourceEvaluationContentHash") != recurrence_rows[case_id].get("contentHash"):
                fail(f"recurrence source root mismatch for {campaign_id}/{case_id}")
            supporting = [
                result["candidateForm"]
                for result in form_results
                if result.get("disposition") == "CONFIRMED"
            ]
            refuted = [
                result["candidateForm"]
                for result in form_results
                if result.get("disposition") == "REFUTED"
            ]
            inconclusive = [
                result["candidateForm"]
                for result in form_results
                if result.get("disposition") == "INCONCLUSIVE"
            ]
            if row.get("supportingForms") != supporting:
                fail(f"supporting forms mismatch for {campaign_id}/{case_id}")
            if row.get("refutedForms") != refuted:
                fail(f"refuted forms mismatch for {campaign_id}/{case_id}")
            if row.get("inconclusiveForms") != inconclusive:
                fail(f"inconclusive forms mismatch for {campaign_id}/{case_id}")
            conflict = bool(supporting and refuted)
            if row.get("modelOutcomeConflict") is not conflict:
                fail(f"model outcome conflict mismatch for {campaign_id}/{case_id}")
            if conflict:
                conflicts += 1
            if row.get("disposition") != EXPECTED_DISPOSITIONS[case_id]:
                fail(f"aggregate disposition mismatch for {campaign_id}/{case_id}")
            if row.get("confirmedByAtLeastOneForm") is not True:
                fail(f"confirmed case became unresolved for {campaign_id}/{case_id}")
            if row.get("uniqueInfiniteContinuationClaimAuthorized") is not False:
                fail(f"unique continuation claim authorized for {campaign_id}/{case_id}")
        if conflicts != 1:
            fail(f"expected exactly one retained model conflict in {campaign_id}")
        if campaign.get("casesConfirmedByAtLeastOneForm") != 6:
            fail(f"campaign confirmation accounting changed for {campaign_id}")
        if campaign.get("casesWithRefutedAlternativeModel") != 1:
            fail(f"campaign conflict accounting changed for {campaign_id}")
        if campaign.get("casesUnresolved") != 0:
            fail(f"campaign unresolved accounting changed for {campaign_id}")


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def rehash_aggregate(run: dict[str, Any]) -> None:
    for campaign in run.get("campaigns", []):
        for row in campaign.get("caseEvaluations", []):
            rehash(row)
        rehash(campaign)
    rehash(run)


def require_rejected(
    name: str,
    baseline: dict[str, Any],
    mutate: Callable[[dict[str, Any]], None],
    validate: Callable[[dict[str, Any]], None],
) -> str:
    candidate = copy.deepcopy(baseline)
    mutate(candidate)
    rehash_aggregate(candidate)
    try:
        validate(candidate)
    except VerificationError as error:
        return f"{name}: {error}"
    fail(f"negative case {name} was accepted")


def write_verification(
    directory: Path,
    aggregate: dict[str, Any],
    aggregate_path: Path,
    negatives: list[str],
) -> None:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    report = {
        "schema": VERIFY_SCHEMA,
        "status": "VERIFIED_COMPLETE_FINITE_SEQUENCE_CANDIDATE_FORM_EXECUTION",
        "aggregateContentHash": aggregate["contentHash"],
        "aggregateFileSha256": exact_hash(aggregate_path),
        "campaignsVerified": 4,
        "casesPerCampaignVerified": 6,
        "candidateFormsVerified": FORMS,
        "casesConfirmedByAtLeastOneForm": 6,
        "casesWithRefutedAlternativeModel": 1,
        "casesUnresolved": 0,
        "completeCandidateFormCoverage": True,
        "benchmarkStatus": "PARTIAL_OTHER_CHALLENGES_NOT_AGGREGATED",
        "uniqueInfiniteContinuationClaimAuthorized": False,
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
        "negativeCases": negatives,
    }
    report["contentHash"] = semantic_hash(report)
    path = directory / "verification.json"
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"finiteSequenceFormAggregateVerification={path}")
    print(f"contentHash={report['contentHash']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--finite-first", type=Path, required=True)
    parser.add_argument("--finite-second", type=Path, required=True)
    parser.add_argument("--recurrence-first", type=Path, required=True)
    parser.add_argument("--recurrence-second", type=Path, required=True)
    parser.add_argument("--aggregate-first", type=Path, required=True)
    parser.add_argument("--aggregate-second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    root = args.repository_root.resolve()
    try:
        schema = load_json(
            root
            / "docs/schemas/regelsuche-candidate-independent-finite-sequence-form-aggregate-v1.schema.json"
        )
        corpus = load_json(
            root / "research/benchmarks/candidate-independent/case-corpus.json"
        )
        finite_first = load_json(args.finite_first.resolve())
        finite_second = load_json(args.finite_second.resolve())
        recurrence_first = load_json(args.recurrence_first.resolve())
        recurrence_second = load_json(args.recurrence_second.resolve())
        validate_source_pair(finite_first, finite_second, "finite-difference")
        validate_source_pair(recurrence_first, recurrence_second, "linear-recurrence")
        first_path = args.aggregate_first.resolve()
        second_path = args.aggregate_second.resolve()
        if first_path.read_bytes() != second_path.read_bytes():
            fail("two clean aggregate runs are not byte-identical")
        aggregate_first = load_json(first_path)
        aggregate_second = load_json(second_path)
        validate = lambda value: validate_aggregate(
            value, schema, corpus, finite_first, recurrence_first
        )
        validate(aggregate_first)
        validate(aggregate_second)
        negatives = [
            require_rejected(
                "hidden-refuted-alternative",
                aggregate_first,
                lambda value: value["campaigns"][0]["caseEvaluations"][4].update(
                    {
                        "refutedForms": [],
                        "modelOutcomeConflict": False,
                        "disposition": "CONFIRMED_BY_FINITE_DIFFERENCE_ONLY",
                    }
                ),
                validate,
            ),
            require_rejected(
                "unique-infinite-continuation-overclaim",
                aggregate_first,
                lambda value: value["campaigns"][0]["caseEvaluations"][0].update(
                    {"uniqueInfiniteContinuationClaimAuthorized": True}
                ),
                validate,
            ),
            require_rejected(
                "missing-case-row",
                aggregate_first,
                lambda value: value["campaigns"][0]["caseEvaluations"].pop(),
                validate,
            ),
            require_rejected(
                "benchmark-and-publication-overclaim",
                aggregate_first,
                lambda value: value.update(
                    {
                        "benchmarkStatus": "COMPLETE",
                        "publicationAuthorized": True,
                    }
                ),
                validate,
            ),
            require_rejected(
                "source-run-root-substitution",
                aggregate_first,
                lambda value: value["sourceRuns"][0].update(
                    {"contentHash": value["sourceRuns"][1]["contentHash"]}
                ),
                validate,
            ),
        ]
        write_verification(
            args.report_directory.resolve(), aggregate_first, first_path, negatives
        )
    except (VerificationError, OSError, jsonschema.SchemaError, KeyError) as error:
        print(f"finite-sequence form aggregate verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
