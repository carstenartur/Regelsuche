#!/usr/bin/env python3
"""Generate the proof-carrying showcase FINAL TEST from a frozen seed receipt.

The generator is deterministic and has no network access. Concrete cases are
created only from a seed receipt that is already bound to the frozen showcase
plan and candidate. The output remains NOT_EXECUTED.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

PLAN_SCHEMA = "regelsuche.proof-carrying-self-improvement-showcase-plan/v1"
SEED_SCHEMA = "regelsuche.proof-carrying-showcase-seed-receipt/v1"
OUTPUT_SCHEMA = "regelsuche.proof-carrying-showcase-generated-final-test/v1"
CASE_SCHEMA = "regelsuche.proof-carrying-showcase-generated-case/v1"
GENERATOR_ID = "proof-carrying-symbolic-stress-ladders/v1"
CASE_IDENTITY = "SHA256_CANONICAL_CASE_V1"
STATUS = "FINAL_TEST_GENERATED_NOT_EXECUTED"
DOMAIN = "regelsuche.proof-carrying-showcase-case-generator/v1"
SHA256_RE = re.compile(r"sha256:[0-9a-f]{64}")


class GenerationError(ValueError):
    """Raised when a frozen input or generated surface is invalid."""


def fail(message: str) -> None:
    raise GenerationError(message)


def load_unique_json(path: Path) -> dict[str, Any]:
    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=hook)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"unable to read strict JSON {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"top-level JSON must be an object: {path}")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def content_hash(value: dict[str, Any]) -> str:
    payload = {key: item for key, item in value.items() if key != "contentHash"}
    return "sha256:" + hashlib.sha256(canonical_bytes(payload)).hexdigest()


def require_content_hash(value: dict[str, Any], context: str) -> str:
    declared = value.get("contentHash")
    if not isinstance(declared, str) or not SHA256_RE.fullmatch(declared):
        fail(f"{context}.contentHash must be SHA-256")
    expected = content_hash(value)
    if declared != expected:
        fail(f"{context}.contentHash mismatch: declared={declared}, expected={expected}")
    return declared


def require_exact_fields(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    unknown = sorted(actual - expected)
    missing = sorted(expected - actual)
    if unknown or missing:
        fail(f"{context} fields differ: unknown={unknown}, missing={missing}")


def require_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        fail(f"{context} must be SHA-256")
    return value


def validate_plan(plan: dict[str, Any]) -> None:
    if plan.get("schema") != PLAN_SCHEMA:
        fail("unsupported showcase-plan schema")
    if plan.get("status") != "CONTRACT_FROZEN_NOT_RUN":
        fail("case generation requires the frozen unexecuted showcase plan")
    if plan.get("claimPolicy") != (
        "SHOWCASE_CONFIRMED_DOES_NOT_IMPLY_EXPERT_REVIEW_OR_EXTERNAL_NOVELTY"
    ):
        fail("showcase claim boundary drift")
    require_content_hash(plan, "plan")
    generator = plan.get("challengeGenerator")
    if not isinstance(generator, dict):
        fail("plan.challengeGenerator must be an object")
    if generator.get("generatorId") != GENERATOR_ID:
        fail("challenge-generator identity drift")
    if generator.get("caseCount") != 24:
        fail("the first showcase generator must produce exactly 24 cases")
    if generator.get("sameGeneratedCasesForAllConfigurations") is not True:
        fail("all configurations must receive the same generated cases")
    if generator.get("assumptionsRetained") is not True:
        fail("generated assumptions must remain explicit")
    if generator.get("caseIdentity") != CASE_IDENTITY:
        fail("case identity policy drift")
    if generator.get("manualReplacementOrPruning") != "FORBIDDEN":
        fail("manual case replacement or pruning must remain forbidden")


def validate_seed_receipt(seed: dict[str, Any], plan: dict[str, Any]) -> None:
    require_exact_fields(
        seed,
        {
            "schema",
            "showcaseId",
            "planContentHash",
            "candidateFreezeContentHash",
            "randomnessReceiptContentHash",
            "drandChainHash",
            "drandRound",
            "derivationAlgorithm",
            "derivedSeed",
            "status",
            "contentHash",
        },
        "seed receipt",
    )
    if seed["schema"] != SEED_SCHEMA:
        fail("unsupported showcase seed schema")
    if seed["showcaseId"] != plan["showcaseId"]:
        fail("seed receipt uses another showcase")
    if seed["planContentHash"] != plan["contentHash"]:
        fail("seed receipt is not bound to the frozen plan")
    for field in (
        "candidateFreezeContentHash",
        "randomnessReceiptContentHash",
        "derivedSeed",
    ):
        require_sha256(seed[field], f"seed receipt.{field}")
    if seed["drandChainHash"] != plan["publicRandomness"]["chainHash"]:
        fail("seed receipt uses another drand chain")
    if isinstance(seed["drandRound"], bool) or not isinstance(seed["drandRound"], int) \
            or seed["drandRound"] < 1:
        fail("seed receipt.drandRound must be a positive integer")
    if seed["derivationAlgorithm"] != "SHA256_DOMAIN_SEPARATED_V1":
        fail("seed derivation algorithm drift")
    if seed["status"] != "FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE":
        fail("FINAL TEST seed is not authorized after candidate freeze")
    require_content_hash(seed, "seed receipt")


def digest(seed: str, family_id: str, difficulty: int, variant: int) -> bytes:
    material = "\n".join(
        (
            DOMAIN,
            f"seed={seed}",
            f"family={family_id}",
            f"difficulty={difficulty}",
            f"variant={variant}",
        )
    )
    return hashlib.sha256(material.encode("utf-8")).digest()


def coefficient_vector(data: bytes, count: int, case_ordinal: int) -> list[int]:
    result: list[int] = []
    for index in range(count):
        # Keep coefficients positive, bounded and structurally case-specific.
        value = 2 + ((data[index % len(data)] + 17 * case_ordinal + 11 * index) % 23)
        result.append(value)
    return result


def parenthesized_product(factors: list[str]) -> str:
    if not factors:
        return "1"
    result = factors[0]
    for factor in factors[1:]:
        result = f"({result}*{factor})"
    return result


def parenthesized_sum(terms: list[str]) -> str:
    if not terms:
        return "0"
    result = terms[0]
    for term in terms[1:]:
        result = f"({result}+{term})"
    return result


def left_division(blocks: list[str]) -> str:
    if not blocks:
        fail("left division requires blocks")
    result = blocks[0]
    for block in blocks[1:]:
        result = f"({result})/({block})"
    return result


def normalized_assumptions(values: list[str]) -> list[str]:
    return sorted(set(values))


@dataclass(frozen=True)
class GeneratedMaterial:
    input_expression: str
    target_expression: str
    assumptions: list[str]
    coefficient_vector: list[int]
    block_kinds: list[str]


def nested_rational_case(
    case_ordinal: int,
    difficulty: int,
    variant: int,
    data: bytes,
) -> GeneratedMaterial:
    coefficients = coefficient_vector(data, difficulty, case_ordinal)
    blocks: list[str] = []
    numerators: list[str] = []
    denominators: list[str] = []
    assumptions: list[str] = []
    for index, coefficient in enumerate(coefficients):
        suffix = f"{case_ordinal}_{index}"
        p = f"p{suffix}"
        q = f"q{suffix}"
        factor = f"f{suffix}"
        quotient = f"(({p}/{factor})/({q}/{factor}))"
        block = (
            f"({coefficient}*{quotient})"
            if variant == 0
            else f"((({coefficient}*{p})/{factor})/({q}/{factor}))"
        )
        blocks.append(block)
        numerators.append(f"({coefficient}*{p})")
        denominators.append(q)
        assumptions.extend((f"{factor} != 0", f"{q} != 0"))
        if index > 0:
            assumptions.append(f"{p} != 0")

    target_numerator = parenthesized_product(
        [numerators[0], *denominators[1:]]
    )
    target_denominator = parenthesized_product(
        [denominators[0], *numerators[1:]]
    )
    return GeneratedMaterial(
        input_expression=left_division(blocks),
        target_expression=f"({target_numerator})/({target_denominator})",
        assumptions=normalized_assumptions(assumptions),
        coefficient_vector=coefficients,
        block_kinds=["SHARED_DENOMINATOR_QUOTIENT"] * difficulty,
    )


def factor_cancel_collect_case(
    case_ordinal: int,
    difficulty: int,
    variant: int,
    data: bytes,
) -> GeneratedMaterial:
    coefficients = coefficient_vector(data, difficulty, case_ordinal)
    denominator = f"d{case_ordinal}"
    terms: list[str] = []
    target_terms: list[str] = []
    assumptions = [f"{denominator} != 0"]
    for index, coefficient in enumerate(coefficients):
        suffix = f"{case_ordinal}_{index}"
        left = f"a{suffix}"
        right = f"b{suffix}"
        difference = f"({left}^2-{right}^2)"
        divisor = f"({left}-{right})"
        if variant == 0:
            term = f"({coefficient}*((({difference})/{divisor})/{denominator}))"
        else:
            term = f"((({coefficient}*{difference})/{divisor})/{denominator})"
        terms.append(term)
        target_terms.append(f"({coefficient}*({left}+{right}))")
        assumptions.append(f"({left}-{right}) != 0")

    return GeneratedMaterial(
        input_expression=parenthesized_sum(terms),
        target_expression=f"({parenthesized_sum(target_terms)})/{denominator}",
        assumptions=normalized_assumptions(assumptions),
        coefficient_vector=coefficients,
        block_kinds=["FACTOR_CANCEL_SHARED_DENOMINATOR"] * difficulty,
    )


def multi_stage_case(
    case_ordinal: int,
    difficulty: int,
    variant: int,
    data: bytes,
) -> GeneratedMaterial:
    coefficients = coefficient_vector(data, difficulty, case_ordinal)
    blocks: list[str] = []
    numerators: list[str] = []
    denominators: list[str] = []
    assumptions: list[str] = []
    block_kinds: list[str] = []

    for index, coefficient in enumerate(coefficients):
        suffix = f"{case_ordinal}_{index}"
        if index % 2 == 0:
            a = f"r{suffix}"
            b = f"s{suffix}"
            x = f"x{suffix}"
            y = f"y{suffix}"
            if variant == 0:
                numerator = f"({coefficient}*(({a}/{x})-({a}/{y})))"
            else:
                numerator = f"((({coefficient}*{a})/{x})-(({coefficient}*{a})/{y}))"
            denominator = f"(({b}/{x})-({b}/{y}))"
            blocks.append(f"({numerator})/({denominator})")
            numerators.append(f"({coefficient}*{a})")
            denominators.append(b)
            assumptions.extend(
                (
                    f"{x} != 0",
                    f"{y} != 0",
                    f"{b} != 0",
                    f"({y}-{x}) != 0",
                )
            )
            if index > 0:
                assumptions.append(f"{a} != 0")
            block_kinds.append("MIXED_DENOMINATOR_RATIO")
        else:
            p = f"u{suffix}"
            q = f"v{suffix}"
            difference = f"({p}^2-{q}^2)"
            divisor = f"({p}-{q})"
            block = (
                f"({coefficient}*(({difference})/{divisor}))"
                if variant == 0
                else f"(({coefficient}*{difference})/{divisor})"
            )
            blocks.append(block)
            numerators.append(f"({coefficient}*({p}+{q}))")
            denominators.append("1")
            assumptions.extend(
                (
                    f"({p}-{q}) != 0",
                    f"({p}+{q}) != 0",
                )
            )
            block_kinds.append("DIFFERENCE_OF_SQUARES_QUOTIENT")

    target_numerator = parenthesized_product(
        [numerators[0], *[value for value in denominators[1:] if value != "1"]]
    )
    target_denominator = parenthesized_product(
        [denominators[0], *numerators[1:]]
    )
    return GeneratedMaterial(
        input_expression=left_division(blocks),
        target_expression=f"({target_numerator})/({target_denominator})",
        assumptions=normalized_assumptions(assumptions),
        coefficient_vector=coefficients,
        block_kinds=block_kinds,
    )


GENERATORS: dict[str, Callable[[int, int, int, bytes], GeneratedMaterial]] = {
    "nested-rational-cancellation": nested_rational_case,
    "factor-cancel-collect": factor_cancel_collect_case,
    "multi-stage-rational-polynomial": multi_stage_case,
}


def case_record(
    plan: dict[str, Any],
    seed: dict[str, Any],
    family_id: str,
    difficulty: int,
    variant: int,
    case_ordinal: int,
) -> dict[str, Any]:
    data = digest(seed["derivedSeed"], family_id, difficulty, variant)
    material = GENERATORS[family_id](case_ordinal, difficulty, variant, data)
    structural_material = {
        "schema": "regelsuche.proof-carrying-showcase-structure/v1",
        "familyId": family_id,
        "difficultyLevel": difficulty,
        "variant": variant,
        "coefficientVector": material.coefficient_vector,
        "blockKinds": material.block_kinds,
    }
    structural_fingerprint = (
        "sha256:" + hashlib.sha256(canonical_bytes(structural_material)).hexdigest()
    )
    id_material = "\n".join(
        (
            "regelsuche.proof-carrying-showcase-case-id/v1",
            f"showcaseId={plan['showcaseId']}",
            f"seedReceiptContentHash={seed['contentHash']}",
            f"familyId={family_id}",
            f"difficultyLevel={difficulty}",
            f"variant={variant}",
            f"structuralFingerprint={structural_fingerprint}",
        )
    )
    case_id_hash = hashlib.sha256(id_material.encode("utf-8")).hexdigest()
    short_family = {
        "nested-rational-cancellation": "nrc",
        "factor-cancel-collect": "fcc",
        "multi-stage-rational-polynomial": "mrp",
    }[family_id]
    result: dict[str, Any] = {
        "schema": CASE_SCHEMA,
        "caseId": f"ft-{short_family}-d{difficulty}-v{variant}-{case_id_hash[:12]}",
        "familyId": family_id,
        "difficultyLevel": difficulty,
        "variant": variant,
        "inputExpression": material.input_expression,
        "targetExpression": material.target_expression,
        "assumptions": material.assumptions,
        "coefficientVector": material.coefficient_vector,
        "blockKinds": material.block_kinds,
        "structuralFingerprint": structural_fingerprint,
        "caseIdentityPolicy": CASE_IDENTITY,
    }
    result["contentHash"] = content_hash(result)
    return result


def aggregate_root(domain: str, values: list[str]) -> str:
    material = domain + "\n" + "\n".join(values)
    return "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()


def generate(plan: dict[str, Any], seed: dict[str, Any]) -> dict[str, Any]:
    validate_plan(plan)
    validate_seed_receipt(seed, plan)
    family_specs = plan["challengeGenerator"]["families"]
    cases: list[dict[str, Any]] = []
    case_ordinal = 0
    for family in family_specs:
        family_id = family["familyId"]
        if family_id not in GENERATORS:
            fail(f"unsupported frozen showcase family: {family_id}")
        if family["caseCount"] != 8 or family["difficultyLevels"] != [3, 4, 5, 6]:
            fail(f"family contract drift: {family_id}")
        for difficulty in family["difficultyLevels"]:
            for variant in (0, 1):
                cases.append(
                    case_record(
                        plan,
                        seed,
                        family_id,
                        difficulty,
                        variant,
                        case_ordinal,
                    )
                )
                case_ordinal += 1

    if len(cases) != plan["challengeGenerator"]["caseCount"]:
        fail("generated case count differs from the frozen plan")
    for field in ("caseId", "contentHash", "inputExpression", "structuralFingerprint"):
        values = [case[field] for case in cases]
        if len(values) != len(set(values)):
            fail(f"generated cases contain duplicate {field}")

    family_summaries: list[dict[str, Any]] = []
    for family in family_specs:
        family_cases = [case for case in cases if case["familyId"] == family["familyId"]]
        if len(family_cases) != family["caseCount"]:
            fail(f"generated family count mismatch: {family['familyId']}")
        family_summaries.append(
            {
                "familyId": family["familyId"],
                "caseCount": len(family_cases),
                "difficultyLevels": sorted(
                    set(case["difficultyLevel"] for case in family_cases)
                ),
                "caseContentRoot": aggregate_root(
                    "regelsuche.proof-carrying-showcase-family-root/v1",
                    [case["contentHash"] for case in family_cases],
                ),
            }
        )

    result: dict[str, Any] = {
        "schema": OUTPUT_SCHEMA,
        "showcaseId": plan["showcaseId"],
        "planContentHash": plan["contentHash"],
        "candidateFreezeContentHash": seed["candidateFreezeContentHash"],
        "seedReceiptContentHash": seed["contentHash"],
        "generatorId": GENERATOR_ID,
        "derivedSeed": seed["derivedSeed"],
        "drandChainHash": seed["drandChainHash"],
        "drandRound": seed["drandRound"],
        "caseCount": len(cases),
        "familySummaries": family_summaries,
        "cases": cases,
        "caseContentRoot": aggregate_root(
            "regelsuche.proof-carrying-showcase-case-root/v1",
            [case["contentHash"] for case in cases],
        ),
        "caseIdentityPolicy": CASE_IDENTITY,
        "manualReplacementOrPruning": "FORBIDDEN",
        "status": STATUS,
    }
    result["contentHash"] = content_hash(result)
    return result


def write_pretty(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def fixture_seed(plan: dict[str, Any], label: str) -> dict[str, Any]:
    h = lambda value: "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()
    result: dict[str, Any] = {
        "schema": SEED_SCHEMA,
        "showcaseId": plan["showcaseId"],
        "planContentHash": plan["contentHash"],
        "candidateFreezeContentHash": h("fixture-candidate-" + label),
        "randomnessReceiptContentHash": h("fixture-randomness-" + label),
        "drandChainHash": plan["publicRandomness"]["chainHash"],
        "drandRound": 99_000_000,
        "derivationAlgorithm": "SHA256_DOMAIN_SEPARATED_V1",
        "derivedSeed": h("fixture-derived-seed-" + label),
        "status": "FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE",
    }
    result["contentHash"] = content_hash(result)
    return result


def self_test(plan_path: Path, output: Path | None = None) -> None:
    plan = load_unique_json(plan_path)
    first_seed = fixture_seed(plan, "a")
    first = generate(plan, first_seed)
    repeated = generate(plan, first_seed)
    if first != repeated:
        fail("case generation is not deterministic")
    second = generate(plan, fixture_seed(plan, "b"))
    if first["caseContentRoot"] == second["caseContentRoot"]:
        fail("seed substitution did not change the generated case surface")
    if first["caseCount"] != 24 or len(first["familySummaries"]) != 3:
        fail("self-test generated the wrong surface size")
    if any(summary["caseCount"] != 8 for summary in first["familySummaries"]):
        fail("self-test generated an unbalanced family")
    if any(summary["difficultyLevels"] != [3, 4, 5, 6]
           for summary in first["familySummaries"]):
        fail("self-test lost a frozen difficulty level")
    with tempfile.TemporaryDirectory(prefix="regelsuche-showcase-generator-") as directory:
        path = Path(directory) / "generated-final-test.json"
        write_pretty(path, first)
        loaded = load_unique_json(path)
        require_content_hash(loaded, "generated FINAL TEST self-test")
        if loaded != first:
            fail("generated FINAL TEST roundtrip changed semantics")
    if output is not None:
        write_pretty(output, first)
    print(f"showcaseId={plan['showcaseId']}")
    print(f"generatedCaseCount={first['caseCount']}")
    print(f"generatedFamilyCount={len(first['familySummaries'])}")
    print(f"generatedCaseContentRoot={first['caseContentRoot']}")
    print("generatorDeterminism=PASS")
    print("seedSubstitutionSensitivity=PASS")
    print("generatedSurfaceStatus=FINAL_TEST_GENERATED_NOT_EXECUTED")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--seed-receipt", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.self_test:
        self_test(args.plan, args.output)
        return
    if args.seed_receipt is None or args.output is None:
        fail("normal generation requires --seed-receipt and --output")
    plan = load_unique_json(args.plan)
    seed = load_unique_json(args.seed_receipt)
    generated = generate(plan, seed)
    write_pretty(args.output, generated)
    print(f"generatedFinalTestStatus={generated['status']}")
    print(f"generatedFinalTestCaseRoot={generated['caseContentRoot']}")
    print(f"generatedFinalTestContentHash={generated['contentHash']}")


if __name__ == "__main__":
    try:
        main()
    except GenerationError as exc:
        raise SystemExit(f"showcase case generation rejected: {exc}") from exc
