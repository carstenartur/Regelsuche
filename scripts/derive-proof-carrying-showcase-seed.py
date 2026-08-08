#!/usr/bin/env python3
"""Derive the showcase FINAL TEST seed only after a complete candidate freeze.

This command deliberately does not fetch drand itself. It accepts a receipt
created by a later pinned, signature-verifying drand client, revalidates the
frozen ordering/identity contract, and derives a domain-separated seed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import tempfile
from pathlib import Path
from typing import Any

PLAN_SCHEMA = "regelsuche.proof-carrying-self-improvement-showcase-plan/v1"
CANDIDATE_SCHEMA = "regelsuche.proof-carrying-showcase-candidate-freeze/v1"
RANDOMNESS_SCHEMA = "regelsuche.proof-carrying-showcase-public-randomness-receipt/v1"
SEED_SCHEMA = "regelsuche.proof-carrying-showcase-seed-receipt/v1"
DOMAIN = "regelsuche.proof-carrying-showcase-seed/v1"
SHA256_RE = re.compile(r"sha256:[0-9a-f]{64}")
HEX_64_RE = re.compile(r"[0-9a-f]{64}")
HEX_RE = re.compile(r"[0-9a-f]+")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")


class ContractError(ValueError):
    """Raised when a frozen showcase input is malformed or inconsistent."""


def fail(message: str) -> None:
    raise ContractError(message)


def load_unique_json(path: Path) -> dict[str, Any]:
    def pairs_hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                fail(f"duplicate JSON field {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs_hook)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        fail(f"unable to read strict JSON {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"top-level JSON must be an object: {path}")
    return value


def require_exact_fields(value: dict[str, Any], expected: set[str], context: str) -> None:
    actual = set(value)
    unknown = sorted(actual - expected)
    missing = sorted(expected - actual)
    if unknown or missing:
        fail(f"{context} fields differ: unknown={unknown}, missing={missing}")


def require_sha256(value: Any, context: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        fail(f"{context} must be sha256:<64 lowercase hex characters>")
    return value


def require_int(value: Any, minimum: int, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        fail(f"{context} must be an integer >= {minimum}")
    return value


def require_bool(value: Any, expected: bool, context: str) -> None:
    if value is not expected:
        fail(f"{context} must be {str(expected).lower()}")


def canonical_without_content_hash(value: dict[str, Any]) -> bytes:
    payload = {key: item for key, item in value.items() if key != "contentHash"}
    return json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def content_hash(value: dict[str, Any]) -> str:
    return "sha256:" + hashlib.sha256(canonical_without_content_hash(value)).hexdigest()


def require_content_hash(value: dict[str, Any], context: str) -> str:
    declared = require_sha256(value.get("contentHash"), f"{context}.contentHash")
    expected = content_hash(value)
    if declared != expected:
        fail(f"{context}.contentHash mismatch: declared={declared}, expected={expected}")
    return declared


def validate_plan(plan: dict[str, Any]) -> None:
    expected = {
        "schema",
        "showcaseId",
        "issue",
        "status",
        "claimPolicy",
        "publicationGradeFlagship",
        "candidateFormation",
        "publicRandomness",
        "challengeGenerator",
        "comparison",
        "acceptance",
        "requiredArtifacts",
        "stageStates",
        "contentHash",
    }
    require_exact_fields(plan, expected, "plan")
    if plan["schema"] != PLAN_SCHEMA:
        fail("unsupported showcase-plan schema")
    if plan["status"] != "CONTRACT_FROZEN_NOT_RUN":
        fail("seed derivation requires an unexecuted frozen showcase contract")
    if plan["claimPolicy"] != (
        "SHOWCASE_CONFIRMED_DOES_NOT_IMPLY_EXPERT_REVIEW_OR_EXTERNAL_NOVELTY"
    ):
        fail("showcase claim policy drift")
    if plan["publicationGradeFlagship"] != "DEFERRED_PENDING_INDEPENDENT_REVIEW":
        fail("publication-grade flagship boundary drift")
    require_content_hash(plan, "plan")

    formation = plan["candidateFormation"]
    if not isinstance(formation, dict):
        fail("candidateFormation must be an object")
    if formation.get("visibleSplits") != ["TRAIN"]:
        fail("candidate formation must be TRAIN-only")
    require_bool(
        formation.get("candidateFreezeRequiredBeforeRandomnessRound"),
        True,
        "candidate freeze ordering",
    )
    properties = formation.get("requiredCandidateProperties")
    if not isinstance(properties, dict):
        fail("requiredCandidateProperties must be an object")
    require_bool(properties.get("notSeedEquivalent"), True, "notSeedEquivalent")
    require_bool(properties.get("compositionTopology"), True, "compositionTopology")
    require_bool(properties.get("decisionTopology"), True, "decisionTopology")
    require_int(
        properties.get("minimumPrimitiveStepsOnSuccessfulPath"),
        3,
        "minimumPrimitiveStepsOnSuccessfulPath",
    )

    randomness = plan["publicRandomness"]
    if not isinstance(randomness, dict):
        fail("publicRandomness must be an object")
    if randomness.get("provider") != "DRAND_LEAGUE_OF_ENTROPY":
        fail("public randomness provider drift")
    if randomness.get("network") != "default":
        fail("public randomness network drift")
    chain_hash = randomness.get("chainHash")
    if not isinstance(chain_hash, str) or not HEX_64_RE.fullmatch(chain_hash):
        fail("public randomness chainHash must be 64 lowercase hex characters")
    if randomness.get("roundSelection") != (
        "FIRST_VERIFIED_ROUND_STRICTLY_AFTER_CANDIDATE_NOT_BEFORE"
    ):
        fail("round-selection policy drift")
    if randomness.get("signatureVerification") != (
        "PINNED_DRAND_CLIENT_AND_CHAIN_INFO_REQUIRED"
    ):
        fail("randomness verification policy drift")
    if randomness.get("seedDerivation") != "SHA256_DOMAIN_SEPARATED_V1":
        fail("seed derivation policy drift")
    require_int(
        randomness.get("minimumDelaySecondsAfterCandidateFreeze"),
        1,
        "minimumDelaySecondsAfterCandidateFreeze",
    )


def validate_candidate(candidate: dict[str, Any], plan: dict[str, Any]) -> None:
    expected = {
        "schema",
        "showcaseId",
        "planContentHash",
        "repositoryCommit",
        "trainingRunHash",
        "selectionEvidenceHash",
        "candidateContentHash",
        "candidateAlphaStructuralHash",
        "humanReadableProgramHash",
        "primitiveInventoryHash",
        "workBudgetPolicyHash",
        "evaluationProtocolHash",
        "seedCandidateHashes",
        "programNodeCount",
        "containsCompositionTopology",
        "containsDecisionTopology",
        "minimumDeclaredPrimitivePathSteps",
        "frozenAtUnixTime",
        "randomnessNotBeforeUnixTime",
        "status",
        "contentHash",
    }
    require_exact_fields(candidate, expected, "candidate freeze")
    if candidate["schema"] != CANDIDATE_SCHEMA:
        fail("unsupported candidate-freeze schema")
    if candidate["showcaseId"] != plan["showcaseId"]:
        fail("candidate freeze uses another showcase")
    if candidate["planContentHash"] != plan["contentHash"]:
        fail("candidate freeze is not bound to the frozen plan")
    if not isinstance(candidate["repositoryCommit"], str) or not COMMIT_RE.fullmatch(
        candidate["repositoryCommit"]
    ):
        fail("candidate repositoryCommit must be a lowercase 40-character commit")
    for field in (
        "trainingRunHash",
        "selectionEvidenceHash",
        "candidateContentHash",
        "candidateAlphaStructuralHash",
        "humanReadableProgramHash",
        "primitiveInventoryHash",
        "workBudgetPolicyHash",
        "evaluationProtocolHash",
    ):
        require_sha256(candidate[field], f"candidate freeze.{field}")
    seed_hashes = candidate["seedCandidateHashes"]
    if not isinstance(seed_hashes, list) or not seed_hashes:
        fail("candidate freeze.seedCandidateHashes must be a non-empty array")
    if len(seed_hashes) != len(set(seed_hashes)):
        fail("candidate freeze contains duplicate seed candidate hashes")
    for index, seed_hash in enumerate(seed_hashes):
        require_sha256(seed_hash, f"candidate freeze.seedCandidateHashes[{index}]")
    if candidate["candidateContentHash"] in seed_hashes:
        fail("frozen candidate is exactly seed-equivalent")
    require_int(candidate["programNodeCount"], 1, "candidate freeze.programNodeCount")
    require_bool(
        candidate["containsCompositionTopology"],
        True,
        "candidate freeze.containsCompositionTopology",
    )
    require_bool(
        candidate["containsDecisionTopology"],
        True,
        "candidate freeze.containsDecisionTopology",
    )
    required_steps = plan["candidateFormation"]["requiredCandidateProperties"][
        "minimumPrimitiveStepsOnSuccessfulPath"
    ]
    if require_int(
        candidate["minimumDeclaredPrimitivePathSteps"],
        required_steps,
        "candidate freeze.minimumDeclaredPrimitivePathSteps",
    ) < required_steps:
        fail("candidate primitive-path floor is below the frozen plan")
    frozen_at = require_int(
        candidate["frozenAtUnixTime"], 1, "candidate freeze.frozenAtUnixTime"
    )
    not_before = require_int(
        candidate["randomnessNotBeforeUnixTime"],
        1,
        "candidate freeze.randomnessNotBeforeUnixTime",
    )
    minimum_delay = plan["publicRandomness"]["minimumDelaySecondsAfterCandidateFreeze"]
    if not_before < frozen_at + minimum_delay:
        fail("candidate randomnessNotBefore boundary violates the minimum delay")
    if candidate["status"] != "CANDIDATE_FROZEN_FINAL_TEST_UNSEEN":
        fail("candidate freeze status does not preserve the FINAL TEST boundary")
    require_content_hash(candidate, "candidate freeze")


def validate_randomness(
    receipt: dict[str, Any], plan: dict[str, Any], candidate: dict[str, Any]
) -> None:
    expected = {
        "schema",
        "showcaseId",
        "planContentHash",
        "candidateFreezeContentHash",
        "network",
        "chainHash",
        "round",
        "roundUnixTime",
        "randomness",
        "signature",
        "previousSignature",
        "chainInfoHash",
        "verificationClient",
        "verificationClientArtifactHash",
        "verificationEvidenceHash",
        "verificationStatus",
        "endpointId",
        "contentHash",
    }
    require_exact_fields(receipt, expected, "public randomness receipt")
    if receipt["schema"] != RANDOMNESS_SCHEMA:
        fail("unsupported public-randomness receipt schema")
    if receipt["showcaseId"] != plan["showcaseId"]:
        fail("randomness receipt uses another showcase")
    if receipt["planContentHash"] != plan["contentHash"]:
        fail("randomness receipt is not bound to the frozen plan")
    if receipt["candidateFreezeContentHash"] != candidate["contentHash"]:
        fail("randomness receipt is not bound to the frozen candidate")
    policy = plan["publicRandomness"]
    if receipt["network"] != policy["network"]:
        fail("randomness network differs from the frozen plan")
    if receipt["chainHash"] != policy["chainHash"]:
        fail("randomness chain differs from the frozen plan")
    require_int(receipt["round"], 1, "public randomness receipt.round")
    round_time = require_int(
        receipt["roundUnixTime"], 1, "public randomness receipt.roundUnixTime"
    )
    if round_time <= candidate["randomnessNotBeforeUnixTime"]:
        fail("drand round is not strictly after the frozen candidate boundary")
    randomness = receipt["randomness"]
    if not isinstance(randomness, str) or not HEX_64_RE.fullmatch(randomness):
        fail("drand randomness must be 32 bytes of lowercase hex")
    for field in ("signature", "previousSignature"):
        value = receipt[field]
        if (
            not isinstance(value, str)
            or len(value) < 96
            or len(value) % 2 != 0
            or not HEX_RE.fullmatch(value)
        ):
            fail(f"public randomness receipt.{field} is not bounded lowercase hex")
    for field in (
        "chainInfoHash",
        "verificationClientArtifactHash",
        "verificationEvidenceHash",
    ):
        require_sha256(receipt[field], f"public randomness receipt.{field}")
    client = receipt["verificationClient"]
    if not isinstance(client, str) or not client.startswith("drand-client/"):
        fail("verificationClient must identify the pinned drand client")
    if receipt["verificationStatus"] != "VERIFIED_BY_PINNED_DRAND_CLIENT":
        fail("unverified drand randomness cannot generate FINAL TEST")
    endpoint = receipt["endpointId"]
    if not isinstance(endpoint, str) or not re.fullmatch(r"[a-z0-9.-]{3,128}", endpoint):
        fail("endpointId has invalid syntax")
    require_content_hash(receipt, "public randomness receipt")


def derive_seed(
    plan: dict[str, Any], candidate: dict[str, Any], receipt: dict[str, Any]
) -> str:
    material = "\n".join(
        (
            DOMAIN,
            f"showcaseId={plan['showcaseId']}",
            f"planContentHash={plan['contentHash']}",
            f"candidateFreezeContentHash={candidate['contentHash']}",
            f"chainHash={receipt['chainHash']}",
            f"round={receipt['round']}",
            f"randomness={receipt['randomness']}",
            f"randomnessReceiptContentHash={receipt['contentHash']}",
        )
    )
    return "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()


def create_seed_receipt(
    plan: dict[str, Any], candidate: dict[str, Any], receipt: dict[str, Any]
) -> dict[str, Any]:
    validate_plan(plan)
    validate_candidate(candidate, plan)
    validate_randomness(receipt, plan, candidate)
    result: dict[str, Any] = {
        "schema": SEED_SCHEMA,
        "showcaseId": plan["showcaseId"],
        "planContentHash": plan["contentHash"],
        "candidateFreezeContentHash": candidate["contentHash"],
        "randomnessReceiptContentHash": receipt["contentHash"],
        "drandChainHash": receipt["chainHash"],
        "drandRound": receipt["round"],
        "derivationAlgorithm": "SHA256_DOMAIN_SEPARATED_V1",
        "derivedSeed": derive_seed(plan, candidate, receipt),
        "status": "FINAL_TEST_SEED_DERIVED_AFTER_CANDIDATE_FREEZE",
    }
    result["contentHash"] = content_hash(result)
    return result


def write_canonical_pretty(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def hashed_fixture(value: dict[str, Any]) -> dict[str, Any]:
    result = dict(value)
    result["contentHash"] = content_hash(result)
    return result


def self_test(plan_path: Path) -> None:
    plan = load_unique_json(plan_path)
    validate_plan(plan)
    h = lambda label: "sha256:" + hashlib.sha256(label.encode("utf-8")).hexdigest()
    candidate = hashed_fixture(
        {
            "schema": CANDIDATE_SCHEMA,
            "showcaseId": plan["showcaseId"],
            "planContentHash": plan["contentHash"],
            "repositoryCommit": "1" * 40,
            "trainingRunHash": h("training"),
            "selectionEvidenceHash": h("selection"),
            "candidateContentHash": h("candidate"),
            "candidateAlphaStructuralHash": h("candidate-alpha"),
            "humanReadableProgramHash": h("program"),
            "primitiveInventoryHash": h("inventory"),
            "workBudgetPolicyHash": h("budget"),
            "evaluationProtocolHash": h("protocol"),
            "seedCandidateHashes": [h("seed-a"), h("seed-b")],
            "programNodeCount": 7,
            "containsCompositionTopology": True,
            "containsDecisionTopology": True,
            "minimumDeclaredPrimitivePathSteps": 3,
            "frozenAtUnixTime": 2_000_000_000,
            "randomnessNotBeforeUnixTime": 2_000_000_300,
            "status": "CANDIDATE_FROZEN_FINAL_TEST_UNSEEN",
        }
    )
    randomness = hashed_fixture(
        {
            "schema": RANDOMNESS_SCHEMA,
            "showcaseId": plan["showcaseId"],
            "planContentHash": plan["contentHash"],
            "candidateFreezeContentHash": candidate["contentHash"],
            "network": plan["publicRandomness"]["network"],
            "chainHash": plan["publicRandomness"]["chainHash"],
            "round": 99_999_999,
            "roundUnixTime": 2_000_000_301,
            "randomness": "ab" * 32,
            "signature": "cd" * 96,
            "previousSignature": "ef" * 96,
            "chainInfoHash": h("chain-info"),
            "verificationClient": "drand-client/verified-fixture",
            "verificationClientArtifactHash": h("client"),
            "verificationEvidenceHash": h("verification"),
            "verificationStatus": "VERIFIED_BY_PINNED_DRAND_CLIENT",
            "endpointId": "fixture.drand.invalid",
        }
    )
    first = create_seed_receipt(plan, candidate, randomness)
    second = create_seed_receipt(plan, candidate, randomness)
    if first != second:
        fail("seed derivation is not deterministic")

    tampered = dict(randomness)
    tampered["roundUnixTime"] = candidate["randomnessNotBeforeUnixTime"]
    tampered["contentHash"] = content_hash(tampered)
    try:
        create_seed_receipt(plan, candidate, tampered)
    except ContractError:
        pass
    else:
        fail("ordering violation was accepted")

    substituted = dict(randomness)
    substituted["randomness"] = "01" * 32
    substituted["contentHash"] = content_hash(substituted)
    third = create_seed_receipt(plan, candidate, substituted)
    if third["derivedSeed"] == first["derivedSeed"]:
        fail("randomness substitution did not change the derived seed")

    with tempfile.TemporaryDirectory(prefix="regelsuche-showcase-seed-") as directory:
        output = Path(directory) / "showcase-seed-receipt.json"
        write_canonical_pretty(output, first)
        loaded = load_unique_json(output)
        require_content_hash(loaded, "self-test seed receipt")
        if loaded != first:
            fail("seed receipt roundtrip changed semantics")

    print(f"showcaseId={plan['showcaseId']}")
    print(f"planContentHash={plan['contentHash']}")
    print("seedDerivationSelfTest=PASS")
    print("orderingTamperRejection=PASS")
    print("randomnessSubstitutionSensitivity=PASS")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--candidate-freeze", type=Path)
    parser.add_argument("--randomness-receipt", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.self_test:
        if args.plan is None:
            fail("--self-test requires --plan")
        self_test(args.plan)
        return
    required = {
        "--plan": args.plan,
        "--candidate-freeze": args.candidate_freeze,
        "--randomness-receipt": args.randomness_receipt,
        "--output": args.output,
    }
    missing = [name for name, value in required.items() if value is None]
    if missing:
        fail("missing required arguments: " + ", ".join(missing))
    plan = load_unique_json(args.plan)
    candidate = load_unique_json(args.candidate_freeze)
    randomness = load_unique_json(args.randomness_receipt)
    seed_receipt = create_seed_receipt(plan, candidate, randomness)
    write_canonical_pretty(args.output, seed_receipt)
    print(f"showcaseSeedStatus={seed_receipt['status']}")
    print(f"showcaseSeed={seed_receipt['derivedSeed']}")
    print(f"showcaseSeedReceiptHash={seed_receipt['contentHash']}")


if __name__ == "__main__":
    try:
        main()
    except ContractError as exc:
        raise SystemExit(f"showcase seed derivation rejected: {exc}") from exc
