#!/usr/bin/env python3
"""Generate the paper's candidate-independent benchmark table from v2 evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

EXPECTED_SCHEMA = "regelsuche.candidate-independent-benchmark-execution/v2"
EXPECTED_STATUS = "COMPLETE_FROZEN_CHALLENGE_EXECUTION"
EXPECTED_TOTALS = {
    "configuredCampaigns": 12,
    "executedCampaigns": 12,
    "configuredCaseSlots": 72,
    "executedCaseSlots": 72,
    "successfulCaseSlots": 52,
    "noResultCaseSlots": 20,
    "detailedEvaluationRows": 120,
    "correctnessRegressions": 0,
}
EXPECTED_CHALLENGES = [
    "finite-difference-recurrences",
    "rational-assumption-rewrites",
    "reusable-search-macros",
]


def fail(message: str) -> None:
    raise SystemExit(f"paper benchmark generation failed: {message}")


def load_unique(path: Path) -> dict[str, Any]:
    def pairs(items: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in items:
            if key in result:
                fail(f"duplicate field {key!r} in {path}")
            result[key] = value
        return result

    if not path.is_file() or path.is_symlink():
        fail(f"benchmark execution is missing, non-regular or symbolic: {path}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=pairs
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot parse {path}: {error}")
    if not isinstance(value, dict):
        fail("benchmark execution is not an object")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def verify(execution: dict[str, Any]) -> None:
    if execution.get("schema") != EXPECTED_SCHEMA:
        fail("unexpected benchmark execution schema")
    if execution.get("benchmarkStatus") != EXPECTED_STATUS:
        fail("benchmark execution is not complete")
    if execution.get("publicationAuthorized") is not False:
        fail("benchmark unexpectedly authorizes publication")
    material = dict(execution)
    retained = material.pop("contentHash", None)
    if retained != semantic_hash(material):
        fail("benchmark execution contentHash mismatch")
    if execution.get("totals") != EXPECTED_TOTALS:
        fail(f"benchmark totals changed: {execution.get('totals')}")
    challenges = execution.get("challengeExecutions")
    if not isinstance(challenges, list):
        fail("challengeExecutions is not an array")
    identifiers = [item.get("challengeId") for item in challenges]
    if identifiers != EXPECTED_CHALLENGES:
        fail(f"challenge order changed: {identifiers}")
    for challenge in challenges:
        if challenge.get("executedCampaigns") != 4:
            fail(f"campaign count changed: {challenge.get('challengeId')}")
        if challenge.get("caseSlots") != 24:
            fail(f"case-slot count changed: {challenge.get('challengeId')}")
        if challenge.get("correctnessRegressions") != 0:
            fail(f"correctness regression retained: {challenge.get('challengeId')}")
        if (
            challenge.get("successfulCaseSlots", 0)
            + challenge.get("noResultCaseSlots", 0)
            != challenge.get("caseSlots")
        ):
            fail(f"case-slot accounting is incomplete: {challenge.get('challengeId')}")


def render(execution: dict[str, Any]) -> str:
    totals = execution["totals"]
    lines = [
        "# Candidate-independent benchmark results",
        "",
        f"Evidence root: `{execution['contentHash']}`",
        "",
        "| Challenge | Campaigns | Frozen case slots | Successful slots | Retained no-result slots | Detailed rows | Correctness regressions |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for challenge in execution["challengeExecutions"]:
        lines.append(
            "| {challenge} | {campaigns} | {slots} | {successful} | "
            "{no_result} | {rows} | {regressions} |".format(
                challenge=challenge["challengeId"],
                campaigns=challenge["executedCampaigns"],
                slots=challenge["caseSlots"],
                successful=challenge["successfulCaseSlots"],
                no_result=challenge["noResultCaseSlots"],
                rows=challenge["detailedEvaluationRows"],
                regressions=challenge["correctnessRegressions"],
            )
        )
    lines.extend(
        [
            "| **Total** | **{executed}** | **{slots}** | **{successful}** | "
            "**{no_result}** | **{rows}** | **{regressions}** |".format(
                executed=totals["executedCampaigns"],
                slots=totals["executedCaseSlots"],
                successful=totals["successfulCaseSlots"],
                no_result=totals["noResultCaseSlots"],
                rows=totals["detailedEvaluationRows"],
                regressions=totals["correctnessRegressions"],
            ),
            "",
            "The table reports challenge-native case slots rather than a universal "
            "cross-domain success score. A successful slot means the challenge's "
            "pre-registered candidate form or paired evaluation produced its "
            "declared non-regressing outcome. No-result slots remain part of the "
            "denominator and are not failures removed after inspection.",
            "",
            "This evidence does not authorize formal-proof, external-novelty, "
            "expert-interestingness, baseline-superiority, amortization, or "
            "publication claims.",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--execution", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    execution = load_unique(arguments.execution)
    verify(execution)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(render(execution), encoding="utf-8")
    print(f"paperBenchmarkResults={execution['contentHash']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
