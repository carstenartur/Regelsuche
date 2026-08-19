#!/usr/bin/env python3
"""Adapt the 24-row candidate freeze to the accepted hardened plan."""

from __future__ import annotations

from pathlib import Path

ROOT = Path.cwd()
SOURCE = ROOT / (
    "regelsuche-discovery/src/main/java/de/regelsuche/discovery/representation/"
    "TargetFreeRepresentationCandidateFreeze.java"
)
TEST = ROOT / (
    "regelsuche-discovery/src/test/java/de/regelsuche/discovery/representation/"
    "TargetFreeRepresentationCandidateFreezeTest.java"
)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one occurrence, found {count}")
    return text.replace(old, new)


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    source = replace_once(
        source,
        "import de.regelsuche.knowledge.RuleProfile;\n",
        "",
        "obsolete RuleProfile import",
    )
    source = replace_once(
        source,
        '''        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                KnowledgePackSelection.profile(RuleProfile.ALL)
            );''',
        '''        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile());
        for (String packId : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(packId);
        }
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                selection
            );''',
        "frozen case inventory selection",
    )
    source = replace_once(
        source,
        '''        if (!TargetFreeRepresentationSearch.class.getName().equals(
                policy.adapter())) {
            throw new IllegalArgumentException(
                "native target-free adapter differs from plan");
        }''',
        '''        if (!TargetFreeRepresentationSearch.class.getName().equals(
                policy.adapter())
                || policy.adapterConstructor()
                    != TargetFreeRepresentationEvaluationPlan
                        .AdapterConstructor.NO_ARGUMENT
                || policy.deterministicSeed() != 0L) {
            throw new IllegalArgumentException(
                "native target-free adapter constructor or seed differs "
                    + "from plan");
        }''',
        "native adapter constructor and seed contract",
    )
    source = replace_once(
        source,
        '''        int beamWidth = Math.max(1, Math.min(
            configured.maxCandidatesPerState(),
            configured.maxRetainedStates()
        ));''',
        '''        int beamWidth = configured.beamWidth();''',
        "beam-width projection",
    )
    source = replace_once(
        source,
        '''                1,
                configured.maxDepth(),
                configured.maxCandidatesPerState(),
                beamWidth''',
        '''                configured.significantImprovementThreshold(),
                configured.maxExpandingSteps(),
                configured.maxCandidatesPerState(),
                beamWidth''',
        "search-heuristic projection",
    )
    source = replace_once(
        source,
        '''                value.maxGeneratedTransitions(),
                value.maxCandidatesPerState(),
                value.maxAstSizeIncreasePerStep(),
                value.maxDepth(),
                Math.max(1, Math.min(
                    value.maxCandidatesPerState(),
                    value.maxRetainedStates()
                ))''',
        '''                value.maxGeneratedTransitions(),
                value.maxCandidatesPerState(),
                value.maxAstSizeIncreasePerStep(),
                value.maxExpandingSteps(),
                value.beamWidth()''',
        "retained budget projection",
    )
    source = replace_once(
        source,
        '"RULE_PROFILE_ALL_WITH_TRACK_SPECIFIC_FORMATION_WITHHOLDING_V1"',
        '"FROZEN_CASE_PROFILE_AND_EXPLICIT_PACKS_WITH_TRACK_SPECIFIC_WITHHOLDING_V1"',
        "visible knowledge policy identity",
    )
    SOURCE.write_text(source, encoding="utf-8")

    test = TEST.read_text(encoding="utf-8")
    test = replace_once(
        test,
        '"occurrence-local-square-bridge"',
        '"occurrence-local-trigonometric-bridge"',
        "corrected occurrence-local case identity",
    )
    assertion = '''        assertTrue(content.entries().stream().allMatch(entry ->
            entry.candidateCount() == entry.candidates().size()
                && entry.candidateBatchHash().startsWith("sha256:")
                && entry.candidateSetHash().startsWith("sha256:")
                && entry.candidateFreezeReceiptHash().startsWith("sha256:")
                && entry.work().contentHash().startsWith("sha256:")
        ));'''
    expanded_assertion = assertion + '''
        assertTrue(content.entries().stream().allMatch(entry ->
            entry.appliedSearchBudget().maxExpandingSteps()
                == entry.configuredBudget().maxExpandingSteps()
                && entry.appliedSearchBudget().beamWidth()
                    == entry.configuredBudget().beamWidth()
        ));
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "assumption-sensitive-cancellation-control"))
            .flatMap(entry -> entry.candidates().stream())
            .filter(candidate -> candidate.expression().equals("1"))
            .allMatch(candidate -> candidate.assumptions().contains(
                "x != 0")));
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "catalog-blind-trigonometric-bridge")
                || entry.caseId().equals(
                    "occurrence-local-trigonometric-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .flatMap(candidate -> candidate.pathRuleIds().stream())
            .noneMatch(rule -> rule.equals(
                "sympy.trig.pythagorean")));
        assertTrue(content.entries().stream()
            .filter(entry -> entry.caseId().equals(
                "telescoping-capability-bridge"))
            .flatMap(entry -> entry.candidates().stream())
            .flatMap(candidate -> candidate.pathRuleIds().stream())
            .noneMatch(rule -> rule.equals(
                "sympy.rational.partial_fraction.telescoping")));'''
    test = replace_once(
        test,
        assertion,
        expanded_assertion,
        "candidate-freeze contract assertions",
    )
    TEST.write_text(test, encoding="utf-8")


if __name__ == "__main__":
    main()
