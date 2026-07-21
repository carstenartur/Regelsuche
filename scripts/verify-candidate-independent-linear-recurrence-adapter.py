#!/usr/bin/env python3
"""Independently verify the frozen linear-recurrence adapter slice for issue #383."""

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

RUN_SCHEMA = "regelsuche.candidate-independent-linear-recurrence-adapter-run/v1"
VERIFY_SCHEMA = (
    "regelsuche.candidate-independent-linear-recurrence-adapter-verification/v1"
)
CHALLENGE = "finite-difference-recurrences"
PROFILE_ID = "finite-sequence-candidate-forms/v1"
FORM = "LINEAR_RECURRENCE"
ADAPTER_STATUS = "POST_FREEZE_ADAPTER_EXECUTION_WITH_FROZEN_PROFILE_RETAINED"
EXPECTED_CAMPAIGNS = [
    f"{CHALLENGE}-campaign-{index:02d}" for index in range(1, 5)
]
EXPECTED_CASES = [f"case-{index:02d}" for index in range(7, 13)]
TRAIN_CASES = ["case-07", "case-08"]


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


def require_content_hash(value: dict[str, Any], context: str) -> None:
    retained = value.get("contentHash")
    if not isinstance(retained, str):
        fail(f"{context} has no contentHash")
    material = dict(value)
    material.pop("contentHash", None)
    expected = semantic_hash(material)
    if retained != expected:
        fail(f"{context} contentHash mismatch: {retained} != {expected}")


def require_all_hashes(run: dict[str, Any]) -> None:
    for campaign_index, campaign in enumerate(run.get("campaigns", [])):
        if not isinstance(campaign, dict):
            fail(f"campaign {campaign_index} is not an object")
        for formation_index, formation in enumerate(
            campaign.get("formationEvidence", [])
        ):
            if not isinstance(formation, dict):
                fail(
                    f"campaign {campaign_index} formation {formation_index} "
                    "is not an object"
                )
            require_content_hash(
                formation,
                f"campaign {campaign_index} formation {formation_index}",
            )
        for evaluation_index, evaluation in enumerate(
            campaign.get("evaluations", [])
        ):
            if not isinstance(evaluation, dict):
                fail(
                    f"campaign {campaign_index} evaluation {evaluation_index} "
                    "is not an object"
                )
            require_content_hash(
                evaluation,
                f"campaign {campaign_index} evaluation {evaluation_index}",
            )
        require_content_hash(campaign, f"campaign {campaign_index}")
    require_content_hash(run, "linear-recurrence adapter run")


