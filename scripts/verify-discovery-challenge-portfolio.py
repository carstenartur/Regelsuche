#!/usr/bin/env python3
"""Generate and independently verify the frozen discovery challenge portfolio."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

try:
    from jsonschema import Draft202012Validator
    from jsonschema.exceptions import ValidationError
except ImportError as error:
    raise SystemExit(
        "jsonschema is required; run ./gradlew prepareVerificationEnvironment"
    ) from error

EXPECTED_JSONSCHEMA_VERSION = "4.25.1"
JSON_ARTIFACT_NAMES = (
    "challenge-landscape.json",
    "challenge-feasibility-report.json",
    "challenge-split-policy.json",
    "challenge-baseline-plan.json",
    "challenge-run-budget.json",
    "challenge-portfolio.json",
)
SUMMARY_NAME = "challenge-portfolio-summary.md"
EXPECTED_FILES = (*JSON_ARTIFACT_NAMES, SUMMARY_NAME)
EXPECTED_SELECTED_IDS = {
    "rational-assumption-rewrites",
    "finite-difference-recurrences",
    "reusable-search-macros",
}


def fail(message: str) -> None:
    raise SystemExit(f"discovery challenge portfolio invalid: {message}")


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def load_unique(path: Path) -> Any:
    def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate field {key!r}")
            result[key] = value
        return result

    try:
        with path.open("r", encoding="utf-8") as handle:
            return json.load(handle, object_pairs_hook=pairs_hook)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        fail(f"cannot parse {path}: {error}")


def digest(value: Any) -> str:
    payload = json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def run_generator(
    generator: Path,
    source: Path,
    output: Path,
    expect_success: bool,
    label: str,
) -> None:
    result = subprocess.run(
        [
            sys.executable,
            str(generator),
            "--source",
            str(source),
            "--output",
            str(output),
        ],
        cwd=generator.parent.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    if expect_success and result.returncode != 0:
        fail(
            f"{label} failed with {result.returncode}: "
            f"{result.stdout}\n{result.stderr}"
        )
    if not expect_success and result.returncode == 0:
        fail(f"{label} unexpectedly passed")


def tree_entries(root: Path) -> list[tuple[str, str]]:
    require(
        root.is_dir() and not root.is_symlink(),
        f"directory is missing or symbolic: {root}",
    )
    entries: list[tuple[str, str]] = []
    for current, directory_names, file_names in os.walk(
        root,
        topdown=True,
        followlinks=False,
    ):
        current_path = Path(current)
        directory_names.sort()
        file_names.sort()
        for name in directory_names:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic link is forbidden: {path}")
            require(path.is_dir(), f"unsupported directory entry: {path}")
            entries.append(("directory", path.relative_to(root).as_posix()))
        for name in file_names:
            path = current_path / name
            require(not path.is_symlink(), f"symbolic link is forbidden: {path}")
            require(path.is_file(), f"unsupported file entry: {path}")
            entries.append(("file", path.relative_to(root).as_posix()))
    return sorted(entries)


def require_identical(first: Path, second: Path, label: str) -> None:
    first_entries = tree_entries(first)
    second_entries = tree_entries(second)
    require(first_entries == second_entries, f"{label} trees differ")
    for kind, relative in first_entries:
        if kind != "file":
            continue
        require(
            (first / relative).read_bytes() == (second / relative).read_bytes(),
            f"{label} differs at {relative}",
        )


def validate_artifacts(
    source: dict[str, Any],
    generated: Path,
    artifact_validator: Draft202012Validator,
) -> None:
    expected_tree = sorted(("file", name) for name in EXPECTED_FILES)
    require(
        tree_entries(generated) == expected_tree,
        "generated artifact tree is incomplete or contains extra entries",
    )

    artifacts: dict[str, dict[str, Any]] = {}
    for name in JSON_ARTIFACT_NAMES:
        value = load_unique(generated / name)
        try:
            artifact_validator.validate(value)
        except ValidationError as error:
            fail(f"schema validation failed for {name}: {error.message}")
        without_hash = dict(value)
        content_hash = without_hash.pop("contentHash", None)
        require(content_hash == digest(without_hash), f"contentHash drift: {name}")
        artifacts[name] = value

    summary = (generated / SUMMARY_NAME).read_text(encoding="utf-8")
    require(summary.strip(), "challenge portfolio summary is empty")
    require(
        "Evaluated campaign status: `NOT_STARTED`" in summary,
        "summary campaign boundary drift",
    )
    require(
        "External novelty status: `NOT_EVALUATED`" in summary,
        "summary novelty boundary drift",
    )
    for challenge_id in EXPECTED_SELECTED_IDS:
        require(
            f"`{challenge_id}`" in summary,
            f"summary omits selected challenge: {challenge_id}",
        )

    landscape = artifacts["challenge-landscape.json"]
    feasibility = artifacts["challenge-feasibility-report.json"]
    split = artifacts["challenge-split-policy.json"]
    baselines = artifacts["challenge-baseline-plan.json"]
    budget = artifacts["challenge-run-budget.json"]
    portfolio = artifacts["challenge-portfolio.json"]

    require(landscape["assessedChallengeCount"] == 5, "assessed challenge count drift")
    require(landscape["selectedChallengeCount"] == 3, "selected challenge count drift")
    require(landscape["deferredChallengeCount"] == 2, "deferred challenge count drift")
    selected_ids = portfolio["selectedChallengeIds"]
    require(len(selected_ids) == 3, "selected challenge list size drift")
    require(set(selected_ids) == EXPECTED_SELECTED_IDS, "selected challenge IDs drift")
    require(portfolio["landscapeHash"] == landscape["contentHash"], "landscape root drift")
    require(
        portfolio["feasibilityHash"] == feasibility["contentHash"],
        "feasibility root drift",
    )
    require(portfolio["splitPolicyHash"] == split["contentHash"], "split root drift")
    require(
        portfolio["baselinePlanHash"] == baselines["contentHash"],
        "baseline root drift",
    )
    require(portfolio["runBudgetHash"] == budget["contentHash"], "budget root drift")
    require(portfolio["pilotEvidenceStatus"] == "DEVELOPMENT_ONLY", "pilot claim inflated")
    require(portfolio["evaluatedCampaignStatus"] == "NOT_STARTED", "campaign claim inflated")
    require(portfolio["externalNoveltyStatus"] == "NOT_EVALUATED", "novelty claim inflated")
    require(
        split["externalSearchVisibility"] == "POST_FORMATION_ONLY",
        "external search visibility drift",
    )
    require(
        baselines["universalScorePolicy"]
        == "NO_UNIVERSAL_SCORE_TRACK_SCOPED_CLAIMS_ONLY",
        "universal-score policy drift",
    )

    source_by_id = {
        item["challengeId"]: item for item in source["challenges"]
    }
    for challenge_id in selected_ids:
        item = source_by_id[challenge_id]
        require(
            item["complexityTier"] in {"INTERMEDIATE", "ADVANCED"},
            f"unsupported complexity tier: {challenge_id}",
        )
        require(
            bool(item["independentEvaluator"]["evaluatorId"]),
            f"missing evaluator: {challenge_id}",
        )
        require(
            len(item["baselineProfiles"]) >= 2,
            f"insufficient baselines: {challenge_id}",
        )
        require(
            item["budget"]["maximumCampaigns"] > 0,
            f"missing campaign budget: {challenge_id}",
        )
        require(
            item["budget"]["maximumCandidateEvaluations"] > 0,
            f"missing evaluation budget: {challenge_id}",
        )
        prohibited = " ".join(
            item["prohibitedFormationInformation"]
        ).lower()
        require(
            "target" in prohibited or "expected" in prohibited,
            f"target prohibition missing: {challenge_id}",
        )


def reject_mutations(
    generator: Path,
    source: dict[str, Any],
    temporary_root: Path,
) -> None:
    mutations: list[tuple[str, dict[str, Any]]] = []

    visible_test = copy.deepcopy(source)
    visible_test["challenges"][0]["formationInformation"].append(
        "final TEST target"
    )
    visible_test["challenges"][0]["prohibitedFormationInformation"] = [
        "hidden reference rule"
    ]
    mutations.append(("missing target prohibition", visible_test))

    no_evaluator = copy.deepcopy(source)
    no_evaluator["challenges"][0]["independentEvaluator"]["method"] = ""
    mutations.append(("blank evaluator", no_evaluator))

    unbounded = copy.deepcopy(source)
    unbounded["challenges"][0]["budget"]["maximumCampaigns"] = 0
    mutations.append(("no campaign budget", unbounded))

    for index, (label, mutation) in enumerate(mutations):
        mutation_root = temporary_root / f"mutation-{index}"
        mutation_root.mkdir(parents=True)
        source_path = mutation_root / "source.json"
        source_path.write_text(
            json.dumps(mutation, ensure_ascii=False),
            encoding="utf-8",
        )
        run_generator(
            generator,
            source_path,
            mutation_root / "output",
            False,
            label,
        )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    root = arguments.repository_root.resolve()

    try:
        installed = version("jsonschema")
    except PackageNotFoundError:
        fail("jsonschema is not installed")
    require(
        installed == EXPECTED_JSONSCHEMA_VERSION,
        f"jsonschema version drift: expected {EXPECTED_JSONSCHEMA_VERSION}, found {installed}",
    )

    source_path = root / "research/challenges/challenge-plan-source.json"
    generator = root / "scripts/generate-discovery-challenge-portfolio.py"
    committed = root / "research/challenges/generated"
    source_schema_path = (
        root
        / "docs/schemas/regelsuche-discovery-challenge-plan-source-v1.schema.json"
    )
    artifact_schema_path = (
        root
        / "docs/schemas/regelsuche-discovery-challenge-artifact-v1.schema.json"
    )
    for path in (
        source_path,
        generator,
        source_schema_path,
        artifact_schema_path,
    ):
        require(path.is_file() and not path.is_symlink(), f"required input is invalid: {path}")

    source_schema = load_unique(source_schema_path)
    artifact_schema = load_unique(artifact_schema_path)
    require(
        source_schema.get("additionalProperties") is False,
        "source schema must fail closed",
    )
    require(
        artifact_schema.get("additionalProperties") is False,
        "artifact schema must fail closed",
    )
    Draft202012Validator.check_schema(source_schema)
    Draft202012Validator.check_schema(artifact_schema)
    source_validator = Draft202012Validator(source_schema)
    artifact_validator = Draft202012Validator(artifact_schema)
    source = load_unique(source_path)
    try:
        source_validator.validate(source)
    except ValidationError as error:
        fail(f"source schema validation failed: {error.message}")

    with tempfile.TemporaryDirectory(prefix="regelsuche-challenge-") as directory:
        temporary_root = Path(directory)
        first = temporary_root / "first"
        second = temporary_root / "second"
        run_generator(generator, source_path, first, True, "first generation")
        run_generator(generator, source_path, second, True, "second generation")
        require_identical(first, second, "clean challenge generations")
        require_identical(first, committed, "committed challenge portfolio")
        validate_artifacts(source, first, artifact_validator)
        reject_mutations(generator, source, temporary_root)

    print(f"jsonschema={installed}")
    print("discovery-challenge-portfolio=VERIFIED")
    print("selected-challenges=" + ",".join(sorted(EXPECTED_SELECTED_IDS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
