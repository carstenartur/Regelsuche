#!/usr/bin/env python3
"""Generate the deterministic, fail-closed execution foundation for issue #383.

This generator does not execute an evaluated discovery campaign. It materializes
all preregistered campaigns and their complete same-challenge case matrix so
later adapters cannot silently omit null, failed, unsupported, or held-out
outcomes. Every generated status remains explicitly incomplete.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
from typing import Any

CAMPAIGN_BATCH_SCHEMA = "regelsuche.candidate-independent-campaign-batch/v1"
CASE_EVALUATION_SCHEMA = "regelsuche.candidate-independent-case-evaluation/v1"
BENCHMARK_REPORT_SCHEMA = "regelsuche.candidate-independent-benchmark-report/v1"
BENCHMARK_RUN_SCHEMA = "regelsuche.candidate-independent-benchmark-run/v1"
CLAIM_POLICY = "INCOMPLETE_EXECUTION_DOES_NOT_AUTHORIZE_DISCOVERY_OR_NOVELTY_CLAIMS"
REASON = "EXECUTION_ADAPTER_NOT_IMPLEMENTED"
HASH_PATTERN = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"candidate-independent execution generation failed: {message}")


def load_unique(path: Path) -> dict[str, Any]:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=hook)
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(value, dict):
        fail(f"expected a JSON object in {path}")
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


def exact_hash_bytes(value: bytes) -> str:
    return "sha256:" + hashlib.sha256(value).hexdigest()


def exact_hash(path: Path) -> str:
    return exact_hash_bytes(path.read_bytes())


def with_content_hash(value: dict[str, Any]) -> dict[str, Any]:
    if "contentHash" in value:
        fail("contentHash must be assigned only by the generator")
    result = dict(value)
    result["contentHash"] = semantic_hash(value)
    return result


def safe(value: str) -> str:
    rendered = re.sub(r"[^A-Za-z0-9._-]", "_", value)
    if not rendered or rendered in {".", ".."}:
        fail(f"unsafe generated file component: {value!r}")
    return rendered


def write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def validate_source(source: dict[str, Any]) -> None:
    required = {
        "schema",
        "benchmarkId",
        "portfolioId",
        "portfolioContentHash",
        "claimPolicy",
        "publicationAuthorized",
        "executionStatus",
        "formationVisibility",
        "budgets",
        "metrics",
        "cases",
    }
    if set(source) != required:
        fail(
            "source fields differ from the frozen preregistration: "
            f"unknown={sorted(set(source) - required)} "
            f"missing={sorted(required - set(source))}"
        )
    if source["executionStatus"] != "NOT_STARTED":
        fail("evaluated execution has already started in the preregistration")
    if source["publicationAuthorized"] is not False:
        fail("preregistration unexpectedly authorizes publication")
    if not HASH_PATTERN.fullmatch(str(source["portfolioContentHash"])):
        fail("portfolioContentHash is not a canonical SHA-256 identity")
    budgets = source["budgets"]
    if not isinstance(budgets, dict) or budgets.get("campaignsPerChallenge") != 4:
        fail("the frozen source must configure exactly four campaigns per challenge")
    cases = source["cases"]
    if not isinstance(cases, list) or len(cases) != 18:
        fail("the frozen source must contain exactly 18 cases")


def grouped_cases(source: dict[str, Any]) -> dict[str, list[dict[str, Any]]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for raw in source["cases"]:
        if not isinstance(raw, dict):
            fail("every frozen case must be an object")
        challenge = str(raw.get("challengeId", ""))
        case_id = str(raw.get("caseId", ""))
        split = str(raw.get("split", ""))
        if not challenge or not case_id or split not in {"TRAIN", "VALIDATION", "TEST"}:
            fail(f"invalid frozen case identity: {raw}")
        grouped.setdefault(challenge, []).append(raw)
    if len(grouped) != 3:
        fail(f"expected three selected challenges, found {sorted(grouped)}")
    for challenge, cases in grouped.items():
        cases.sort(key=lambda item: str(item["caseId"]))
        split_counts = {
            split: sum(1 for case in cases if case["split"] == split)
            for split in ("TRAIN", "VALIDATION", "TEST")
        }
        if split_counts != {"TRAIN": 2, "VALIDATION": 2, "TEST": 2}:
            fail(f"challenge {challenge} has unexpected split counts: {split_counts}")
    return dict(sorted(grouped.items()))


def campaign_document(
    source: dict[str, Any],
    challenge: str,
    cases: list[dict[str, Any]],
    index: int,
) -> dict[str, Any]:
    campaign_id = f"{challenge}-campaign-{index:02d}"
    case_ids = [str(case["caseId"]) for case in cases]
    train_ids = [str(case["caseId"]) for case in cases if case["split"] == "TRAIN"]
    held_out = [
        str(case["caseId"])
        for case in cases
        if case["split"] in {"VALIDATION", "TEST"}
    ]
    seed = semantic_hash(
        {
            "benchmarkId": source["benchmarkId"],
            "campaignId": campaign_id,
            "challengeId": challenge,
            "index": index,
        }
    )
    return with_content_hash(
        {
            "campaignId": campaign_id,
            "caseIds": case_ids,
            "challengeId": challenge,
            "configuredSeed": seed,
            "formationCaseIds": train_ids,
            "heldOutCaseIds": held_out,
            "reasonCodes": [REASON],
            "resourceBudget": {
                "maxCandidateEvaluations": source["budgets"]["maxCandidateEvaluations"],
                "maxProofAttempts": source["budgets"]["maxProofAttempts"],
                "maxStates": source["budgets"]["maxStatesPerCampaign"],
            },
            "status": "CONFIGURED_NOT_EXECUTED",
            "terminalOutcome": "INCOMPLETE",
        }
    )


def evaluation_document(
    source: dict[str, Any],
    campaign: dict[str, Any],
    case: dict[str, Any],
) -> dict[str, Any]:
    split = str(case["split"])
    formation_visibility = "ALLOWED" if split == "TRAIN" else "PROHIBITED"
    return with_content_hash(
        {
            "benchmarkId": source["benchmarkId"],
            "campaignContentHash": campaign["contentHash"],
            "campaignId": campaign["campaignId"],
            "candidateFormationStatus": "NOT_RUN",
            "caseId": case["caseId"],
            "challengeId": case["challengeId"],
            "executionStatus": "NOT_EXECUTED",
            "failures": [REASON],
            "formationVisibility": formation_visibility,
            "heldOutEvaluationStatus": "NOT_RUN",
            "outcome": "INCOMPLETE",
            "publicationEligible": False,
            "resourceUse": {
                "candidateEvaluations": 0,
                "exploredStates": 0,
                "generatedSuccessors": 0,
                "proofAttempts": 0,
            },
            "schema": CASE_EVALUATION_SCHEMA,
            "split": split,
        }
    )


def build_bundle(source_path: Path, output: Path, repository_revision: str) -> None:
    source = load_unique(source_path)
    validate_source(source)
    grouped = grouped_cases(source)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    source_hash = semantic_hash(source)
    campaigns: list[dict[str, Any]] = []
    for challenge, cases in grouped.items():
        for index in range(1, int(source["budgets"]["campaignsPerChallenge"]) + 1):
            campaigns.append(campaign_document(source, challenge, cases, index))
    campaigns.sort(key=lambda item: str(item["campaignId"]))

    batch = with_content_hash(
        {
            "benchmarkId": source["benchmarkId"],
            "campaigns": campaigns,
            "claimPolicy": CLAIM_POLICY,
            "configuredCampaigns": len(campaigns),
            "executedCampaigns": 0,
            "incompleteCampaigns": len(campaigns),
            "portfolioContentHash": source["portfolioContentHash"],
            "portfolioId": source["portfolioId"],
            "schema": CAMPAIGN_BATCH_SCHEMA,
            "sourceContentHash": source_hash,
            "status": "NOT_STARTED",
        }
    )
    batch_path = output / "campaign-batch.json"
    write_json(batch_path, batch)

    cases_by_challenge = grouped
    evaluation_records: list[dict[str, Any]] = []
    evaluation_files: list[dict[str, str]] = []
    for campaign in campaigns:
        for case in cases_by_challenge[str(campaign["challengeId"])]:
            evaluation = evaluation_document(source, campaign, case)
            relative = Path("case-evaluations") / (
                safe(str(campaign["campaignId"]))
                + "--"
                + safe(str(case["caseId"]))
                + ".json"
            )
            path = output / relative
            write_json(path, evaluation)
            evaluation_records.append(evaluation)
            evaluation_files.append(
                {
                    "contentHash": evaluation["contentHash"],
                    "fileSha256": exact_hash(path),
                    "path": relative.as_posix(),
                }
            )

    challenge_coverage = []
    for challenge in sorted(grouped):
        count = sum(
            1 for item in evaluation_records if item["challengeId"] == challenge
        )
        challenge_coverage.append(
            {
                "challengeId": challenge,
                "configuredEvaluations": count,
                "executedEvaluations": 0,
                "incompleteEvaluations": count,
            }
        )

    report = with_content_hash(
        {
            "benchmarkId": source["benchmarkId"],
            "campaignBatchContentHash": batch["contentHash"],
            "caseEvaluationContentHashes": sorted(
                item["contentHash"] for item in evaluation_records
            ),
            "challengeCoverage": challenge_coverage,
            "claimPolicy": CLAIM_POLICY,
            "configuredEvaluations": len(evaluation_records),
            "executedEvaluations": 0,
            "incompleteEvaluations": len(evaluation_records),
            "metrics": {
                str(metric): "NOT_MEASURED" for metric in source["metrics"]
            },
            "outcomeCounts": {"INCOMPLETE": len(evaluation_records)},
            "publicationAuthorized": False,
            "schema": BENCHMARK_REPORT_SCHEMA,
            "sourceContentHash": source_hash,
            "status": "INCOMPLETE_EXECUTION",
        }
    )
    report_path = output / "benchmark-report.json"
    write_json(report_path, report)

    evaluation_set = sorted(evaluation_files, key=lambda item: item["path"])
    run = with_content_hash(
        {
            "artifacts": {
                "benchmarkReport": {
                    "contentHash": report["contentHash"],
                    "fileSha256": exact_hash(report_path),
                    "path": "benchmark-report.json",
                },
                "campaignBatch": {
                    "contentHash": batch["contentHash"],
                    "fileSha256": exact_hash(batch_path),
                    "path": "campaign-batch.json",
                },
                "caseEvaluationSet": {
                    "contentHash": semantic_hash(evaluation_set),
                    "fileCount": len(evaluation_set),
                    "files": evaluation_set,
                },
            },
            "benchmarkId": source["benchmarkId"],
            "claimPolicy": CLAIM_POLICY,
            "environmentId": "checkout-local-execution-foundation/v1",
            "externalNoveltyStatus": "NOT_EVALUATED",
            "publicationAuthorized": False,
            "repositoryRevision": repository_revision,
            "schema": BENCHMARK_RUN_SCHEMA,
            "sourceContentHash": source_hash,
            "sourceFileSha256": exact_hash(source_path),
            "status": "GENERATED_NOT_EVALUATED",
        }
    )
    write_json(output / "benchmark-run.json", run)
    print(f"candidateIndependentExecutionFoundation={output}")
    print(f"campaigns={len(campaigns)}")
    print(f"caseEvaluations={len(evaluation_records)}")
    print(f"reportContentHash={report['contentHash']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--source",
        type=Path,
        default=Path("research/benchmarks/candidate-independent/benchmark-source.json"),
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository-revision", default="WORKTREE")
    args = parser.parse_args()
    build_bundle(args.source.resolve(), args.output.resolve(), args.repository_revision)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
