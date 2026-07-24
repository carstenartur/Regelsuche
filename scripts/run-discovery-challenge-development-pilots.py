#!/usr/bin/env python3
"""Execute and retain the development-only pilots selected by issue #390.

The script is intentionally CI-agnostic. It invokes ordinary Gradle/JUnit tests,
checks their XML reports, and writes a deterministic receipt. It does not run the
candidate-independent benchmark and does not authorize any novelty claim.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

SCHEMA = "regelsuche.discovery-challenge-development-pilots/v1"
CLAIM_POLICY = "DEVELOPMENT_PILOTS_DO_NOT_IMPLY_BENCHMARK_SUCCESS_OR_EXTERNAL_NOVELTY"
PORTFOLIO_PATH = Path("research/challenges/generated/challenge-portfolio.json")


@dataclass(frozen=True)
class Pilot:
    challenge_id: str
    purpose: str
    task: str
    patterns: tuple[str, ...]
    report: Path
    expected_methods: tuple[str, ...]
    evidence_strength: str
    limitation: str

    def command(self) -> list[str]:
        command = ["./gradlew", "--no-daemon", self.task, "--rerun-tasks"]
        for pattern in self.patterns:
            command.extend(["--tests", pattern])
        return command


PILOTS = (
    Pilot(
        challenge_id="finite-difference-recurrences",
        purpose=(
            "Exercise confirmed holdout continuation, retained refutation, and "
            "counterexample-budget exhaustion in the production sequence domain."
        ),
        task=":regelsuche-discovery:test",
        patterns=(
            "de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomainTest",
        ),
        report=Path(
            "regelsuche-discovery/build/test-results/test/"
            "TEST-de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomainTest.xml"
        ),
        expected_methods=(
            "discoversQuadraticFiniteDifferenceAndValidatesIndependentHoldout()",
            "refutesWrongHoldoutWithoutRetainingCertificate()",
            "counterexampleBudgetExhaustionCannotBecomeConfirmation()",
        ),
        evidence_strength="FINITE_DATA_VALIDATION_DEVELOPMENT_PILOT",
        limitation=(
            "A finite holdout certificate is not a proof of a unique infinite "
            "sequence and is not an external novelty decision."
        ),
    ),
    Pilot(
        challenge_id="rational-assumption-rewrites",
        purpose=(
            "Exercise fail-closed rejection without a nonzero denominator "
            "assumption and lossless translation when that assumption is explicit."
        ),
        task=":regelsuche-solver-portfolio:test",
        patterns=(
            "de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest."
            "divisionWithoutNonZeroAssumptionIsRejectedBeforeInvocation",
            "de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest."
            "explicitNonZeroAssumptionMakesDivisionTranslationLossless",
        ),
        report=Path(
            "regelsuche-solver-portfolio/build/test-results/test/"
            "TEST-de.regelsuche.solver.portfolio.Z3SmtSolverBackendTest.xml"
        ),
        expected_methods=(
            "divisionWithoutNonZeroAssumptionIsRejectedBeforeInvocation()",
            "explicitNonZeroAssumptionMakesDivisionTranslationLossless()",
        ),
        evidence_strength="ASSUMPTION_TRANSLATION_DEVELOPMENT_PILOT",
        limitation=(
            "This pilot validates the supported translation boundary; it does not "
            "execute a final benchmark campaign or establish a new identity."
        ),
    ),
    Pilot(
        challenge_id="reusable-search-macros",
        purpose=(
            "Exercise real replay-based macro learning, independent equivalence "
            "validation, guarded reuse, and assumption-preserving rationalization."
        ),
        task=":app:test",
        patterns=(
            "de.regelsuche.learning.MacroLearningPipelineReplayIntegrationTest."
            "symbolicSophieGermainMacroLearnsFromActualReplayAndReuses",
            "de.regelsuche.learning.MacroLearningPipelineReplayIntegrationTest."
            "rationalizationMacroLearnsFromRealOperatorCorpusWorkflowAndReuses",
        ),
        report=Path(
            "app/build/test-results/test/"
            "TEST-de.regelsuche.learning.MacroLearningPipelineReplayIntegrationTest.xml"
        ),
        expected_methods=(
            "symbolicSophieGermainMacroLearnsFromActualReplayAndReuses()",
            "rationalizationMacroLearnsFromRealOperatorCorpusWorkflowAndReuses()",
        ),
        evidence_strength="REPLAY_AND_REUSE_DEVELOPMENT_PILOT",
        limitation=(
            "Successful reuse is project-local utility evidence, not mathematical "
            "importance, universal superiority, or external novelty."
        ),
    ),
)


def load_portfolio(root: Path) -> dict:
    path = root / PORTFOLIO_PATH
    try:
        portfolio = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"cannot read frozen portfolio: {exc}") from exc
    selected = set(portfolio.get("selectedChallengeIds", []))
    expected = {pilot.challenge_id for pilot in PILOTS}
    if selected != expected:
        raise RuntimeError(
            f"pilot set does not match frozen portfolio: selected={sorted(selected)} "
            f"pilots={sorted(expected)}"
        )
    content_hash = portfolio.get("contentHash", "")
    if not content_hash.startswith("sha256:"):
        raise RuntimeError("frozen portfolio has no content-addressed identity")
    return portfolio


def run_pilot(root: Path, pilot: Pilot, reuse_test_results: bool) -> dict:
    command = pilot.command()
    if not reuse_test_results:
        completed = subprocess.run(command, cwd=root, check=False)
        if completed.returncode != 0:
            raise RuntimeError(
                f"pilot {pilot.challenge_id} failed with exit code "
                f"{completed.returncode}: {' '.join(command)}"
            )

    report_path = root / pilot.report
    if not report_path.is_file():
        mode = "reused" if reuse_test_results else "executed"
        raise RuntimeError(
            f"pilot {pilot.challenge_id} produced no JUnit XML after {mode} test run: "
            f"{pilot.report}"
        )
    suite = ET.parse(report_path).getroot()
    failures = int(suite.get("failures", "0"))
    errors = int(suite.get("errors", "0"))
    skipped = int(suite.get("skipped", "0"))
    methods = sorted(testcase.get("name", "") for testcase in suite.findall("testcase"))
    missing = sorted(set(pilot.expected_methods) - set(methods))
    if failures or errors or skipped or missing:
        raise RuntimeError(
            f"pilot {pilot.challenge_id} incomplete: failures={failures}, "
            f"errors={errors}, skipped={skipped}, missing={missing}, methods={methods}"
        )

    return {
        "challengeId": pilot.challenge_id,
        "command": command,
        "evidenceStrength": pilot.evidence_strength,
        "executedTestMethods": list(pilot.expected_methods),
        "limitation": pilot.limitation,
        "outcome": "PASSED",
        "purpose": pilot.purpose,
        "reportPath": pilot.report.as_posix(),
    }


def canonical_hash(document: dict) -> str:
    payload = json.dumps(
        document, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(payload).hexdigest()


def write_report(root: Path, output: Path, portfolio: dict, pilots: list[dict]) -> None:
    report = {
        "benchmarkExecutionStatus": "NOT_STARTED",
        "claimPolicy": CLAIM_POLICY,
        "externalNoveltyStatus": "NOT_EVALUATED",
        "pilotEvidenceStatus": "DEVELOPMENT_ONLY_PASSED",
        "pilots": pilots,
        "portfolioContentHash": portfolio["contentHash"],
        "portfolioId": portfolio["portfolioId"],
        "schema": SCHEMA,
    }
    report["contentHash"] = canonical_hash(report)
    path = root / output
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"developmentPilotReport={path}")
    print(f"contentHash={report['contentHash']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/reports/discovery-challenge-pilots/report.json"),
    )
    parser.add_argument(
        "--reuse-test-results",
        action="store_true",
        help=(
            "Validate JUnit XML from an already scheduled repository test graph "
            "instead of starting nested Gradle processes."
        ),
    )
    args = parser.parse_args()
    root = args.root.resolve()

    try:
        portfolio = load_portfolio(root)
        results = [
            run_pilot(root, pilot, args.reuse_test_results) for pilot in PILOTS
        ]
        write_report(root, args.output, portfolio, results)
    except (RuntimeError, ET.ParseError) as exc:
        print(f"development challenge pilots failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
