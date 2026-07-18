#!/usr/bin/env python3
"""Fail-closed verification of the frozen evolution population study."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

EXPECTED_SCHEMA = "regelsuche.evolution-study-source/v1"
EXPECTED_STUDY_ID = "regelsuche-evolution-population-study-2026-07/v1"
EXPECTED_CLAIM_POLICY = (
    "EVOLUTION_FITNESS_IS_NOT_PROOF_NOVELTY_PROMOTION_OR_PUBLIC_EVIDENCE"
)
EXPECTED_BENCHMARK_SCHEMA = (
    "regelsuche.candidate-independent-benchmark-source/v1"
)
EXPECTED_BENCHMARK_ID = (
    "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1"
)
EXPECTED_BENCHMARK_HASH = (
    "sha256:b1b37434b731d915a6ea206a22b9a3d7d36a0214f519e9cfa83c24bd0a8f8bca"
)
EXPECTED_SOURCE_HASH = (
    "sha256:1cf044f1bf4d9df8f157fb402a428365477da11e57d39d9ca22b62bf7815da2b"
)
EXPECTED_SPLIT_COUNTS = Counter({"TRAIN": 6, "VALIDATION": 6, "TEST": 6})
EXPECTED_PROHIBITED_FIELDS = [
    "target",
    "expectedAnswer",
    "hiddenReference",
    "testLabel",
    "postHocFamilyAnnotation",
    "finalTestOutcome",
]
EXPECTED_POPULATION_OUTCOMES = [
    "COMPLETED",
    "EXTINCT",
    "STAGNATED",
    "BUDGET_EXHAUSTED",
    "INCOMPLETE",
]
EXPECTED_FINAL_OUTCOMES = [
    "ACCEPTED",
    "REJECTED",
    "DISPROVED",
    "NO_RESULT",
    "TIMEOUT",
    "UNSUPPORTED",
    "INCOMPLETE",
]
EXPECTED_BLOCKERS = [
    "SAFETY",
    "LEAKAGE",
    "CORRECTNESS",
    "EVIDENCE_INCOMPLETE",
    "UNBOUNDED",
    "CYCLIC",
    "DUPLICATE",
]
EXPECTED_COMPONENTS = {
    "newlySolvedTrainCases": "MAXIMIZE",
    "exploredStateReduction": "MAXIMIZE",
    "pathLengthReduction": "MAXIMIZE",
    "structuralSupportDiversity": "MAXIMIZE",
    "projectInternalNovelty": "MAXIMIZE",
    "assumptionComplexity": "MINIMIZE",
    "candidateComplexity": "MINIMIZE",
    "counterexampleRisk": "MINIMIZE",
}
TOP_LEVEL_FIELDS = {
    "schema",
    "studyId",
    "executionStatus",
    "finalTestAccess",
    "publicationAuthorized",
    "claimPolicy",
    "benchmarkSource",
    "formationBoundary",
    "populationPolicy",
    "fitnessModel",
    "validationSelection",
    "finalTestPolicy",
    "diversityAblation",
    "splitManifest",
    "contentHash",
}
SPLIT_FIELDS = {
    "caseId",
    "challengeId",
    "familyId",
    "structuralSignature",
    "exactFingerprint",
    "alphaStructuralFingerprint",
    "split",
    "formationVisible",
}
SHA256 = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> None:
    raise SystemExit(f"evolution study invalid: {message}")


def load_unique_json(path: Path) -> dict[str, Any]:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    try:
        document = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=hook
        )
    except (OSError, json.JSONDecodeError) as error:
        fail(f"cannot read {path}: {error}")
    if not isinstance(document, dict):
        fail(f"{path} must contain a JSON object")
    return document


def canonical_hash(value: object) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def require_exact_fields(
    value: dict[str, Any], expected: set[str], context: str
) -> None:
    actual = set(value)
    if actual != expected:
        fail(
            f"{context} fields drift: unknown={sorted(actual - expected)} "
            f"missing={sorted(expected - actual)}"
        )


def require_hash(value: object, context: str) -> str:
    if not isinstance(value, str) or not SHA256.fullmatch(value):
        fail(f"{context} is not a canonical sha256 hash")
    return value


def verify_source_identity(source: dict[str, Any]) -> None:
    require_exact_fields(source, TOP_LEVEL_FIELDS, "top-level")
    if source["schema"] != EXPECTED_SCHEMA:
        fail("unexpected schema")
    if source["studyId"] != EXPECTED_STUDY_ID:
        fail("study identity drift")
    if source["claimPolicy"] != EXPECTED_CLAIM_POLICY:
        fail("claim policy drift")
    if source["executionStatus"] != "NOT_STARTED":
        fail("population execution started before the study contract was frozen")
    if source["finalTestAccess"] != "NOT_ACCESSED":
        fail("FINAL TEST has already been accessed")
    if source["publicationAuthorized"] is not False:
        fail("publication must remain unauthorized before study execution")

    recorded = require_hash(source["contentHash"], "contentHash")
    content = dict(source)
    content.pop("contentHash")
    observed = canonical_hash(content)
    if recorded != observed or recorded != EXPECTED_SOURCE_HASH:
        fail(f"study content hash drift: expected {recorded}, observed {observed}")


def verify_benchmark_binding(
    root: Path, source: dict[str, Any]
) -> dict[str, dict[str, Any]]:
    binding = source["benchmarkSource"]
    require_exact_fields(
        binding,
        {"path", "schema", "benchmarkId", "canonicalSourceHash"},
        "benchmarkSource",
    )
    if binding["schema"] != EXPECTED_BENCHMARK_SCHEMA:
        fail("benchmark schema drift")
    if binding["benchmarkId"] != EXPECTED_BENCHMARK_ID:
        fail("benchmark identity drift")
    if binding["canonicalSourceHash"] != EXPECTED_BENCHMARK_HASH:
        fail("recorded benchmark hash drift")

    relative = Path(binding["path"])
    if relative.is_absolute() or ".." in relative.parts:
        fail("benchmark source path must be checkout-relative")
    benchmark = load_unique_json(root / relative)
    if benchmark.get("schema") != EXPECTED_BENCHMARK_SCHEMA:
        fail("bound benchmark file has the wrong schema")
    if benchmark.get("benchmarkId") != EXPECTED_BENCHMARK_ID:
        fail("bound benchmark file has the wrong identity")
    observed_hash = canonical_hash(benchmark)
    if observed_hash != EXPECTED_BENCHMARK_HASH:
        fail(
            "bound benchmark source changed: "
            f"expected {EXPECTED_BENCHMARK_HASH}, observed {observed_hash}"
        )
    cases = benchmark.get("cases")
    if not isinstance(cases, list):
        fail("bound benchmark cases are missing")
    by_id: dict[str, dict[str, Any]] = {}
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get("caseId"), str):
            fail("bound benchmark contains a malformed case")
        if case["caseId"] in by_id:
            fail(f"duplicate bound benchmark case {case['caseId']}")
        by_id[case["caseId"]] = case
    return by_id


def verify_boundaries(source: dict[str, Any]) -> None:
    boundary = source["formationBoundary"]
    require_exact_fields(
        boundary, {"allowedSplits", "prohibitedFields"}, "formationBoundary"
    )
    if boundary["allowedSplits"] != ["TRAIN"]:
        fail("genome formation must remain TRAIN-only")
    if boundary["prohibitedFields"] != EXPECTED_PROHIBITED_FIELDS:
        fail("formation prohibited-field policy drift")

    validation = source["validationSelection"]
    require_exact_fields(
        validation,
        {"split", "selects", "finalTestVisible", "selectionStatus"},
        "validationSelection",
    )
    if validation != {
        "split": "VALIDATION",
        "selects": ["populationPolicy", "scalarSelectionProfile"],
        "finalTestVisible": False,
        "selectionStatus": "NOT_STARTED",
    }:
        fail("VALIDATION selection policy drift")

    final_test = source["finalTestPolicy"]
    require_exact_fields(
        final_test,
        {"split", "oneTimeEvaluation", "rerunPolicy", "status", "terminalOutcomes"},
        "finalTestPolicy",
    )
    if final_test["split"] != "TEST":
        fail("final evaluation must use TEST")
    if final_test["oneTimeEvaluation"] is not True:
        fail("FINAL TEST must be one-time")
    if final_test["rerunPolicy"] != "NEW_STUDY_ID_REQUIRED":
        fail("FINAL TEST reruns must require a new study identity")
    if final_test["status"] != "NOT_ACCESSED":
        fail("FINAL TEST status changed before selection")
    if final_test["terminalOutcomes"] != EXPECTED_FINAL_OUTCOMES:
        fail("FINAL TEST terminal-outcome policy drift")


def verify_population_and_fitness(source: dict[str, Any]) -> None:
    population = source["populationPolicy"]
    require_exact_fields(
        population,
        {
            "randomSeed",
            "populationSize",
            "generationBudget",
            "eliteCount",
            "maxOffspringPerLineage",
            "maxEvaluatedGenomes",
            "minDistinctAlphaStructures",
            "duplicatePolicy",
            "replacementPolicy",
            "terminalOutcomes",
        },
        "populationPolicy",
    )
    for field in (
        "populationSize",
        "generationBudget",
        "maxOffspringPerLineage",
        "maxEvaluatedGenomes",
        "minDistinctAlphaStructures",
    ):
        if not isinstance(population[field], int) or population[field] <= 0:
            fail(f"population budget {field} must be positive")
    if population["randomSeed"] != 22020260718:
        fail("random seed drift")
    if not 0 <= population["eliteCount"] < population["populationSize"]:
        fail("elite count is outside the population")
    if population["minDistinctAlphaStructures"] > population["populationSize"]:
        fail("minimum diversity exceeds population size")
    if population["maxEvaluatedGenomes"] != (
        population["populationSize"] * population["generationBudget"]
    ):
        fail("evaluated-genome budget no longer matches the frozen population budget")
    if population["duplicatePolicy"] != "EXACT_AND_ALPHA_STRUCTURAL_UNIQUE":
        fail("duplicate policy drift")
    if population["replacementPolicy"] != (
        "ELITES_THEN_DETERMINISTIC_FITNESS_ORDER"
    ):
        fail("replacement policy drift")
    if population["terminalOutcomes"] != EXPECTED_POPULATION_OUTCOMES:
        fail("population terminal-outcome policy drift")

    fitness = source["fitnessModel"]
    require_exact_fields(
        fitness,
        {"hardBlockers", "components", "scalarSelectionProfile"},
        "fitnessModel",
    )
    if fitness["hardBlockers"] != EXPECTED_BLOCKERS:
        fail("hard blocker policy drift")
    components = fitness["components"]
    if not isinstance(components, list):
        fail("fitness components are missing")
    actual: dict[str, str] = {}
    for component in components:
        require_exact_fields(
            component, {"name", "direction", "split"}, "fitness component"
        )
        name = component["name"]
        if name in actual:
            fail(f"duplicate fitness component {name}")
        if component["split"] != "TRAIN":
            fail(f"fitness component {name} is not TRAIN-only")
        actual[name] = component["direction"]
    if actual != EXPECTED_COMPONENTS:
        fail(f"fitness component drift: {actual}")

    scalar = fitness["scalarSelectionProfile"]
    require_exact_fields(
        scalar, {"profileId", "frozenBeforeValidation", "weights"},
        "scalarSelectionProfile",
    )
    if scalar["profileId"] != "regelsuche.evolution-fitness-profile/v1":
        fail("scalar profile identity drift")
    if scalar["frozenBeforeValidation"] is not True:
        fail("scalar profile is not frozen before VALIDATION")
    weights = scalar["weights"]
    if set(weights) != set(EXPECTED_COMPONENTS):
        fail("scalar weights do not cover exactly the frozen fitness components")
    for name, direction in EXPECTED_COMPONENTS.items():
        weight = weights[name]
        if not isinstance(weight, (int, float)) or weight == 0:
            fail(f"invalid scalar weight for {name}")
        if direction == "MAXIMIZE" and weight < 0:
            fail(f"weight sign contradicts MAXIMIZE for {name}")
        if direction == "MINIMIZE" and weight > 0:
            fail(f"weight sign contradicts MINIMIZE for {name}")

    ablation = source["diversityAblation"]
    require_exact_fields(
        ablation,
        {
            "profiles",
            "identicalBudgets",
            "selectionSplit",
            "finalTestUsedForSelection",
            "status",
        },
        "diversityAblation",
    )
    if ablation != {
        "profiles": ["FULL_DIVERSITY", "NO_DIVERSITY"],
        "identicalBudgets": True,
        "selectionSplit": "VALIDATION",
        "finalTestUsedForSelection": False,
        "status": "NOT_STARTED",
    }:
        fail("diversity ablation policy drift")


def verify_split_manifest(
    source: dict[str, Any], benchmark_cases: dict[str, dict[str, Any]]
) -> None:
    manifest = source["splitManifest"]
    if not isinstance(manifest, list) or len(manifest) != 18:
        fail("split manifest must contain exactly 18 frozen cases")

    case_ids: set[str] = set()
    family_ids: set[str] = set()
    signatures: set[str] = set()
    exact_fingerprints: set[str] = set()
    alpha_fingerprints: set[str] = set()
    split_counts: Counter[str] = Counter()

    for entry in manifest:
        if not isinstance(entry, dict):
            fail("split manifest contains a non-object entry")
        require_exact_fields(entry, SPLIT_FIELDS, "split entry")
        case_id = entry["caseId"]
        if case_id in case_ids:
            fail(f"duplicate split case {case_id}")
        case_ids.add(case_id)
        benchmark_case = benchmark_cases.get(case_id)
        if benchmark_case is None:
            fail(f"split case {case_id} does not exist in the bound benchmark")

        for field in ("challengeId", "split"):
            if entry[field] != benchmark_case[field]:
                fail(f"{case_id} {field} differs from the bound benchmark")
        if entry["structuralSignature"] != benchmark_case["structuralCluster"]:
            fail(f"{case_id} structural signature differs from the benchmark")
        if entry["formationVisible"] is not benchmark_case["formationVisible"]:
            fail(f"{case_id} formation visibility differs from the benchmark")
        if entry["formationVisible"] is not (entry["split"] == "TRAIN"):
            fail(f"{case_id} leaks a non-TRAIN case into formation")

        expected_family = (
            f"{entry['challengeId']}/{entry['structuralSignature']}"
        )
        if entry["familyId"] != expected_family:
            fail(f"{case_id} family identity is not canonical")

        exact_material = {
            "caseId": case_id,
            "challengeId": entry["challengeId"],
            "structuralSignature": entry["structuralSignature"],
            "split": entry["split"],
        }
        expected_exact = canonical_hash(exact_material)
        if entry["exactFingerprint"] != expected_exact:
            fail(f"{case_id} exact fingerprint drift")

        alpha_signature = re.sub(
            r"^(train|validation|test)-", "", entry["structuralSignature"]
        )
        alpha_material = {
            "challengeId": entry["challengeId"],
            "alphaStructuralSignature": alpha_signature,
        }
        expected_alpha = canonical_hash(alpha_material)
        if entry["alphaStructuralFingerprint"] != expected_alpha:
            fail(f"{case_id} alpha-structural fingerprint drift")

        for collection, value, label in (
            (family_ids, entry["familyId"], "family"),
            (signatures, entry["structuralSignature"], "structural signature"),
            (exact_fingerprints, entry["exactFingerprint"], "exact fingerprint"),
            (
                alpha_fingerprints,
                entry["alphaStructuralFingerprint"],
                "alpha-structural fingerprint",
            ),
        ):
            if value in collection:
                fail(f"split collision for {label} {value}")
            collection.add(value)
        split_counts[entry["split"]] += 1

    if case_ids != set(benchmark_cases):
        fail(
            "evolution split coverage differs from the bound benchmark: "
            f"missing={sorted(set(benchmark_cases) - case_ids)} "
            f"extra={sorted(case_ids - set(benchmark_cases))}"
        )
    if split_counts != EXPECTED_SPLIT_COUNTS:
        fail(f"unexpected split counts: {dict(split_counts)}")


def main() -> None:
    source_path = Path(
        sys.argv[1]
        if len(sys.argv) > 1
        else "research/evolution/evolution-study-source.json"
    ).resolve()
    root = Path.cwd().resolve()
    source = load_unique_json(source_path)
    verify_source_identity(source)
    benchmark_cases = verify_benchmark_binding(root, source)
    verify_boundaries(source)
    verify_population_and_fitness(source)
    verify_split_manifest(source, benchmark_cases)
    print(f"verifiedEvolutionStudy={source['studyId']}")
    print(f"studyContentHash={source['contentHash']}")
    print(f"benchmarkSourceHash={EXPECTED_BENCHMARK_HASH}")
    print("splitCounts=TRAIN:6,VALIDATION:6,TEST:6")
    print("executionStatus=NOT_STARTED")
    print("finalTestAccess=NOT_ACCESSED")


if __name__ == "__main__":
    main()
