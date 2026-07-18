#!/usr/bin/env python3
"""Prevent new verification semantics from being implemented only in Actions YAML."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

WORKFLOW_DIR = Path(".github/workflows")
POLICY_FILE = Path("config/workflow-semantics-policy.json")
EXPECTED_SCHEMA = "regelsuche.workflow-semantics-policy/v1"


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

REPOSITORY_COMMAND = re.compile(
    r"(?:\./gradlew\b|python3\s+scripts/|bash\s+(?:scripts/|reproduction/)|\./(?:scripts/|reproduction/))"
)


def load_policy() -> tuple[list[str], list[str]]:
    try:
        document = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {POLICY_FILE}: {error}") from error

    if set(document) != {
        "schema",
        "repositoryOwnedWorkflows",
        "legacyWorkflowOwnedSemantics",
    }:
        raise ValueError("workflow policy has unknown or missing top-level fields")
    if document["schema"] != EXPECTED_SCHEMA:
        raise ValueError(f"unsupported workflow policy schema: {document['schema']!r}")

    repository_owned = document["repositoryOwnedWorkflows"]
    legacy = document["legacyWorkflowOwnedSemantics"]
    if not isinstance(repository_owned, list) or not isinstance(legacy, list):
        raise ValueError("workflow policy classifications must be arrays")
    if not all(isinstance(item, str) and item for item in repository_owned + legacy):
        raise ValueError("workflow policy entries must be non-empty strings")
    if repository_owned != sorted(set(repository_owned)):
        raise ValueError("repositoryOwnedWorkflows must be sorted and unique")
    if legacy != sorted(set(legacy)):
        raise ValueError("legacyWorkflowOwnedSemantics must be sorted and unique")
    overlap = set(repository_owned) & set(legacy)
    if overlap:
        raise ValueError(f"workflows have two ownership classifications: {sorted(overlap)}")
    return repository_owned, legacy


def workflow_names() -> list[str]:
    if not WORKFLOW_DIR.is_dir():
        raise ValueError(f"workflow directory does not exist: {WORKFLOW_DIR}")
    return sorted(
        path.name
        for path in WORKFLOW_DIR.iterdir()
        if path.is_file() and path.suffix in {".yml", ".yaml"}
    )


def scan_repository_owned(workflow: str) -> list[Violation]:
    path = WORKFLOW_DIR / workflow
    text = path.read_text(encoding="utf-8")
    violations: list[Violation] = []

    for line_number, line in enumerate(text.splitlines(), start=1):
        for category, pattern in TEXT_PATTERNS:
            if pattern.search(line):
                violations.append(Violation(workflow, line_number, category, line.strip()))
        stripped = line.strip()
        if stripped.startswith(ASSERTION_PREFIXES):
            violations.append(
                Violation(workflow, line_number, "workflow-owned-assertion", stripped)
            )

    if not REPOSITORY_COMMAND.search(text):
        violations.append(
            Violation(
                workflow,
                1,
                "missing-repository-command",
                "no Gradle task or checked-in script invocation found",
            )
        )
    return violations


def self_test() -> None:
    samples = {
        "inline-interpreter-heredoc": "python3 - <<'PY'",
        "manual-docker-lifecycle": "docker run --rm image",
        "fixed-host-port": "docker run -p 18080:8080 image",
        "github-service-fixture": "    services:",
    }
    for expected, sample in samples.items():
        matched = {name for name, pattern in TEXT_PATTERNS if pattern.search(sample)}
        if expected not in matched:
            raise AssertionError(f"guard self-test did not detect {expected}")
    if not any("test value".startswith(prefix) for prefix in ASSERTION_PREFIXES):
        raise AssertionError("guard self-test did not detect shell assertion")
    if not REPOSITORY_COMMAND.search("run: ./gradlew verifySomething"):
        raise AssertionError("guard self-test did not detect repository command")


def main() -> int:
    try:
        self_test()
        repository_owned, legacy = load_policy()
        actual = workflow_names()
    except (AssertionError, ValueError) as error:
        print(f"Workflow semantics guard configuration failed: {error}", file=sys.stderr)
        return 2

    classified = sorted(repository_owned + legacy)
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
        for workflow in repository_owned
        for violation in scan_repository_owned(workflow)
    ]
    if violations:
        print("Repository-owned workflows contain forbidden semantics:", file=sys.stderr)
        for violation in violations:
            print(
                f"  {violation.workflow}:{violation.line}: "
                f"{violation.category}: {violation.excerpt}",
                file=sys.stderr,
            )
        return 1

    print(
        "OK: "
        f"{len(repository_owned)} repository-owned workflow(s) are thin; "
        f"{len(legacy)} legacy workflow(s) remain explicitly tracked"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
