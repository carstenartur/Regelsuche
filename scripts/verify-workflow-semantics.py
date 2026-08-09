#!/usr/bin/env python3
"""Keep Actions thin, few in number and reproducible from a checkout."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

WORKFLOW_DIR = Path(".github/workflows")
POLICY_FILE = Path("config/workflow-semantics-policy.json")
GOVERNANCE_POLICY_FILE = Path("config/github-merge-governance-policy.json")
EXPECTED_SCHEMA = "regelsuche.workflow-semantics-policy/v2"
EXPECTED_GOVERNANCE_SCHEMA = "regelsuche.github-merge-governance-policy/v1"


@dataclass(frozen=True)
class Policy:
    maximum_workflow_count: int
    verification_workflows: tuple[str, ...]
    platform_workflows: tuple[str, ...]


@dataclass(frozen=True)
class MergeGovernancePolicy:
    default_branch: str
    required_status_check_context: str
    required_status_check_integration_id: int
    strict_up_to_date: bool
    create_event_check_context: str
    routine_bypass_actors: tuple[object, ...]
    current_user_can_bypass: str
    required_approving_review_count: int
    required_review_thread_resolution: bool


@dataclass(frozen=True)
class Violation:
    workflow: str
    line: int
    category: str
    excerpt: str


TEXT_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "inline-interpreter-heredoc",
        re.compile(r"\b(?:python3?|node|ruby|perl)\s+-\s*<<"),
    ),
    (
        "manual-docker-lifecycle",
        re.compile(r"\bdocker\s+(?:build|run|compose)\b"),
    ),
    (
        "fixed-host-port",
        re.compile(r"(?:^|\s)-p\s+\d{2,5}:\d{2,5}(?:\s|$)"),
    ),
    (
        "github-service-fixture",
        re.compile(r"^\s+services:\s*$"),
    ),
    (
        "direct-repository-script",
        re.compile(r"\b(?:python3?|bash)\s+(?:scripts/|reproduction/)"),
    ),
)

ASSERTION_PREFIXES = (
    "grep ",
    "test ",
    "cmp ",
    "diff ",
    "git diff ",
    "jq -e ",
    "[ ",
    "[[ ",
)

GRADLE_INVOCATION = re.compile(r"\./gradlew\b")
CI_ENTRYPOINT = re.compile(r"\bciCheck\b")


def load_policy() -> Policy:
    try:
        document = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {POLICY_FILE}: {error}") from error

    if set(document) != {
        "schema",
        "maximumWorkflowCount",
        "verificationWorkflows",
        "platformWorkflows",
    }:
        raise ValueError("workflow policy has unknown or missing top-level fields")
    if document["schema"] != EXPECTED_SCHEMA:
        raise ValueError(f"unsupported workflow policy schema: {document['schema']!r}")

    maximum = document["maximumWorkflowCount"]
    verification = document["verificationWorkflows"]
    platform = document["platformWorkflows"]
    if not isinstance(maximum, int) or isinstance(maximum, bool) or maximum < 1:
        raise ValueError("maximumWorkflowCount must be a positive integer")
    if not isinstance(verification, list) or not isinstance(platform, list):
        raise ValueError("workflow classifications must be arrays")
    if not all(isinstance(item, str) and item for item in verification + platform):
        raise ValueError("workflow policy entries must be non-empty strings")
    if verification != sorted(set(verification)):
        raise ValueError("verificationWorkflows must be sorted and unique")
    if platform != sorted(set(platform)):
        raise ValueError("platformWorkflows must be sorted and unique")
    overlap = set(verification) & set(platform)
    if overlap:
        raise ValueError(
            f"workflows have two ownership classifications: {sorted(overlap)}"
        )
    if len(verification) + len(platform) > maximum:
        raise ValueError("classified workflows exceed maximumWorkflowCount")
    if not verification:
        raise ValueError("at least one verification workflow is required")
    return Policy(maximum, tuple(verification), tuple(platform))


def load_governance_policy() -> MergeGovernancePolicy:
    try:
        document = json.loads(GOVERNANCE_POLICY_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {GOVERNANCE_POLICY_FILE}: {error}") from error

    expected_fields = {
        "schema",
        "defaultBranch",
        "requiredStatusCheck",
        "createEventCheckContext",
        "routineBypassActors",
        "currentUserCanBypass",
        "requiredApprovingReviewCount",
        "requiredReviewThreadResolution",
    }
    if set(document) != expected_fields:
        raise ValueError("merge-governance policy has unknown or missing top-level fields")
    if document["schema"] != EXPECTED_GOVERNANCE_SCHEMA:
        raise ValueError(
            f"unsupported merge-governance policy schema: {document['schema']!r}"
        )

    required = document["requiredStatusCheck"]
    if not isinstance(required, dict) or set(required) != {
        "context",
        "integrationId",
        "strictUpToDate",
    }:
        raise ValueError("requiredStatusCheck has unknown or missing fields")

    default_branch = document["defaultBranch"]
    required_context = required["context"]
    integration_id = required["integrationId"]
    strict_up_to_date = required["strictUpToDate"]
    create_context = document["createEventCheckContext"]
    bypass_actors = document["routineBypassActors"]
    current_user_can_bypass = document["currentUserCanBypass"]
    review_count = document["requiredApprovingReviewCount"]
    thread_resolution = document["requiredReviewThreadResolution"]

    if not isinstance(default_branch, str) or not default_branch:
        raise ValueError("defaultBranch must be a non-empty string")
    if not isinstance(required_context, str) or not required_context:
        raise ValueError("required status context must be a non-empty string")
    if not isinstance(create_context, str) or not create_context:
        raise ValueError("create-event check context must be a non-empty string")
    if required_context == create_context:
        raise ValueError(
            "create-event check context must differ from the required merge check"
        )
    if (
        not isinstance(integration_id, int)
        or isinstance(integration_id, bool)
        or integration_id <= 0
    ):
        raise ValueError("required status integrationId must be a positive integer")
    if strict_up_to_date is not True:
        raise ValueError("strictUpToDate must remain true in the intended merge policy")
    if not isinstance(bypass_actors, list) or bypass_actors:
        raise ValueError("routineBypassActors must be an empty array")
    if current_user_can_bypass != "never":
        raise ValueError("currentUserCanBypass must be 'never'")
    if (
        not isinstance(review_count, int)
        or isinstance(review_count, bool)
        or review_count < 0
    ):
        raise ValueError("requiredApprovingReviewCount must be a non-negative integer")
    if thread_resolution is not True:
        raise ValueError("requiredReviewThreadResolution must remain true")

    return MergeGovernancePolicy(
        default_branch,
        required_context,
        integration_id,
        strict_up_to_date,
        create_context,
        tuple(bypass_actors),
        current_user_can_bypass,
        review_count,
        thread_resolution,
    )


def workflow_names() -> list[str]:
    if not WORKFLOW_DIR.is_dir():
        raise ValueError(f"workflow directory does not exist: {WORKFLOW_DIR}")
    return sorted(
        path.name
        for path in WORKFLOW_DIR.iterdir()
        if path.is_file() and path.suffix in {".yml", ".yaml"}
    )


def scan_verification_workflow(
    workflow: str, governance: MergeGovernancePolicy
) -> list[Violation]:
    path = WORKFLOW_DIR / workflow
    text = path.read_text(encoding="utf-8")
    violations: list[Violation] = []

    for line_number, line in enumerate(text.splitlines(), start=1):
        for category, pattern in TEXT_PATTERNS:
            if pattern.search(line):
                violations.append(
                    Violation(workflow, line_number, category, line.strip())
                )
        stripped = line.strip()
        if stripped.startswith(ASSERTION_PREFIXES):
            violations.append(
                Violation(workflow, line_number, "workflow-owned-assertion", stripped)
            )

    invocations = list(GRADLE_INVOCATION.finditer(text))
    if not invocations:
        violations.append(
            Violation(
                workflow,
                1,
                "missing-gradle-entrypoint",
                "no checkout-local Gradle invocation found",
            )
        )
    if len(invocations) > 2:
        violations.append(
            Violation(
                workflow,
                1,
                "too-many-gradle-entrypoints",
                f"found {len(invocations)} Gradle invocations; expected at most two",
            )
        )
    if not CI_ENTRYPOINT.search(text):
        violations.append(
            Violation(
                workflow,
                1,
                "missing-ci-entrypoint",
                "verification workflow does not invoke ciCheck",
            )
        )

    compact = " ".join(text.split())
    expected_job_name = (
        "${{ github.event_name == 'create' && '"
        + governance.create_event_check_context
        + "' || '"
        + governance.required_status_check_context
        + "' }}"
    )
    if expected_job_name not in compact:
        violations.append(
            Violation(
                workflow,
                1,
                "required-check-create-collision",
                "verification job must give create events a distinct check name: "
                + expected_job_name,
            )
        )
    return violations


def self_test() -> None:
    samples = {
        "inline-interpreter-heredoc": "python3 - <<'PY'",
        "manual-docker-lifecycle": "docker run --rm image",
        "fixed-host-port": "docker run -p 18080:8080 image",
        "github-service-fixture": "    services:",
        "direct-repository-script": "bash scripts/check.sh",
    }
    for expected, sample in samples.items():
        matched = {name for name, pattern in TEXT_PATTERNS if pattern.search(sample)}
        if expected not in matched:
            raise AssertionError(f"guard self-test did not detect {expected}")
    if not any("test value".startswith(prefix) for prefix in ASSERTION_PREFIXES):
        raise AssertionError("guard self-test did not detect shell assertion")
    if not CI_ENTRYPOINT.search("run: ./gradlew ciCheck"):
        raise AssertionError("guard self-test did not detect ciCheck")


def main() -> int:
    try:
        self_test()
        policy = load_policy()
        governance = load_governance_policy()
        actual = workflow_names()
    except (AssertionError, ValueError) as error:
        print(f"Workflow semantics guard configuration failed: {error}", file=sys.stderr)
        return 2

    classified = sorted(
        policy.verification_workflows + policy.platform_workflows
    )
    if len(actual) > policy.maximum_workflow_count:
        print(
            f"Too many workflows: {len(actual)} present, "
            f"maximum is {policy.maximum_workflow_count}",
            file=sys.stderr,
        )
        return 1
    if classified != actual:
        missing = sorted(set(actual) - set(classified))
        stale = sorted(set(classified) - set(actual))
        if missing:
            print("Unclassified workflows:", file=sys.stderr)
            for item in missing:
                print(f"  - {item}", file=sys.stderr)
        if stale:
            print("Policy entries without a workflow:", file=sys.stderr)
            for item in stale:
                print(f"  - {item}", file=sys.stderr)
        return 1

    violations = [
        violation
        for workflow in policy.verification_workflows
        for violation in scan_verification_workflow(workflow, governance)
    ]
    if violations:
        print("Verification workflows contain checkout-owned semantics:", file=sys.stderr)
        for violation in violations:
            print(
                f"  {violation.workflow}:{violation.line}: "
                f"{violation.category}: {violation.excerpt}",
                file=sys.stderr,
            )
        return 1

    print(
        "OK: "
        f"{len(actual)}/{policy.maximum_workflow_count} workflow slots used; "
        f"{len(policy.verification_workflows)} verification workflow(s) invoke ciCheck; "
        f"required merge check is {governance.required_status_check_context!r}; "
        f"create events use {governance.create_event_check_context!r}; "
        f"{len(policy.platform_workflows)} platform workflow(s) remain"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
