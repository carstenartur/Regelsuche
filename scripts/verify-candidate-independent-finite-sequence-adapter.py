#!/usr/bin/env python3
"""Independently verify the frozen finite-sequence adapter slice for issue #383."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import shutil
import sys
from typing import Any, Callable

import jsonschema

RUN_SCHEMA = "regelsuche.candidate-independent-finite-sequence-adapter-run/v1"
VERIFY_SCHEMA = (
    "regelsuche.candidate-independent-finite-sequence-adapter-verification/v1"
)
CHALLENGE = "finite-difference-recurrences"
PROFILE_ID = "finite-sequence-candidate-forms/v1"
AVAILABLE_FORM = "FINITE_DIFFERENCE_POLYNOMIAL"
MISSING_FORM = "LINEAR_RECURRENCE"
PARTIAL_STATUS = "PARTIAL_EXECUTION_WITH_INCOMPLETE_ADAPTER_COVERAGE"
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
    require_content_hash(run, "adapter run")


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
        fail("freeze receipt already contains executed campaigns")
    if receipt.get("executedEvaluationsAtFreeze") != 0:
        fail("freeze receipt already contains executed evaluations")
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
    if forms.get(AVAILABLE_FORM, {}).get("implementationStatus") != "AVAILABLE":
        fail("finite-difference form is not AVAILABLE")
    if forms.get(MISSING_FORM, {}).get("implementationStatus") != "ADAPTER_REQUIRED":
        fail("linear-recurrence limitation is not retained")

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


def configured_seed(benchmark_id: str, campaign_id: str, index: int) -> str:
    return semantic_hash(
        {
            "benchmarkId": benchmark_id,
            "campaignId": campaign_id,
            "challengeId": CHALLENGE,
            "index": index,
        }
    )


def validate_run(
    run: dict[str, Any],
    schema: dict[str, Any],
    corpus: dict[str, Any],
    profile: dict[str, Any],
    receipt: dict[str, Any],
    cases: dict[str, dict[str, Any]],
) -> tuple[int, int]:
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
        "adapterStatus": PARTIAL_STATUS,
        "availableCandidateForm": AVAILABLE_FORM,
        "recurrenceAdapterStatus": "ADAPTER_REQUIRED",
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

    confirmed = 0
    incomplete = 0
    evaluation_roots: set[str] = set()
    for index, campaign in enumerate(campaigns, start=1):
        campaign_id = EXPECTED_CAMPAIGNS[index - 1]
        if campaign.get("configuredSeed") != configured_seed(
            run["benchmarkId"], campaign_id, index
        ):
            fail(f"configured seed mismatch for {campaign_id}")
        if campaign.get("formationCaseIds") != TRAIN_CASES:
            fail(f"formation case set changed for {campaign_id}")
        if campaign.get("candidateForm") != AVAILABLE_FORM:
            fail(f"unsupported candidate form in {campaign_id}")
        if campaign.get("recurrenceAdapterStatus") != "ADAPTER_REQUIRED":
            fail(f"recurrence limitation disappeared in {campaign_id}")
        if campaign.get("publicationEligible") is not False:
            fail(f"campaign {campaign_id} authorizes publication")

        formation = campaign.get("formationEvidence")
        if not isinstance(formation, list):
            fail(f"formation evidence missing in {campaign_id}")
        if [item.get("caseId") for item in formation] != TRAIN_CASES:
            fail(f"formation evidence identities changed in {campaign_id}")
        if not any(item.get("candidateFormStatus") == "SELECTED" for item in formation):
            fail(f"campaign {campaign_id} selected no available form")
        for item in formation:
            case_id = item.get("caseId")
            if item.get("caseContentHash") != cases[case_id].get("contentHash"):
                fail(f"formation case hash mismatch for {campaign_id}/{case_id}")
            if item.get("inputSurface") != "formationInput":
                fail(f"formation input surface changed for {campaign_id}/{case_id}")
            if item.get("evaluationInputRead") is not False:
                fail(f"formation read evaluator-only input for {campaign_id}/{case_id}")
            if item.get("holdoutVisible") is not False:
                fail(f"formation exposed a holdout for {campaign_id}/{case_id}")
            if item.get("syntheticHoldoutSource") != (
                "DERIVED_FROM_OBSERVED_PREFIX_ONLY"
            ):
                fail(f"formation synthetic holdout source changed for {case_id}")

        evaluations = campaign.get("evaluations")
        if not isinstance(evaluations, list):
            fail(f"evaluations missing in {campaign_id}")
        if [item.get("caseId") for item in evaluations] != EXPECTED_CASES:
            fail(f"evaluation matrix changed in {campaign_id}")
        for item in evaluations:
            case_id = item.get("caseId")
            frozen = cases[case_id]
            if item.get("caseContentHash") != frozen.get("contentHash"):
                fail(f"evaluation case hash mismatch for {campaign_id}/{case_id}")
            if item.get("split") != frozen.get("split"):
                fail(f"evaluation split mismatch for {campaign_id}/{case_id}")
            expected_visibility = (
                "ALLOWED" if frozen.get("split") == "TRAIN" else "PROHIBITED"
            )
            if item.get("formationVisibility") != expected_visibility:
                fail(f"formation visibility mismatch for {campaign_id}/{case_id}")
            if item.get("heldOutInputReadStage") != "EVALUATION_ONLY":
                fail(f"heldout input stage changed for {campaign_id}/{case_id}")
            if item.get("candidateForm") != AVAILABLE_FORM:
                fail(f"evaluation candidate form changed for {campaign_id}/{case_id}")
            production = item.get("productionOutcome")
            outcome = item.get("outcome")
            reason = item.get("reasonCode")
            if production == "CONFIRMED":
                if outcome != "CONFIRMED_FINITE_DIFFERENCE_FIT":
                    fail(f"confirmed production result not retained for {campaign_id}/{case_id}")
                if reason != "FINITE_DIFFERENCE_HOLDOUT_CONFIRMED":
                    fail(f"confirmed reason mismatch for {campaign_id}/{case_id}")
                confirmed += 1
            else:
                if outcome != "INCOMPLETE_ADAPTER_COVERAGE":
                    fail(f"unsupported coverage became a refutation for {campaign_id}/{case_id}")
                if reason != "LINEAR_RECURRENCE_ADAPTER_REQUIRED":
                    fail(f"missing recurrence blocker for {campaign_id}/{case_id}")
                incomplete += 1
            if item.get("uniqueInfiniteContinuationClaimAuthorized") is not False:
                fail(f"unique continuation claim authorized for {campaign_id}/{case_id}")
            if item.get("formalProofStatus") != "NOT_EVALUATED":
                fail(f"formal proof overstated for {campaign_id}/{case_id}")
            if item.get("externalNoveltyStatus") != "NOT_EVALUATED":
                fail(f"external novelty overstated for {campaign_id}/{case_id}")
            if item.get("publicationEligible") is not False:
                fail(f"publication authorized for {campaign_id}/{case_id}")
            root = item.get("contentHash")
            if root in evaluation_roots:
                fail(f"duplicate evaluation root: {root}")
            evaluation_roots.add(root)

    if confirmed + incomplete != 24:
        fail("evaluation accounting is not complete")
    if run.get("confirmedFiniteDifferenceEvaluations") != confirmed:
        fail("top-level confirmed count does not match raw evaluations")
    if run.get("incompleteAdapterCoverageEvaluations") != incomplete:
        fail("top-level incomplete count does not match raw evaluations")
    if confirmed == 0:
        fail("available production adapter confirmed no frozen case")
    if incomplete == 0:
        fail("missing recurrence adapter produced no retained incomplete outcome")
    return confirmed, incomplete


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
    incomplete: int,
    negatives: list[str],
) -> None:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    report = {
        "schema": VERIFY_SCHEMA,
        "status": "VERIFIED_PARTIAL_FINITE_SEQUENCE_ADAPTER",
        "runContentHash": run["contentHash"],
        "runFileSha256": exact_hash(run_path),
        "campaignsVerified": 4,
        "evaluationsVerified": 24,
        "confirmedFiniteDifferenceEvaluations": confirmed,
        "incompleteAdapterCoverageEvaluations": incomplete,
        "recurrenceAdapterStatus": "ADAPTER_REQUIRED",
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
    print(f"finiteSequenceAdapterVerification={path}")
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
            / "docs/schemas/regelsuche-candidate-independent-finite-sequence-adapter-run-v1.schema.json"
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
            fail("two clean adapter runs are not byte-identical")
        first = load_json(first_path)
        second = load_json(second_path)
        validate = lambda value: validate_run(
            value, schema, corpus, profile, receipt, cases
        )
        confirmed, incomplete = validate(first)
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
                "premature-completion",
                first,
                lambda value: value.update(
                    {"adapterStatus": "COMPLETE", "publicationAuthorized": True}
                ),
                validate,
            ),
            require_rejected(
                "removed-recurrence-blocker",
                first,
                lambda value: value.update({"recurrenceAdapterStatus": "AVAILABLE"}),
                validate,
            ),
            require_rejected(
                "hidden-refutation",
                first,
                lambda value: value["campaigns"][0]["evaluations"][1].update(
                    {
                        "outcome": "CONFIRMED_FINITE_DIFFERENCE_FIT",
                        "reasonCode": "FINITE_DIFFERENCE_HOLDOUT_CONFIRMED",
                    }
                ),
                validate,
            ),
        ]
        write_verification(
            args.report_directory.resolve(),
            first,
            first_path,
            confirmed,
            incomplete,
            negatives,
        )
    except (VerificationError, OSError, jsonschema.SchemaError) as error:
        print(f"finite-sequence adapter verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
