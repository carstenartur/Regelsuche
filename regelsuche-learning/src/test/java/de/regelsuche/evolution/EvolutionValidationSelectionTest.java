package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionValidationSelectionTest {
    private static final List<String> CASE_IDS = List.of(
        "validation-case-a", "validation-case-b", "validation-case-c");

    @Test
    void selectsConfigurationOnlyFromCompleteValidationEvidence() {
        EvolutionValidationCandidate conservative = candidate(
            "conservative", new EvolutionValidationSearchConfiguration(4, 200, 12),
            List.of(evidence("validation-case-a", true, true, 20, 4),
                evidence("validation-case-b", false, false, 30, -1),
                evidence("validation-case-c", false, false, 25, -1)),
            List.of());
        EvolutionValidationCandidate improving = candidate(
            "improving", new EvolutionValidationSearchConfiguration(6, 400, 20),
            List.of(evidence("validation-case-a", true, true, 28, 5),
                evidence("validation-case-b", false, true, 42, 7),
                evidence("validation-case-c", false, false, 33, -1)),
            List.of());

        EvolutionValidationSelection selection = selection(
            List.of(conservative, improving));

        assertEquals(EvolutionValidationSelection.SelectionOutcome.SELECTED,
            selection.selectionOutcome());
        assertEquals(improving.genomeHash(), selection.selectedGenomeHash());
        assertEquals(improving.configurationHash(),
            selection.selectedConfigurationHash());
        assertEquals("VALIDATION", selection.evaluationSplit());
        assertEquals("NOT_EVALUATED", selection.finalTestStatus());
        assertTrue(selection.hasSelection());
        assertEquals(CASE_IDS, selection.validationCaseIds());
    }

    @Test
    void canonicalJsonAndTieBreakAreDeterministic() {
        EvolutionValidationCandidate left = candidate(
            "left", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30), List.of());
        EvolutionValidationCandidate right = candidate(
            "right", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30), List.of());

        EvolutionValidationSelection first = selection(List.of(right, left));
        EvolutionValidationSelection second = selection(List.of(left, right));
        String expected = List.of(left.configurationHash(), right.configurationHash())
            .stream().sorted().findFirst().orElseThrow();

        assertEquals(expected, first.selectedConfigurationHash());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first, EvolutionValidationSelection.fromCanonicalJson(
            first.toCanonicalJson()));
    }

    @Test
    void regressionsFailuresAndBlockersCannotWinSelection() {
        EvolutionValidationCandidate reachabilityRegression = candidate(
            "lost-target", new EvolutionValidationSearchConfiguration(8, 500, 30),
            List.of(evidence("validation-case-a", true, false, 10, -1),
                evidence("validation-case-b", false, true, 15, 3),
                evidence("validation-case-c", false, true, 15, 3)),
            List.of());
        EvolutionValidationCandidate refuted = candidate(
            "refuted", new EvolutionValidationSearchConfiguration(8, 500, 30),
            List.of(refutedEvidence("validation-case-a", true),
                evidence("validation-case-b", false, true, 15, 3),
                evidence("validation-case-c", false, false, 15, -1)),
            List.of());
        EvolutionValidationCandidate blocked = candidate(
            "blocked", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(20), List.of("VALIDATION_INCOMPLETE"));
        EvolutionValidationCandidate safe = candidate(
            "safe", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(40), List.of());

        EvolutionValidationSelection selection = selection(
            List.of(reachabilityRegression, refuted, blocked, safe));

        assertEquals(safe.configurationHash(),
            selection.selectedConfigurationHash());
        assertEquals(1, reachabilityRegression.reachabilityRegressions());
        assertEquals(1, refuted.correctnessFailures());
        assertEquals(1, refuted.correctnessRegressions());
        assertFalse(reachabilityRegression.eligible());
        assertFalse(refuted.eligible());
        assertFalse(blocked.eligible());
    }

    @Test
    void allIneligibleCandidatesProduceTransparentNullSelection() {
        EvolutionValidationCandidate blocked = candidate(
            "blocked", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30), List.of("VALIDATION_INCOMPLETE"));

        EvolutionValidationSelection selection = selection(List.of(blocked));

        assertEquals(
            EvolutionValidationSelection.SelectionOutcome.NO_ELIGIBLE_CANDIDATE,
            selection.selectionOutcome());
        assertEquals("", selection.selectedGenomeHash());
        assertEquals("", selection.selectedConfigurationHash());
        assertFalse(selection.hasSelection());
    }

    @Test
    void incompleteReorderedOrTamperedEvidenceIsRejected() {
        EvolutionValidationCandidate incomplete = candidate(
            "incomplete", new EvolutionValidationSearchConfiguration(4, 200, 12),
            List.of(evidence("validation-case-a", false, false, 10, -1),
                evidence("validation-case-c", false, false, 10, -1)),
            List.of());
        assertThrows(IllegalArgumentException.class,
            () -> selection(List.of(incomplete)));

        EvolutionValidationCandidate valid = candidate(
            "aggregate", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30), List.of());
        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionValidationCandidate(
                valid.genomeHash(), valid.alphaStructuralHash(),
                valid.searchConfiguration(), valid.configurationHash(),
                valid.cases(), valid.reachedCases() + 1,
                valid.newlySolvedCases(), valid.reachabilityRegressions(),
                valid.correctnessFailures(), valid.correctnessRegressions(),
                valid.exploredStates(), valid.candidateEvaluations(),
                valid.blockers()));
    }

    @Test
    void splitStatusSelectionAndUnknownFieldsCannotBeSubstituted() {
        EvolutionValidationCandidate left = candidate(
            "left", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(20), List.of());
        EvolutionValidationCandidate right = candidate(
            "right", new EvolutionValidationSearchConfiguration(4, 200, 12),
            allUnchangedEvidence(40), List.of());
        EvolutionValidationSelection valid = selection(List.of(left, right));

        assertThrows(IllegalArgumentException.class, () -> copyWith(
            valid, "FINAL_TEST", valid.finalTestStatus(),
            valid.selectedGenomeHash(), valid.selectedConfigurationHash()));
        assertThrows(IllegalArgumentException.class, () -> copyWith(
            valid, valid.evaluationSplit(), "COMPLETED",
            valid.selectedGenomeHash(), valid.selectedConfigurationHash()));
        assertThrows(IllegalArgumentException.class, () -> copyWith(
            valid, valid.evaluationSplit(), valid.finalTestStatus(),
            right.genomeHash(), right.configurationHash()));

        String canonical = valid.toCanonicalJson().trim();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(IllegalArgumentException.class,
            () -> EvolutionValidationSelection.fromCanonicalJson(unknown));
    }

    private static EvolutionValidationSelection copyWith(
        EvolutionValidationSelection valid,
        String split,
        String finalStatus,
        String genomeHash,
        String configurationHash
    ) {
        return new EvolutionValidationSelection(
            valid.schema(), valid.studyPlanHash(), valid.splitManifestHash(),
            valid.trainPopulationRunHash(), valid.validationSuiteHash(), split,
            valid.validationCaseIds(), valid.candidates(), valid.selectionPolicy(),
            valid.selectionOutcome(), genomeHash, configurationHash,
            valid.validationStatus(), finalStatus, valid.proofStatus(),
            valid.externalNoveltyStatus(), valid.promotionStatus(),
            valid.publicEvidenceStatus(), valid.contentHash());
    }

    private static EvolutionValidationSelection selection(
        List<EvolutionValidationCandidate> candidates
    ) {
        return EvolutionValidationSelection.create(
            hash("study-plan"), hash("split-manifest"),
            hash("train-population-run"), hash("validation-suite"),
            CASE_IDS, candidates);
    }

    private static EvolutionValidationCandidate candidate(
        String id,
        EvolutionValidationSearchConfiguration configuration,
        List<EvolutionValidationCaseEvidence> cases,
        List<String> blockers
    ) {
        return EvolutionValidationCandidate.create(
            hash("genome-" + id), hash("structure-" + id),
            configuration, cases, blockers);
    }

    private static List<EvolutionValidationCaseEvidence> allUnchangedEvidence(
        long explored
    ) {
        return List.of(
            evidence("validation-case-a", true, true, explored, 4),
            evidence("validation-case-b", false, false, explored, -1),
            evidence("validation-case-c", false, false, explored, -1));
    }

    private static EvolutionValidationCaseEvidence evidence(
        String caseId,
        boolean baselineReached,
        boolean candidateReached,
        long candidateExplored,
        int candidateDepth
    ) {
        EvolutionCorrectnessStatus baseline = baselineReached
            ? EvolutionCorrectnessStatus.CONFIRMED
            : EvolutionCorrectnessStatus.NOT_EVALUATED;
        EvolutionCorrectnessStatus candidate = candidateReached
            ? EvolutionCorrectnessStatus.CONFIRMED
            : EvolutionCorrectnessStatus.NOT_EVALUATED;
        return new EvolutionValidationCaseEvidence(
            caseId, "family", baselineReached, candidateReached,
            baseline, candidate,
            baselineReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            candidateReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            baselineReached ? 5 : -1, candidateDepth, 20, candidateExplored,
            8, Math.max(1, candidateExplored / 2),
            !baselineReached && candidateReached,
            baselineReached && !candidateReached, false, false);
    }

    private static EvolutionValidationCaseEvidence refutedEvidence(
        String caseId,
        boolean baselineReached
    ) {
        EvolutionCorrectnessStatus baseline = baselineReached
            ? EvolutionCorrectnessStatus.CONFIRMED
            : EvolutionCorrectnessStatus.NOT_EVALUATED;
        return new EvolutionValidationCaseEvidence(
            caseId, "family", baselineReached, true,
            baseline, EvolutionCorrectnessStatus.REFUTED,
            baselineReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            "TARGET_REACHED_BUT_REFUTED", baselineReached ? 5 : -1, 3,
            20, 15, 8, 7, false, false, true, baselineReached);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
