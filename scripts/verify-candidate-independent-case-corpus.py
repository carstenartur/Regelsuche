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

SOURCE = Path("research/benchmarks/candidate-independent/benchmark-source.json")
CORPUS = Path("research/benchmarks/candidate-independent/case-corpus.json")
RECEIPT = Path("research/benchmarks/candidate-independent/corpus-freeze-receipt.json")
CORPUS_SCHEMA = Path(
    "docs/schemas/regelsuche-candidate-independent-case-corpus-v1.schema.json"
)
RECEIPT_SCHEMA = Path(
    "docs/schemas/regelsuche-candidate-independent-corpus-freeze-receipt-v1.schema.json"
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
    identities = [str(value[field]) for value in values]
    need(
        identities == sorted(identities),
        f"{label} identities are not sorted",
    )
    need(len(identities) == len(set(identities)), f"duplicate {label} identity")


def verify_case_payload(case: dict[str, Any], source_case: dict[str, Any]) -> None:
    for field in ("caseId", "challengeId", "split", "structuralCluster"):
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
                and formation["candidateFormsAllowed"]
                == evaluation["candidateFormsAllowed"]
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
            unique_ids(
                case["formationInput"]["seedExpressions"],
                "seedId",
                f"rational seed {case['caseId']}",
            )
            need(
                not case["formationInput"]["targetExpressionsVisible"],
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
            traces = case["formationInput"]["replayTraces"]
            unique_ids(traces, "traceId", f"macro trace {case['caseId']}")
            need(
                all(trace["primitiveSteps"] for trace in traces)
                and not case["formationInput"]["heldOutTargetsVisible"],
                f"macro formation boundary drift in {case['caseId']}",
            )
    else:
        raise Invalid(f"unknown challenge in {case['caseId']}: {challenge}")


def verify_documents(
    repository: Path,
    source: dict[str, Any],
    corpus: dict[str, Any],
    receipt: dict[str, Any],
    corpus_schema: dict[str, Any],
    receipt_schema: dict[str, Any],
) -> dict[str, Any]:
    validate(corpus_schema, corpus, "case corpus")
    validate(receipt_schema, receipt, "freeze receipt")

    source_hash = semantic_hash(source)
    need(
        source["executionStatus"] == "NOT_STARTED"
        and not source["publicationAuthorized"],
        "original source no longer represents a pre-execution state",
    )
    need(corpus["amendmentId"] == EXPECTED_AMENDMENT, "amendment identity drift")
    need(
        corpus["priorBenchmarkSourceContentHash"] == source_hash,
        "corpus source semantic hash drift",
    )
    source_blob = git_output(repository, "hash-object", str(repository / SOURCE))
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

    source_cases = source["cases"]
    corpus_cases = corpus["cases"]
    unique_ids(source_cases, "caseId", "source case")
    unique_ids(corpus_cases, "caseId", "corpus case")
    need(
        [case["caseId"] for case in corpus_cases]
        == [case["caseId"] for case in source_cases],
        "corpus case set/order drift",
    )
    for case, source_case in zip(corpus_cases, source_cases, strict=True):
        verify_case_payload(case, source_case)

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
        receipt["priorExecutionFoundationCommit"] == EXPECTED_FOUNDATION_COMMIT,
        "receipt foundation commit drift",
    )
    combined = semantic_hash(
        {
            "benchmarkSourceContentHash": source_hash,
            "caseCorpusContentHash": corpus["contentHash"],
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
        "- Evaluated campaigns / evaluations: `0 / 0`\n"
        "- Benchmark success and external novelty: `NOT_EVALUATED`\n"
        "- Publication authorized: `false`\n\n"
        "The amendment repairs an omitted concrete-payload layer before any "
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
        corpus_schema = load_unique(repository / CORPUS_SCHEMA)
        receipt_schema = load_unique(repository / RECEIPT_SCHEMA)
        summary = verify_documents(
            repository,
            source,
            corpus,
            receipt,
            corpus_schema,
            receipt_schema,
        )

        missing = copy.deepcopy(corpus)
        missing["cases"] = missing["cases"][:-1]
        missing["contentHash"] = document_hash(missing)
        require_negative(
            "missing-case",
            lambda: verify_documents(
                repository, source, missing, receipt, corpus_schema, receipt_schema
            ),
        )

        substituted = copy.deepcopy(corpus)
        substituted["cases"][0]["structuralCluster"] = "post-hoc-substitution"
        substituted["cases"][0]["contentHash"] = document_hash(substituted["cases"][0])
        substituted["contentHash"] = document_hash(substituted)
        require_negative(
            "structural-cluster-substitution",
            lambda: verify_documents(
                repository, source, substituted, receipt, corpus_schema, receipt_schema
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
                repository, source, leaked, receipt, corpus_schema, receipt_schema
            ),
        )

        post_execution = copy.deepcopy(receipt)
        post_execution["executedCampaignsAtFreeze"] = 1
        post_execution["contentHash"] = document_hash(post_execution)
        require_negative(
            "post-execution-freeze-receipt",
            lambda: verify_documents(
                repository,
                source,
                corpus,
                post_execution,
                corpus_schema,
                receipt_schema,
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
