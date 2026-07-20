#!/usr/bin/env python3
"""Verify the candidate-independent case-payload freeze before execution."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any, Callable

try:
    from jsonschema import Draft202012Validator
except ImportError:
    print(
        "Missing pinned jsonschema. Run ./gradlew prepareVerificationEnvironment.",
        file=sys.stderr,
    )
    raise SystemExit(2)

ROOT = Path("research/benchmarks/candidate-independent")
SOURCE = ROOT / "benchmark-source.json"
CORPUS = ROOT / "case-corpus.json"
RECEIPT = ROOT / "corpus-freeze-receipt.json"
PROFILE_FILES = {
    "finite-sequence-candidate-forms/v1": ROOT / "finite-sequence-candidate-forms.json",
    "macro-primitives/v1": ROOT / "macro-primitives.json",
    "rational-assumption-primitives/v1": ROOT / "rational-assumption-primitives.json",
}
CORPUS_SCHEMA = Path(
    "docs/schemas/regelsuche-candidate-independent-case-corpus-v1.schema.json"
)
RECEIPT_SCHEMA = Path(
    "docs/schemas/regelsuche-candidate-independent-corpus-freeze-receipt-v1.schema.json"
)
PROFILE_SCHEMA = Path(
    "docs/schemas/regelsuche-candidate-independent-formation-inventory-profile-v1.schema.json"
)
EXPECTED_SOURCE_BLOB = "742caf75ea01290259b7952dbcc826bb6beaeed7"
EXPECTED_FOUNDATION_COMMIT = "f4e221273088e8b044baa9db81f23d060c856fc5"
EXPECTED_AMENDMENT = (
    "regelsuche-candidate-independent-autonomous-discovery-2026-07/"
    "case-payload-freeze/v1"
)
CHALLENGES = (
    "finite-difference-recurrences",
    "rational-assumption-rewrites",
    "reusable-search-macros",
)


class Invalid(RuntimeError):
    """A fail-closed corpus contract violation."""


def need(condition: bool, message: str) -> None:
    if not condition:
        raise Invalid(message)


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def semantic_hash(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canonical_bytes(value)).hexdigest()


def document_hash(document: dict[str, Any]) -> str:
    body = dict(document)
    body.pop("contentHash", None)
    return semantic_hash(body)


def load_unique(path: Path) -> dict[str, Any]:
    need(
        path.exists() and path.is_file() and not path.is_symlink(),
        f"missing/non-regular file: {path}",
    )

    def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            need(key not in result, f"duplicate field {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=hook,
        )
    except (OSError, json.JSONDecodeError) as error:
        raise Invalid(f"cannot parse {path}: {error}") from error
    need(isinstance(value, dict), f"expected object in {path}")
    return value


def validate(
    schema: dict[str, Any],
    document: dict[str, Any],
    label: str,
) -> None:
    Draft202012Validator.check_schema(schema)
    errors = sorted(
        Draft202012Validator(schema).iter_errors(document),
        key=lambda error: (list(error.absolute_path), error.message),
    )
    if errors:
        first = errors[0]
        location = "/".join(map(str, first.absolute_path)) or "<root>"
        raise Invalid(f"{label} schema violation at {location}: {first.message}")
    need(
        document.get("contentHash") == document_hash(document),
        f"{label} contentHash drift",
    )


def git_output(repository: Path, *arguments: str) -> str:
    process = subprocess.run(
        ["git", "-C", str(repository), *arguments],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    need(
        process.returncode == 0,
        f"git {' '.join(arguments)} failed: {process.stdout}",
    )
    return process.stdout.strip()


def unique_ids(values: list[dict[str, Any]], field: str, label: str) -> None:
    need(all(isinstance(value, dict) for value in values), f"{label} entry is not an object")
    need(all(field in value for value in values), f"{label} entry misses {field}")
    identities = [str(value[field]) for value in values]
    need(
        identities == sorted(identities),
        f"{label} identities are not sorted",
    )
    need(len(identities) == len(set(identities)), f"duplicate {label} identity")


def verify_profiles(
    profiles: dict[str, dict[str, Any]],
    profile_schema: dict[str, Any],
) -> dict[str, str]:
    need(set(profiles) == set(PROFILE_FILES), "formation profile set drift")
    for profile_id, document in profiles.items():
        validate(profile_schema, document, f"formation profile {profile_id}")
        need(document["profileId"] == profile_id, f"profile identity drift: {profile_id}")

    rational = profiles["rational-assumption-primitives/v1"]
    rational_operations = rational["operations"]
    unique_ids(rational_operations, "operationId", "rational operation")
    need(
        any(item["implementationStatus"] == "ADAPTER_REQUIRED" for item in rational_operations),
        "rational profile hides adapter-required operations",
    )

    finite = profiles["finite-sequence-candidate-forms/v1"]
    forms = finite["forms"]
    unique_ids(forms, "formId", "finite-sequence form")
    need(
        [item["formId"] for item in forms]
        == ["FINITE_DIFFERENCE_POLYNOMIAL", "LINEAR_RECURRENCE"],
        "finite-sequence candidate form drift",
    )
    need(
        not finite["ambiguityPolicy"].startswith("UNIQUE"),
        "finite-data profile implies unique infinite continuation",
    )

    macro = profiles["macro-primitives/v1"]
    operations = macro["operations"]
    unique_ids(operations, "operationId", "macro operation")
    need(
        all(operation["implementationRuleIds"] for operation in operations),
        "macro profile has an unbound operation",
    )
    return {
        profile_id: profiles[profile_id]["contentHash"]
        for profile_id in sorted(profiles)
    }


def verify_case_payload(
    case: dict[str, Any],
    source_case: dict[str, Any],
    profiles: dict[str, dict[str, Any]],
) -> None:
    for field in ("caseId", "challengeId", "split", "structuralCluster"):
        need(field in case and field in source_case, f"missing {field} in case binding")
        need(case[field] == source_case[field], f"{field} drift in {case['caseId']}")
    need(case["contentHash"] == document_hash(case), f"case hash drift: {case['caseId']}")

    split = case["split"]
    expected_formation = ["formationInput"] if split == "TRAIN" else []
    need(
        case["exposurePolicy"]
        == {
            "candidateFormationMayRead": expected_formation,
            "candidateFormationMustNotRead": ["evaluationInput"],
            "evaluatorMayRead": ["evaluationInput"],
        },
        f"exposure policy drift in {case['caseId']}",
    )
    if split == "TRAIN":
        need(case["formationInput"] is not None, f"missing TRAIN input in {case['caseId']}")
    else:
        need(case["formationInput"] is None, f"held-out formation leak in {case['caseId']}")

    challenge = case["challengeId"]
    evaluation = case["evaluationInput"]
    if challenge == "finite-difference-recurrences":
        finite_profile = profiles["finite-sequence-candidate-forms/v1"]
        frozen_forms = [item["formId"] for item in finite_profile["forms"]]
        need(
            evaluation["candidateFormsAllowed"] == frozen_forms,
            f"sequence form-profile drift in {case['caseId']}",
        )
        need(
            len(evaluation["observedPrefix"]) >= 4
            and len(evaluation["holdoutContinuation"]) >= 2,
            f"unbounded/empty sequence payload in {case['caseId']}",
        )
        need(
            not evaluation["uniquenessOfInfiniteContinuationClaimAllowed"]
            and evaluation["retainAmbiguousContinuations"],
            f"sequence ambiguity boundary drift in {case['caseId']}",
        )
        if split == "TRAIN":
            formation = case["formationInput"]
            need(
                formation["observedPrefix"] == evaluation["observedPrefix"]
                and formation["indexOrigin"] == evaluation["indexOrigin"]
                and formation["candidateFormsAllowed"] == frozen_forms
                and formation["maximumOrder"] == evaluation["maximumOrder"]
                and not formation["holdoutVisible"],
                f"sequence TRAIN/evaluator binding drift in {case['caseId']}",
            )
    elif challenge == "rational-assumption-rewrites":
        tasks = evaluation["tasks"]
        unique_ids(tasks, "taskId", f"rational task {case['caseId']}")
        need(
            all(task["source"] != task["target"] for task in tasks),
            f"trivial rational task in {case['caseId']}",
        )
        if split == "TRAIN":
            formation = case["formationInput"]
            need(
                formation["primitiveInventoryProfile"]
                == profiles["rational-assumption-primitives/v1"]["profileId"],
                f"rational profile drift in {case['caseId']}",
            )
            unique_ids(
                formation["seedExpressions"],
                "seedId",
                f"rational seed {case['caseId']}",
            )
            need(
                not formation["targetExpressionsVisible"],
                f"rational target leak in {case['caseId']}",
            )
    elif challenge == "reusable-search-macros":
        tasks = evaluation["tasks"]
        unique_ids(tasks, "taskId", f"macro task {case['caseId']}")
        need(
            all(task["source"] != task["target"] for task in tasks),
            f"trivial macro task in {case['caseId']}",
        )
        if split == "TRAIN":
            formation = case["formationInput"]
            macro_profile = profiles["macro-primitives/v1"]
            need(
                formation["primitiveInventoryProfile"] == macro_profile["profileId"],
                f"macro profile drift in {case['caseId']}",
            )
            allowed_operations = {
                operation["operationId"] for operation in macro_profile["operations"]
            }
            traces = formation["replayTraces"]
            unique_ids(traces, "traceId", f"macro trace {case['caseId']}")
            need(
                all(
                    trace["primitiveSteps"]
                    and set(trace["primitiveSteps"]).issubset(allowed_operations)
                    for trace in traces
                )
                and not formation["heldOutTargetsVisible"],
                f"macro formation boundary drift in {case['caseId']}",
            )
    else:
        raise Invalid(f"unknown challenge in {case['caseId']}: {challenge}")


def verify_documents(
    repository: Path,
    source: dict[str, Any],
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    profiles: dict[str, dict[str, Any]],
    corpus_schema: dict[str, Any],
    receipt_schema: dict[str, Any],
    profile_schema: dict[str, Any],
) -> dict[str, Any]:
    validate(corpus_schema, corpus, "case corpus")
    validate(receipt_schema, receipt, "freeze receipt")
    inventory_hashes = verify_profiles(profiles, profile_schema)

    source_hash = semantic_hash(source)
    need(
        source.get("executionStatus") == "NOT_STARTED"
        and source.get("publicationAuthorized") is False,
        "original source no longer represents a pre-execution state",
    )
    need(corpus["amendmentId"] == EXPECTED_AMENDMENT, "amendment identity drift")
    need(
        corpus["priorBenchmarkSourceContentHash"] == source_hash,
        "corpus source semantic hash drift",
    )
    source_blob = git_output(
        repository,
        "hash-object",
        f"--path={SOURCE.as_posix()}",
        str(repository / SOURCE),
    )
    need(source_blob == EXPECTED_SOURCE_BLOB, "original benchmark source Git blob drift")
    need(
        corpus["priorBenchmarkSourceGitBlobSha"] == source_blob,
        "corpus source Git blob binding drift",
    )
    git_output(repository, "cat-file", "-e", EXPECTED_FOUNDATION_COMMIT + "^{commit}")
    need(
        corpus["priorExecutionFoundationCommit"] == EXPECTED_FOUNDATION_COMMIT,
        "execution-foundation commit drift",
    )
    git_output(repository, "merge-base", "--is-ancestor", EXPECTED_FOUNDATION_COMMIT, "HEAD")

    source_cases = source.get("cases")
    corpus_cases = corpus.get("cases")
    need(isinstance(source_cases, list), "source cases must be an array")
    need(isinstance(corpus_cases, list), "corpus cases must be an array")
    need(all(isinstance(case, dict) for case in source_cases), "source case is not an object")
    need(all(isinstance(case, dict) for case in corpus_cases), "corpus case is not an object")
    unique_ids(source_cases, "caseId", "source case")
    unique_ids(corpus_cases, "caseId", "corpus case")
    need(
        [case["caseId"] for case in corpus_cases]
        == [case["caseId"] for case in source_cases],
        "corpus case set/order drift",
    )
    for case, source_case in zip(corpus_cases, source_cases, strict=True):
        verify_case_payload(case, source_case, profiles)

    split_counts = {
        split: sum(1 for case in corpus_cases if case["split"] == split)
        for split in ("TRAIN", "VALIDATION", "TEST")
    }
    challenge_counts = {
        challenge: sum(1 for case in corpus_cases if case["challengeId"] == challenge)
        for challenge in CHALLENGES
    }
    need(split_counts == corpus["splitCounts"], "corpus split count drift")
    need(challenge_counts == corpus["challengeCounts"], "corpus challenge count drift")

    need(receipt["amendmentId"] == corpus["amendmentId"], "receipt amendment drift")
    need(receipt["benchmarkId"] == corpus["benchmarkId"], "receipt benchmark drift")
    need(receipt["benchmarkSourceContentHash"] == source_hash, "receipt source hash drift")
    need(receipt["caseCorpusContentHash"] == corpus["contentHash"], "receipt corpus hash drift")
    need(
        receipt["formationInventoryContentHashes"] == inventory_hashes,
        "receipt formation-inventory hash drift",
    )
    need(
        receipt["priorExecutionFoundationCommit"] == EXPECTED_FOUNDATION_COMMIT,
        "receipt foundation commit drift",
    )
    combined = semantic_hash(
        {
            "benchmarkSourceContentHash": source_hash,
            "caseCorpusContentHash": corpus["contentHash"],
            "formationInventoryContentHashes": inventory_hashes,
            "amendmentId": corpus["amendmentId"],
            "priorExecutionFoundationCommit": EXPECTED_FOUNDATION_COMMIT,
        }
    )
    need(receipt["combinedPreregistrationHash"] == combined, "combined freeze root drift")
    need(
        receipt["executionStatusAtFreeze"] == "NOT_STARTED"
        and receipt["executedCampaignsAtFreeze"] == 0
        and receipt["executedEvaluationsAtFreeze"] == 0
        and receipt["resultInspectionStatus"] == "NO_EVALUATED_RESULTS_EXIST"
        and not receipt["publicationAuthorized"],
        "receipt does not fail closed before execution",
    )

    return {
        "benchmarkId": corpus["benchmarkId"],
        "benchmarkSourceContentHash": source_hash,
        "caseCorpusContentHash": corpus["contentHash"],
        "formationInventoryContentHashes": inventory_hashes,
        "combinedPreregistrationHash": combined,
        "caseCount": len(corpus_cases),
        "splitCounts": split_counts,
        "challengeCounts": challenge_counts,
    }


def require_negative(label: str, action: Callable[[], None]) -> None:
    try:
        action()
    except Invalid:
        return
    raise Invalid(f"negative case unexpectedly passed: {label}")


def write_report(directory: Path, summary: dict[str, Any]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    report = {
        "schema": "regelsuche.candidate-independent-corpus-freeze-verification/v1",
        "status": "VERIFIED_FROZEN_BEFORE_EVALUATED_EXECUTION",
        **summary,
        "executedCampaigns": 0,
        "executedEvaluations": 0,
        "benchmarkSuccessStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
        "negativeCases": [
            "missing-case",
            "structural-cluster-substitution",
            "held-out-formation-payload",
            "formation-inventory-drift",
            "post-execution-freeze-receipt",
        ],
    }
    report["contentHash"] = document_hash(report)
    (directory / "verification.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    (directory / "summary.md").write_text(
        "# Candidate-independent case-corpus freeze\n\n"
        "- Status: `VERIFIED_FROZEN_BEFORE_EVALUATED_EXECUTION`\n"
        "- Cases: `18` (`6 TRAIN / 6 VALIDATION / 6 TEST`)\n"
        "- Formation inventories: `3`, hash-bound before execution\n"
        "- Evaluated campaigns / evaluations: `0 / 0`\n"
        "- Benchmark success and external novelty: `NOT_EVALUATED`\n"
        "- Publication authorized: `false`\n\n"
        "The amendment repairs omitted payload and inventory layers before any "
        "evaluated execution. It is not a benchmark result.\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    repository = args.repository_root.resolve()
    try:
        source = load_unique(repository / SOURCE)
        corpus = load_unique(repository / CORPUS)
        receipt = load_unique(repository / RECEIPT)
        profiles = {
            profile_id: load_unique(repository / path)
            for profile_id, path in PROFILE_FILES.items()
        }
        corpus_schema = load_unique(repository / CORPUS_SCHEMA)
        receipt_schema = load_unique(repository / RECEIPT_SCHEMA)
        profile_schema = load_unique(repository / PROFILE_SCHEMA)
        summary = verify_documents(
            repository,
            source,
            corpus,
            receipt,
            profiles,
            corpus_schema,
            receipt_schema,
            profile_schema,
        )

        missing = copy.deepcopy(corpus)
        missing["cases"] = missing["cases"][:-1]
        missing["contentHash"] = document_hash(missing)
        require_negative(
            "missing-case",
            lambda: verify_documents(
                repository, source, missing, receipt, profiles,
                corpus_schema, receipt_schema, profile_schema
            ),
        )

        substituted = copy.deepcopy(corpus)
        substituted["cases"][0]["structuralCluster"] = "post-hoc-substitution"
        substituted["cases"][0]["contentHash"] = document_hash(substituted["cases"][0])
        substituted["contentHash"] = document_hash(substituted)
        require_negative(
            "structural-cluster-substitution",
            lambda: verify_documents(
                repository, source, substituted, receipt, profiles,
                corpus_schema, receipt_schema, profile_schema
            ),
        )

        leaked = copy.deepcopy(corpus)
        held_out = next(case for case in leaked["cases"] if case["split"] == "TEST")
        held_out["formationInput"] = {"leaked": True}
        held_out["contentHash"] = document_hash(held_out)
        leaked["contentHash"] = document_hash(leaked)
        require_negative(
            "held-out-formation-payload",
            lambda: verify_documents(
                repository, source, leaked, receipt, profiles,
                corpus_schema, receipt_schema, profile_schema
            ),
        )

        profile_drift = copy.deepcopy(profiles)
        profile_drift["macro-primitives/v1"]["operations"][0]["implementationRuleIds"] = [
            "unregistered-post-hoc-rule"
        ]
        profile_drift["macro-primitives/v1"]["contentHash"] = document_hash(
            profile_drift["macro-primitives/v1"]
        )
        require_negative(
            "formation-inventory-drift",
            lambda: verify_documents(
                repository, source, corpus, receipt, profile_drift,
                corpus_schema, receipt_schema, profile_schema
            ),
        )

        post_execution = copy.deepcopy(receipt)
        post_execution["executedCampaignsAtFreeze"] = 1
        post_execution["contentHash"] = document_hash(post_execution)
        require_negative(
            "post-execution-freeze-receipt",
            lambda: verify_documents(
                repository, source, corpus, post_execution, profiles,
                corpus_schema, receipt_schema, profile_schema
            ),
        )
        write_report(args.report_directory.resolve(), summary)
    except (Invalid, ValueError) as error:
        print(f"candidate-independent case corpus invalid: {error}", file=sys.stderr)
        return 1
    print(f"verifiedCandidateIndependentCaseCorpus={args.report_directory.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
