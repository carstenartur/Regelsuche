#!/usr/bin/env python3
"""Verify the fail-closed issue #383 execution-accounting foundation."""

from __future__ import annotations
import argparse, copy, hashlib, json, os, re, shutil, sys
from collections import Counter
from pathlib import Path
from typing import Any
from jsonschema import Draft202012Validator

SOURCE = Path("research/benchmarks/candidate-independent/benchmark-source.json")
SCHEMA_DIR = Path("docs/schemas")
SCHEMAS = {
    "batch": "regelsuche-candidate-independent-campaign-batch-v1.schema.json",
    "evaluation": "regelsuche-candidate-independent-case-evaluation-v1.schema.json",
    "report": "regelsuche-candidate-independent-benchmark-report-v1.schema.json",
    "run": "regelsuche-candidate-independent-benchmark-run-v1.schema.json",
}
CHALLENGES = (
    "finite-difference-recurrences",
    "rational-assumption-rewrites",
    "reusable-search-macros",
)
REASON = "EXECUTION_ADAPTER_NOT_IMPLEMENTED"
CLAIM = "INCOMPLETE_EXECUTION_DOES_NOT_AUTHORIZE_DISCOVERY_OR_NOVELTY_CLAIMS"
CAMPAIGN = re.compile(
    r"^(?P<challenge>[a-z0-9-]+)-campaign-(?P<index>[0-9]{2})$"
)


class Invalid(RuntimeError):
    pass


def need(ok: bool, message: str) -> None:
    if not ok:
        raise Invalid(message)