def validate_frozen_inputs(
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    require_content_hash(corpus, "case corpus")
    require_content_hash(profile, "finite-sequence profile")
    require_content_hash(receipt, "corpus-freeze receipt")
    if corpus.get("contentHash") != receipt.get("caseCorpusContentHash"):
        fail("case corpus is not bound by the freeze receipt")
    profile_hashes = receipt.get("formationInventoryContentHashes")
    if not isinstance(profile_hashes, dict):
        fail("freeze receipt has no formation inventory roots")
    if profile.get("contentHash") != profile_hashes.get(PROFILE_ID):
        fail("finite-sequence profile is not bound by the freeze receipt")
    if receipt.get("executionStatusAtFreeze") != "NOT_STARTED":
        fail("corpus was not frozen before evaluated execution")
    if receipt.get("executedCampaignsAtFreeze") != 0:
        fail("freeze receipt already contains campaigns")
    if receipt.get("executedEvaluationsAtFreeze") != 0:
        fail("freeze receipt already contains evaluations")
    if receipt.get("publicationAuthorized") is not False:
        fail("freeze receipt unexpectedly authorizes publication")
    if receipt.get("allowedNextStep") != (
        "IMPLEMENT_EXECUTION_ADAPTERS_WITHOUT_MODIFYING_FROZEN_CASE_PAYLOADS"
    ):
        fail("freeze receipt does not authorize the adapter step")

    forms = {
        item.get("formId"): item
        for item in profile.get("forms", [])
        if isinstance(item, dict)
    }
    recurrence = forms.get(FORM)
    if not isinstance(recurrence, dict):
        fail("frozen profile has no LINEAR_RECURRENCE form")
    if recurrence.get("implementationStatus") != "ADAPTER_REQUIRED":
        fail("frozen recurrence limitation was rewritten")
    if "implementationClass" in recurrence:
        fail("frozen recurrence profile was amended post hoc")

    cases = {
        item.get("caseId"): item
        for item in corpus.get("cases", [])
        if isinstance(item, dict) and item.get("challengeId") == CHALLENGE
    }
    if sorted(cases) != EXPECTED_CASES:
        fail(f"finite-sequence case identities changed: {sorted(cases)}")
    for case_id, item in cases.items():
        require_content_hash(item, f"frozen case {case_id}")
        split = item.get("split")
        formation = item.get("formationInput")
        exposure = item.get("exposurePolicy")
        if not isinstance(exposure, dict):
            fail(f"case {case_id} has no exposure policy")
        if split == "TRAIN":
            if not isinstance(formation, dict):
                fail(f"TRAIN case {case_id} has no formation input")
            if exposure.get("candidateFormationMayRead") != ["formationInput"]:
                fail(f"TRAIN case {case_id} formation surface changed")
            if formation.get("holdoutVisible") is not False:
                fail(f"TRAIN case {case_id} exposes its holdout")
        else:
            if formation is not None:
                fail(f"held-out case {case_id} has a formation input")
            if exposure.get("candidateFormationMayRead") != []:
                fail(f"held-out case {case_id} exposes formation data")
        if exposure.get("candidateFormationMustNotRead") != ["evaluationInput"]:
            fail(f"case {case_id} does not prohibit evaluation input in formation")
    return cases


def solve_unique_recurrence(
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
                    row
                    for row in range(pivot_row, len(rows))
                    if rows[row][column] != 0
                ),
                None,
            )
            if selected is None:
                continue
            rows[pivot_row], rows[selected] = rows[selected], rows[pivot_row]
            pivot = rows[pivot_row][column]
            rows[pivot_row] = [value / pivot for value in rows[pivot_row]]
            for row in range(len(rows)):
                if row == pivot_row or rows[row][column] == 0:
                    continue
                factor = rows[row][column]
                rows[row] = [
                    rows[row][item] - factor * rows[pivot_row][item]
                    for item in range(order + 1)
                ]
            pivot_rows[column] = pivot_row
            pivot_row += 1
        if any(
            all(value == 0 for value in row[:order]) and row[order] != 0
            for row in rows
        ):
            continue
        if any(row < 0 for row in pivot_rows):
            continue
        coefficients = [rows[pivot_rows[column]][order] for column in range(order)]
        generated = generate(coefficients, observed, len(observed))
        if generated == [Fraction(value) for value in observed]:
            return order, coefficients
    return None


