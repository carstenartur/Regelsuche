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
EXPECTED_SCHEMA = "regelsuche.workflow-semantics-policy/v2"


@dataclass(frozen=True)
class Policy:
    maximum_workflow_count: int
    verification_workflows: tuple[str, ...]
    platform_workflows: tuple[str, ...]


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


def workflow_names() -> list[str]:
    if not WORKFLOW_DIR.is_dir():
        raise ValueError(f"workflow directory does not exist: {WORKFLOW_DIR}")
    return sorted(
        path.name
        for path in WORKFLOW_DIR.iterdir()
        if path.is_file() and path.suffix in {".yml", ".yaml"}
    )


def scan_verification_workflow(workflow: str) -> list[Violation]:
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
        for violation in scan_verification_workflow(workflow)
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
        f"{len(policy.platform_workflows)} platform workflow(s) remain"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