def canon(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()


def sh(value: Any) -> str:
    return "sha256:" + hashlib.sha256(canon(value)).hexdigest()


def doc_hash(value: dict[str, Any]) -> str:
    body = dict(value)
    body.pop("contentHash", None)
    return sh(body)


def byte_hash(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path) -> dict[str, Any]:
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
    need(isinstance(value, dict), f"not an object: {path}")
    return value


def tree(root: Path) -> list[str]:
    need(root.is_dir() and not root.is_symlink(), f"invalid root: {root}")
    result: list[str] = []
    for current, dirs, names in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in dirs:
            path = current_path / name
            need(
                path.is_dir() and not path.is_symlink(),
                f"invalid directory: {path}",
            )
        for name in names:
            path = current_path / name
            need(
                path.is_file() and not path.is_symlink(),
                f"invalid file: {path}",
            )
            result.append(path.relative_to(root).as_posix())
    return sorted(result)


def identical(first: Path, second: Path) -> None:
    names = tree(first)
    need(names == tree(second), "independent file trees differ")
    for name in names:
        need(
            first.joinpath(name).read_bytes()
            == second.joinpath(name).read_bytes(),
            f"independent bytes differ: {name}",
        )


def make_validators(
    repository: Path,
) -> dict[str, Draft202012Validator]:
    result = {}
    for role, name in SCHEMAS.items():
        schema = load(repository / SCHEMA_DIR / name)
        Draft202012Validator.check_schema(schema)
        result[role] = Draft202012Validator(schema)
    return result


def validate(
    check: Draft202012Validator,
    value: dict[str, Any],
    label: str,
) -> None:
    errors = sorted(
        check.iter_errors(value),
        key=lambda error: (list(error.path), error.message),
    )
    if errors:
        error = errors[0]
        place = "/".join(map(str, error.path)) or "<root>"
        raise Invalid(f"{label} schema {place}: {error.message}")
    need(
        value.get("contentHash") == doc_hash(value),
        f"{label} content hash drift",
    )


def cases(
    source: dict[str, Any],
) -> dict[str, dict[str, dict[str, Any]]]:
    raw = source.get("cases")
    need(isinstance(raw, list) and len(raw) == 18, "expected 18 source cases")
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for item in raw:
        need(isinstance(item, dict), "source case is not an object")
        challenge, case_id, split = (
            str(item.get("challengeId", "")),
            str(item.get("caseId", "")),
            str(item.get("split", "")),
        )
        need(
            challenge in CHALLENGES
            and split in {"TRAIN", "VALIDATION", "TEST"},
            f"invalid source case: {item}",
        )
        need(
            case_id not in result.setdefault(challenge, {}),
            f"duplicate case: {case_id}",
        )
        result[challenge][case_id] = item
    need(set(result) == set(CHALLENGES), "source challenge set drift")
    for challenge, items in result.items():
        need(
            Counter(item["split"] for item in items.values())
            == Counter({"TRAIN": 2, "VALIDATION": 2, "TEST": 2}),
            f"split balance drift: {challenge}",
        )
    return result


def safe(value: str) -> str:
    rendered = re.sub(r"[^A-Za-z0-9._-]", "_", value)
    need(rendered not in {"", ".", ".."}, f"unsafe identity: {value}")
    return rendered


def eval_path(campaign: str, case_id: str) -> str:
    return f"case-evaluations/{safe(campaign)}--{safe(case_id)}.json"


def bind_eval(
    value: dict[str, Any],
    campaign: dict[str, Any],
    case: dict[str, Any],
    name: str,
) -> None:
    need(
        value["campaignId"] == campaign["campaignId"]
        and value["campaignContentHash"] == campaign["contentHash"],
        f"campaign binding drift: {name}",
    )
    for field in ("caseId", "challengeId", "split"):
        need(value[field] == case[field], f"{field} drift: {name}")
    visibility = "ALLOWED" if case["split"] == "TRAIN" else "PROHIBITED"
    need(
        value["formationVisibility"] == visibility,
        f"formation visibility leak: {name}",
    )
    need(
        value["executionStatus"] == "NOT_EXECUTED"
        and value["candidateFormationStatus"] == "NOT_RUN"
        and value["heldOutEvaluationStatus"] == "NOT_RUN"
        and value["outcome"] == "INCOMPLETE",
        f"unbacked result: {name}",
    )
    need(
        value["failures"] == [REASON] and not value["publicationEligible"],
        f"claim boundary drift: {name}",
    )
    need(
        all(amount == 0 for amount in value["resourceUse"].values()),
        f"unbacked resource use: {name}",
    )


def expect_failure(label: str, action: Any) -> None:
    try:
        action()
    except Invalid:
        return
    raise Invalid(f"negative case passed: {label}")


def verify(
    repository: Path,
    root: Path,
    source: dict[str, Any],
    checks: dict[str, Draft202012Validator],
) -> dict[str, Any]:
    source_hash, indexed = sh(source), cases(source)
    batch = load(root / "campaign-batch.json")
    validate(checks["batch"], batch, "campaign batch")
    need(
        batch["benchmarkId"] == source["benchmarkId"]
        and batch["portfolioId"] == source["portfolioId"]
        and batch["portfolioContentHash"] == source["portfolioContentHash"]
        and batch["sourceContentHash"] == source_hash,
        "batch source binding drift",
    )

    ids = [campaign["campaignId"] for campaign in batch["campaigns"]]
    expected_ids = sorted(
        f"{challenge}-campaign-{index:02d}"
        for challenge in CHALLENGES
        for index in range(1, 5)
    )
    need(ids == expected_ids, "campaign matrix drift")
    expected_files = {
        "campaign-batch.json",
        "benchmark-report.json",
        "benchmark-run.json",
    }
    bindings = {}
    campaign_check = Draft202012Validator(
        checks["batch"].schema["properties"]["campaigns"]["items"]
    )
    for campaign in batch["campaigns"]:
        validate(campaign_check, campaign, f"campaign {campaign['campaignId']}")
        match = CAMPAIGN.fullmatch(campaign["campaignId"])
        need(match is not None, f"bad campaign ID: {campaign['campaignId']}")
        challenge = match.group("challenge")
        index = int(match.group("index"))
        challenge_cases = indexed[challenge]
        all_ids = sorted(challenge_cases)
        train = sorted(
            case_id
            for case_id, case in challenge_cases.items()
            if case["split"] == "TRAIN"
        )
        need(
            campaign["challengeId"] == challenge
            and campaign["caseIds"] == all_ids
            and campaign["formationCaseIds"] == train
            and campaign["heldOutCaseIds"]
            == sorted(set(all_ids) - set(train)),
            f"campaign case binding drift: {campaign['campaignId']}",
        )
        seed = sh(
            {
                "benchmarkId": source["benchmarkId"],
                "campaignId": campaign["campaignId"],
                "challengeId": challenge,
                "index": index,
            }
        )
        need(campaign["configuredSeed"] == seed, "campaign seed drift")
        need(
            campaign["resourceBudget"]
            == {
                "maxStates": source["budgets"]["maxStatesPerCampaign"],
                "maxCandidateEvaluations": source["budgets"]
                ["maxCandidateEvaluations"],
                "maxProofAttempts": source["budgets"]["maxProofAttempts"],
            },
            "campaign budget drift",
        )
        for case_id in all_ids:
            name = eval_path(campaign["campaignId"], case_id)
            expected_files.add(name)
            bindings[name] = (campaign, challenge_cases[case_id])
    actual_files = set(tree(root))
    need(
        actual_files == expected_files,
        f"bundle membership drift missing={sorted(expected_files-actual_files)} "
        f"unexpected={sorted(actual_files-expected_files)}",
    )

    evaluations, inventory = [], []
    for name in sorted(bindings):
        campaign, case = bindings[name]
        path, value = root / name, load(root / name)
        validate(checks["evaluation"], value, name)
        need(
            value["benchmarkId"] == source["benchmarkId"],
            f"evaluation benchmark drift: {name}",
        )
        bind_eval(value, campaign, case, name)
        evaluations.append(value)
        inventory.append(
            {
                "path": name,
                "contentHash": value["contentHash"],
                "fileSha256": byte_hash(path),
            }
        )

    report = load(root / "benchmark-report.json")
    validate(checks["report"], report, "benchmark report")
    need(
        report["sourceContentHash"] == source_hash
        and report["campaignBatchContentHash"] == batch["contentHash"]
        and report["caseEvaluationContentHashes"]
        == sorted(value["contentHash"] for value in evaluations),
        "report root binding drift",
    )
    need(
        report["challengeCoverage"]
        == [
            {
                "challengeId": challenge,
                "configuredEvaluations": 24,
                "executedEvaluations": 0,
                "incompleteEvaluations": 24,
            }
            for challenge in CHALLENGES
        ],
        "challenge coverage drift",
    )
    need(
        set(report["metrics"]) == set(source["metrics"])
        and all(
            value == "NOT_MEASURED"
            for value in report["metrics"].values()
        ),
        "metric status drift",
    )

    run = load(root / "benchmark-run.json")
    validate(checks["run"], run, "benchmark run")
    need(
        run["sourceContentHash"] == source_hash
        and run["sourceFileSha256"] == byte_hash(repository / SOURCE)
        and not run["publicationAuthorized"]
        and run["externalNoveltyStatus"] == "NOT_EVALUATED",
        "run source/claim binding drift",
    )
    for role, name, value in (
        ("campaignBatch", "campaign-batch.json", batch),
        ("benchmarkReport", "benchmark-report.json", report),
    ):
        ref = run["artifacts"][role]
        need(
            ref
            == {
                "path": name,
                "contentHash": value["contentHash"],
                "fileSha256": byte_hash(root / name),
            },
            f"{role} reference drift",
        )
    inventory.sort(key=lambda item: item["path"])
    eval_set = run["artifacts"]["caseEvaluationSet"]
    need(
        eval_set["files"] == inventory
        and eval_set["fileCount"] == 72
        and eval_set["contentHash"] == sh(inventory),
        "evaluation inventory drift",
    )

    missing = set(actual_files)
    missing.remove(sorted(bindings)[0])
    expect_failure(
        "missing evaluation",
        lambda: need(missing == expected_files, "missing evaluation"),
    )
    test_name = next(
        name
        for name, (_, case) in sorted(bindings.items())
        if case["split"] == "TEST"
    )
    leaked = copy.deepcopy(load(root / test_name))
    leaked["formationVisibility"] = "ALLOWED"
    expect_failure(
        "TEST visibility",
        lambda: bind_eval(leaked, *bindings[test_name], test_name),
    )
    first_name = sorted(bindings)[0]
    accepted = copy.deepcopy(load(root / first_name))
    accepted["outcome"] = "ACCEPTED"
    expect_failure(
        "unbacked accepted outcome",
        lambda: bind_eval(
            accepted,
            *bindings[first_name],
            first_name,
        ),
    )
    return {
        "benchmarkId": source["benchmarkId"],
        "sourceContentHash": source_hash,
        "campaignBatchContentHash": batch["contentHash"],
        "benchmarkReportContentHash": report["contentHash"],
        "benchmarkRunContentHash": run["contentHash"],
        "configuredCampaigns": 12,
        "configuredEvaluations": 72,
    }


def write_report(directory: Path, summary: dict[str, Any]) -> None:
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    report = {
        "schema": (
            "regelsuche.candidate-independent-execution-"
            "foundation-verification/v1"
        ),
        "status": "VERIFIED_INCOMPLETE_EXECUTION_FOUNDATION",
        **summary,
        "executedCampaigns": 0,
        "executedEvaluations": 0,
        "benchmarkSuccessStatus": "NOT_EVALUATED",
        "externalNoveltyStatus": "NOT_EVALUATED",
        "publicationAuthorized": False,
        "claimPolicy": CLAIM,
        "negativeCases": [
            "missing-case-evaluation",
            "test-formation-visibility-leak",
            "unbacked-accepted-outcome",
        ],
    }
    report["contentHash"] = doc_hash(report)
    (directory / "verification.json").write_text(
        json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )
    (directory / "summary.md").write_text(
        "# Candidate-independent benchmark execution foundation\n\n"
        "- Status: `VERIFIED_INCOMPLETE_EXECUTION_FOUNDATION`\n"
        "- Configured campaigns / case evaluations: `12 / 72`\n"
        "- Executed campaigns / case evaluations: `0 / 0`\n"
        "- Benchmark success and external novelty: `NOT_EVALUATED`\n"
        "- Publication authorized: `false`\n\n"
        "This is complete fail-closed accounting, not benchmark-result "
        "evidence.\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, required=True)
    parser.add_argument("--first", type=Path, required=True)
    parser.add_argument("--second", type=Path, required=True)
    parser.add_argument("--report-directory", type=Path, required=True)
    args = parser.parse_args()
    repository = args.repository_root.resolve()
    try:
        source = load(repository / SOURCE)
        checks = make_validators(repository)
        first, second = args.first.resolve(), args.second.resolve()
        identical(first, second)
        summary = verify(repository, first, source, checks)
        need(
            summary == verify(repository, second, source, checks),
            "independent verification summaries differ",
        )
        write_report(args.report_directory.resolve(), summary)
    except (Invalid, ValueError) as error:
        print(
            f"candidate-independent execution foundation invalid: {error}",
            file=sys.stderr,
        )
        return 1
    print(
        "verifiedCandidateIndependentExecutionFoundation="
        f"{args.report_directory.resolve()}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