def generate(
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


def continuation(
    model: tuple[int, list[Fraction]], observed: list[int], count: int
) -> list[Fraction]:
    _, coefficients = model
    generated = generate(coefficients, observed, len(observed) + count)
    return generated[len(observed) :]


def rational_text(value: Fraction) -> str:
    return str(value.numerator) if value.denominator == 1 else f"{value.numerator}/{value.denominator}"


def expected_case(item: dict[str, Any]) -> dict[str, Any]:
    evaluation = item.get("evaluationInput")
    if not isinstance(evaluation, dict):
        fail(f"case {item.get('caseId')} has no evaluation input")
    observed = evaluation.get("observedPrefix")
    holdout = evaluation.get("holdoutContinuation")
    maximum_order = evaluation.get("maximumOrder")
    if not isinstance(observed, list) or not all(isinstance(value, int) for value in observed):
        fail(f"case {item.get('caseId')} observed prefix is invalid")
    if not isinstance(holdout, list) or not all(isinstance(value, int) for value in holdout):
        fail(f"case {item.get('caseId')} holdout is invalid")
    if not isinstance(maximum_order, int):
        fail(f"case {item.get('caseId')} maximum order is invalid")
    model = solve_unique_recurrence(observed, maximum_order)
    if model is None:
        return {
            "formedModelStatus": "NO_UNIQUE_MODEL",
            "recurrenceOrder": 0,
            "coefficients": [],
            "expectedHoldout": [str(value) for value in holdout],
            "predictedHoldout": [],
            "productionOutcome": "INCONCLUSIVE",
            "outcome": "NO_UNIQUE_LINEAR_RECURRENCE",
            "reasonCode": "NO_UNIQUE_MODEL_WITHIN_FROZEN_ORDER_BOUND",
        }
    order, coefficients = model
    predicted = continuation(model, observed, len(holdout))
    if predicted == [Fraction(value) for value in holdout]:
        production = "CONFIRMED"
        outcome = "CONFIRMED_LINEAR_RECURRENCE_FIT"
        reason = "LINEAR_RECURRENCE_HOLDOUT_CONFIRMED"
    else:
        production = "REFUTED"
        outcome = "REFUTED_LINEAR_RECURRENCE_FIT"
        reason = "OBSERVED_PREFIX_RECURRENCE_REFUTED_BY_HOLDOUT"
    return {
        "formedModelStatus": "UNIQUE_MODEL",
        "recurrenceOrder": order,
        "coefficients": [rational_text(value) for value in coefficients],
        "expectedHoldout": [str(value) for value in holdout],
        "predictedHoldout": [rational_text(value) for value in predicted],
        "productionOutcome": production,
        "outcome": outcome,
        "reasonCode": reason,
    }


def configured_seed(benchmark_id: str, campaign_id: str, index: int) -> str:
    return semantic_hash(
        {
            "benchmarkId": benchmark_id,
            "campaignId": campaign_id,
            "challengeId": CHALLENGE,
            "index": index,
        }
    )


def validate_resource_use(value: Any, context: str) -> None:
    if not isinstance(value, dict):
        fail(f"{context} resourceUse is not an object")
    for field in ("exploredStates", "generatedSuccessors", "candidateAttempts"):
        if not isinstance(value.get(field), int) or value[field] < 0:
            fail(f"{context} has invalid resource field {field}")
    if value.get("proofAttempts") != 0:
        fail(f"{context} invents proof attempts")


def validate_run(
    run: dict[str, Any],
    schema: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    cases: dict[str, dict[str, Any]],
) -> tuple[int, int, int]:
    try:
        jsonschema.Draft202012Validator(schema).validate(run)
    except jsonschema.ValidationError as error:
        fail(f"run schema validation failed: {error.message}")
    require_all_hashes(run)

    bindings = {
        "schema": RUN_SCHEMA,
        "benchmarkId": corpus.get("benchmarkId"),
        "challengeId": CHALLENGE,
        "caseCorpusContentHash": corpus.get("contentHash"),
        "formationProfileId": PROFILE_ID,
        "formationProfileContentHash": profile.get("contentHash"),
        "freezeReceiptContentHash": receipt.get("contentHash"),
        "combinedPreregistrationHash": receipt.get("combinedPreregistrationHash"),
        "adapterStatus": ADAPTER_STATUS,
        "candidateForm": FORM,
        "candidateFormImplementationClass": (
            "de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain"
        ),
        "frozenImplementationStatus": "ADAPTER_REQUIRED",
        "runtimeImplementationStatus": "AVAILABLE_AFTER_FREEZE",
        "frozenProfileModified": False,
        "configuredCampaigns": 4,
        "executedCampaigns": 4,
        "configuredEvaluations": 24,
        "executedEvaluations": 24,
        "uniqueInfiniteContinuationClaimAuthorized": False,
        "formalProofStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
    }
    for field, expected in bindings.items():
        if run.get(field) != expected:
            fail(f"run binding {field} changed: {run.get(field)!r} != {expected!r}")

    campaigns = run.get("campaigns")
    if not isinstance(campaigns, list):
        fail("campaigns is not an array")
    if [item.get("campaignId") for item in campaigns] != EXPECTED_CAMPAIGNS:
        fail("campaign identities or ordering changed")

    expected_by_case = {case_id: expected_case(item) for case_id, item in cases.items()}
    expected_outcomes = {
        "case-07": "NO_UNIQUE_LINEAR_RECURRENCE",
        "case-08": "CONFIRMED_LINEAR_RECURRENCE_FIT",
        "case-09": "CONFIRMED_LINEAR_RECURRENCE_FIT",
        "case-10": "CONFIRMED_LINEAR_RECURRENCE_FIT",
        "case-11": "REFUTED_LINEAR_RECURRENCE_FIT",
        "case-12": "CONFIRMED_LINEAR_RECURRENCE_FIT",
    }
    if {case_id: value["outcome"] for case_id, value in expected_by_case.items()} != expected_outcomes:
        fail("independent recurrence calculation no longer matches the frozen case contract")

    counts = {
        "CONFIRMED_LINEAR_RECURRENCE_FIT": 0,
        "REFUTED_LINEAR_RECURRENCE_FIT": 0,
        "NO_UNIQUE_LINEAR_RECURRENCE": 0,
    }
    evaluation_roots: set[str] = set()
    for index, campaign in enumerate(campaigns, start=1):
        campaign_id = EXPECTED_CAMPAIGNS[index - 1]
        if campaign.get("configuredSeed") != configured_seed(
            run["benchmarkId"], campaign_id, index
        ):
            fail(f"configured seed mismatch for {campaign_id}")
        if campaign.get("formationCaseIds") != TRAIN_CASES:
            fail(f"formation case set changed for {campaign_id}")
        if campaign.get("candidateForm") != FORM:
            fail(f"candidate form changed for {campaign_id}")
        if campaign.get("frozenImplementationStatus") != "ADAPTER_REQUIRED":
            fail(f"frozen limitation disappeared in {campaign_id}")
        if campaign.get("runtimeImplementationStatus") != "AVAILABLE_AFTER_FREEZE":
            fail(f"runtime implementation status changed in {campaign_id}")
        if campaign.get("publicationEligible") is not False:
            fail(f"campaign {campaign_id} authorizes publication")

        formation = campaign.get("formationEvidence")
        if not isinstance(formation, list):
            fail(f"formation evidence missing in {campaign_id}")
        if [item.get("caseId") for item in formation] != TRAIN_CASES:
            fail(f"formation evidence identities changed in {campaign_id}")
        for item in formation:
            case_id = item.get("caseId")
            frozen = cases[case_id]
            formation_input = frozen["formationInput"]
            observed = formation_input["observedPrefix"]
            maximum_order = formation_input["maximumOrder"]
            model = solve_unique_recurrence(observed, maximum_order)
            if model is None:
                expected_source = "VISIBLE_PREFIX_LAST_TERM_FALLBACK"
                expected_synthetic = observed[-1]
                expected_status = "NO_UNIQUE_MODEL"
                expected_order = 0
                expected_coefficients: list[str] = []
                expected_production = "INCONCLUSIVE"
                expected_selection = "NO_UNIQUE_LINEAR_RECURRENCE"
            else:
                next_value = continuation(model, observed, 1)[0]
                if next_value.denominator != 1:
                    fail(f"TRAIN model for {case_id} has non-integral synthetic continuation")
                expected_source = "UNIQUE_VISIBLE_PREFIX_RECURRENCE_PREDICTION"
                expected_synthetic = next_value.numerator
                expected_status = "UNIQUE_MODEL"
                expected_order = model[0]
                expected_coefficients = [rational_text(value) for value in model[1]]
                expected_production = "CONFIRMED"
                expected_selection = "SELECTED"
            expected_fields = {
                "caseContentHash": frozen.get("contentHash"),
                "inputSurface": "formationInput",
                "evaluationInputRead": False,
                "holdoutVisible": False,
                "syntheticHoldoutSource": expected_source,
                "syntheticHoldout": expected_synthetic,
                "maximumOrder": maximum_order,
                "formedModelStatus": expected_status,
                "recurrenceOrder": expected_order,
                "coefficients": expected_coefficients,
                "productionOutcome": expected_production,
                "candidateFormStatus": expected_selection,
                "formalProofStatus": "NOT_EVALUATED",
                "externalNoveltyStatus": "NOT_EVALUATED",
            }
            for field, expected in expected_fields.items():
                if item.get(field) != expected:
                    fail(
                        f"formation field {field} mismatch for {campaign_id}/{case_id}: "
                        f"{item.get(field)!r} != {expected!r}"
                    )
            validate_resource_use(item.get("resourceUse"), f"{campaign_id}/{case_id}")

        evaluations = campaign.get("evaluations")
        if not isinstance(evaluations, list):
            fail(f"evaluations missing in {campaign_id}")
        if [item.get("caseId") for item in evaluations] != EXPECTED_CASES:
            fail(f"evaluation matrix changed in {campaign_id}")
        for item in evaluations:
            case_id = item.get("caseId")
            frozen = cases[case_id]
            expected = expected_by_case[case_id]
            expected_visibility = (
                "ALLOWED" if frozen.get("split") == "TRAIN" else "PROHIBITED"
            )
            fixed = {
                "caseContentHash": frozen.get("contentHash"),
                "split": frozen.get("split"),
                "structuralCluster": frozen.get("structuralCluster"),
                "formationVisibility": expected_visibility,
                "heldOutInputReadStage": "EVALUATION_ONLY",
                "candidateForm": FORM,
                "uniqueInfiniteContinuationClaimAuthorized": False,
                "formalProofStatus": "NOT_EVALUATED",
                "externalNoveltyStatus": "NOT_EVALUATED",
                "publicationEligible": False,
            }
            for field, expected_value in fixed.items():
                if item.get(field) != expected_value:
                    fail(
                        f"evaluation field {field} mismatch for {campaign_id}/{case_id}"
                    )
            for field, expected_value in expected.items():
                if item.get(field) != expected_value:
                    fail(
                        f"independent recurrence field {field} mismatch for "
                        f"{campaign_id}/{case_id}: {item.get(field)!r} != {expected_value!r}"
                    )
            validate_resource_use(item.get("resourceUse"), f"{campaign_id}/{case_id}")
            root = item.get("contentHash")
            if root in evaluation_roots:
                fail(f"duplicate evaluation root: {root}")
            evaluation_roots.add(root)
            counts[item["outcome"]] += 1

    confirmed = counts["CONFIRMED_LINEAR_RECURRENCE_FIT"]
    refuted = counts["REFUTED_LINEAR_RECURRENCE_FIT"]
    inconclusive = counts["NO_UNIQUE_LINEAR_RECURRENCE"]
    if (confirmed, refuted, inconclusive) != (16, 4, 4):
        fail(
            "recurrence accounting changed: "
            f"confirmed={confirmed}, refuted={refuted}, inconclusive={inconclusive}"
        )
    if run.get("confirmedLinearRecurrenceEvaluations") != confirmed:
        fail("top-level confirmed count does not match raw evaluations")
    if run.get("refutedLinearRecurrenceEvaluations") != refuted:
        fail("top-level refuted count does not match raw evaluations")
    if run.get("inconclusiveLinearRecurrenceEvaluations") != inconclusive:
        fail("top-level inconclusive count does not match raw evaluations")
    return confirmed, refuted, inconclusive


def rehash(value: dict[str, Any]) -> None:
    value.pop("contentHash", None)
    value["contentHash"] = semantic_hash(value)


def rehash_run(run: dict[str, Any]) -> None:
    for campaign in run.get("campaigns", []):
        for formation in campaign.get("formationEvidence", []):
            rehash(formation)
        for evaluation in campaign.get("evaluations", []):
            rehash(evaluation)
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
    rehash_run(candidate)
    try:
        validate(candidate)
    except VerificationError as error:
        return f"{name}: {error}"
    fail(f"negative case {name} was accepted")


def write_verification(
    directory: Path,
    run: dict[str, Any],
    run_path: Path,
    confirmed: int,
    refuted: int,
    inconclusive: int,
    negatives: list[str],
) -> None:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    report = {
        "schema": VERIFY_SCHEMA,
        "status": "VERIFIED_LINEAR_RECURRENCE_ADAPTER",
        "runContentHash": run["contentHash"],
        "runFileSha256": exact_hash(run_path),
        "campaignsVerified": 4,
        "evaluationsVerified": 24,
        "confirmedLinearRecurrenceEvaluations": confirmed,
        "refutedLinearRecurrenceEvaluations": refuted,
        "inconclusiveLinearRecurrenceEvaluations": inconclusive,
        "frozenImplementationStatus": "ADAPTER_REQUIRED",
        "runtimeImplementationStatus": "AVAILABLE_AFTER_FREEZE",
        "independentArithmetic": "PYTHON_FRACTIONS_GAUSSIAN_ELIMINATION",
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
    print(f"linearRecurrenceAdapterVerification={path}")
    print(f"contentHash={report['contentHash']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    root = args.repository_root.resolve()
    first_path = args.first.resolve()
    second_path = args.second.resolve()
    try:
        schema = load_json(
            root
            / "docs/schemas/regelsuche-candidate-independent-linear-recurrence-adapter-run-v1.schema.json"
        )
        corpus = load_json(
            root / "research/benchmarks/candidate-independent/case-corpus.json"
        )
        profile = load_json(
            root
            / "research/benchmarks/candidate-independent/finite-sequence-candidate-forms.json"
        )
        receipt = load_json(
            root
            / "research/benchmarks/candidate-independent/corpus-freeze-receipt.json"
        )
        cases = validate_frozen_inputs(corpus, profile, receipt)
        if first_path.read_bytes() != second_path.read_bytes():
            fail("two clean linear-recurrence adapter runs are not byte-identical")
        first = load_json(first_path)
        second = load_json(second_path)
        validate = lambda value: validate_run(
            value, schema, corpus, profile, receipt, cases
        )
        confirmed, refuted, inconclusive = validate(first)
        validate(second)
        negatives = [
            require_rejected(
                "heldout-formation-leakage",
                first,
                lambda value: value["campaigns"][0]["formationEvidence"][0].update(
                    {"evaluationInputRead": True}
                ),
                validate,
            ),
            require_rejected(
                "missing-evaluation-row",
                first,
                lambda value: value["campaigns"][0]["evaluations"].pop(),
                validate,
            ),
            require_rejected(
                "rewritten-frozen-status",
                first,
                lambda value: value.update({"frozenImplementationStatus": "AVAILABLE"}),
                validate,
            ),
            require_rejected(
                "hidden-refutation",
                first,
                lambda value: value["campaigns"][0]["evaluations"][4].update(
                    {
                        "productionOutcome": "CONFIRMED",
                        "outcome": "CONFIRMED_LINEAR_RECURRENCE_FIT",
                        "reasonCode": "LINEAR_RECURRENCE_HOLDOUT_CONFIRMED",
                    }
                ),
                validate,
            ),
            require_rejected(
                "premature-publication",
                first,
                lambda value: value.update({"publicationAuthorized": True}),
                validate,
            ),
        ]
        write_verification(
            args.report_directory.resolve(),
            first,
            first_path,
            confirmed,
            refuted,
            inconclusive,
            negatives,
        )
    except (VerificationError, OSError, jsonschema.SchemaError) as error:
        print(f"linear-recurrence adapter verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
