package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionValidationSelection.CandidateValidation;
import de.regelsuche.evolution.EvolutionValidationSelection.SearchConfiguration;
import de.regelsuche.evolution.EvolutionValidationSelection.SelectionOutcome;
import de.regelsuche.evolution.EvolutionValidationSelection.ValidationCaseEvidence;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionValidationSelectionTest {

    private static final List<String> CASE_IDS = List.of(
        "validation-case-a",
        "validation-case-b",
        "validation-case-c");

    @Test
    void selectsGenomeAndHyperparametersOnlyFromCompleteValidationEvidence() {
        CandidateValidation conservative = candidate(
            "conservative",
            new SearchConfiguration(4, 200, 12),
            List.of(
                evidence("validation-case-a", "family-shared", true, true, 20, 4),
                evidence("validation-case-b", "family-shared", false, false, 30, -1),
                evidence("validation-case-c", "family-other", false, false, 25, -1)),
            List.of());
        CandidateValidation improving = candidate(
            "improving",
            new SearchConfiguration(6, 400, 20),
            List.of(
                evidence("validation-case-a", "family-shared", true, true, 28, 5),
                evidence("validation-case-b", "family-shared", false, true, 42, 7),
                evidence("validation-case-c", "family-other", false, false, 33, -1)),
            List.of());

        EvolutionValidationSelection selection = selection(
            List.of(conservative, improving));

        assertEquals(SelectionOutcome.SELECTED, selection.selectionOutcome());
        assertEquals(improving.genomeHash(), selection.selectedGenomeHash());
        assertEquals(
            improving.configurationHash(),
            selection.selectedConfigurationHash());
        assertEquals("COMPLETED", selection.validationStatus());
        assertEquals("NOT_EVALUATED", selection.finalTestStatus());
        assertEquals("NOT_EVALUATED", selection.proofStatus());
        assertTrue(selection.hasSelection());
        assertEquals(CASE_IDS, selection.validationCaseIds());
        assertEquals(2, selection.candidates().size());
    }

    @Test
    void canonicalJsonIsIndependentOfCandidateInputOrder() {
        CandidateValidation left = candidate(
            "left",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of());
        CandidateValidation right = candidate(
            "right",
            new SearchConfiguration(5, 240, 14),
            allUnchangedEvidence(25),
            List.of());

        EvolutionValidationSelection first = selection(List.of(left, right));
        EvolutionValidationSelection second = selection(List.of(right, left));

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            first,
            EvolutionValidationSelection.fromCanonicalJson(
                first.toCanonicalJson()));
    }

    @Test
    void stableTieBreakUsesConfigurationHashAfterEquivalentValidationResults() {
        CandidateValidation left = candidate(
            "tie-left",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of());
        CandidateValidation right = candidate(
            "tie-right",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of());

        EvolutionValidationSelection selection = selection(
            List.of(right, left));
        String expected = List.of(
                left.configurationHash(), right.configurationHash())
            .stream().sorted().findFirst().orElseThrow();

        assertEquals(expected, selection.selectedConfigurationHash());
    }

    @Test
    void correctnessRegressionIsNeverEligibleForSelection() {
        CandidateValidation regressing = candidate(
            "regressing",
            new SearchConfiguration(8, 500, 30),
            List.of(
                evidence("validation-case-a", "family-shared", true, false, 10, -1),
                evidence("validation-case-b", "family-shared", false, true, 15, 3),
                evidence("validation-case-c", "family-other", false, true, 15, 3)),
            List.of());
        CandidateValidation safe = candidate(
            "safe",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(40),
            List.of());

        EvolutionValidationSelection selection = selection(
            List.of(regressing, safe));

        assertEquals(safe.configurationHash(), selection.selectedConfigurationHash());
        assertEquals(1, regressing.correctnessRegressions());
        assertFalse(regressing.eligible());
    }

    @Test
    void allBlockedCandidatesProduceTransparentNullSelection() {
        CandidateValidation blocked = candidate(
            "blocked",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of("VALIDATION_EXECUTION_INCOMPLETE"));

        EvolutionValidationSelection selection = selection(List.of(blocked));

        assertEquals(
            SelectionOutcome.NO_ELIGIBLE_CANDIDATE,
            selection.selectionOutcome());
        assertEquals("", selection.selectedGenomeHash());
        assertEquals("", selection.selectedConfigurationHash());
        assertFalse(selection.hasSelection());
    }

    @Test
    void repeatedFamilyCasesWithinValidationSplitAreAllowed() {
        CandidateValidation candidate = candidate(
            "same-family-cases",
            new SearchConfiguration(4, 200, 12),
            List.of(
                evidence("validation-case-a", "same-family", false, false, 10, -1),
                evidence("validation-case-b", "same-family", false, false, 10, -1),
                evidence("validation-case-c", "same-family", false, false, 10, -1)),
            List.of());

        EvolutionValidationSelection selection = selection(List.of(candidate));

        assertTrue(selection.hasSelection());
        assertEquals(3, selection.candidates().getFirst().cases().size());
    }

    @Test
    void incompleteOrReorderedCaseMatrixIsRejected() {
        CandidateValidation incomplete = candidate(
            "incomplete",
            new SearchConfiguration(4, 200, 12),
            List.of(
                evidence("validation-case-a", "family-a", false, false, 10, -1),
                evidence("validation-case-c", "family-c", false, false, 10, -1)),
            List.of());

        assertThrows(IllegalArgumentException.class, () -> selection(
            List.of(incomplete)));
    }

    @Test
    void aggregateTamperingIsRejected() {
        CandidateValidation valid = candidate(
            "aggregate",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of());

        assertThrows(IllegalArgumentException.class, () ->
            new CandidateValidation(
                valid.genomeHash(),
                valid.alphaStructuralHash(),
                valid.searchConfiguration(),
                valid.configurationHash(),
                valid.cases(),
                valid.reachedCases() + 1,
                valid.newlySolvedCases(),
                valid.correctnessRegressions(),
                valid.exploredStates(),
                valid.candidateEvaluations(),
                valid.blockers()));
    }

    @Test
    void finalTestOrDownstreamStatusCannotBeSmuggledIntoSelection() {
        EvolutionValidationSelection valid = selection(List.of(candidate(
            "status",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(30),
            List.of())));

        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionValidationSelection(
                valid.schema(),
                valid.studyPlanHash(),
                valid.splitManifestHash(),
                valid.trainPopulationRunHash(),
                valid.validationSuiteHash(),
                valid.validationCaseIds(),
                valid.candidates(),
                valid.selectionPolicy(),
                valid.selectionOutcome(),
                valid.selectedGenomeHash(),
                valid.selectedConfigurationHash(),
                valid.validationStatus(),
                "COMPLETED",
                valid.proofStatus(),
                valid.externalNoveltyStatus(),
                valid.promotionStatus(),
                valid.publicEvidenceStatus(),
                valid.contentHash()));
    }

    @Test
    void selectedConfigurationSubstitutionAndUnknownJsonFieldsAreRejected() {
        CandidateValidation left = candidate(
            "selected-left",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(20),
            List.of());
        CandidateValidation right = candidate(
            "selected-right",
            new SearchConfiguration(4, 200, 12),
            allUnchangedEvidence(40),
            List.of());
        EvolutionValidationSelection valid = selection(List.of(left, right));

        assertThrows(IllegalArgumentException.class, () ->
            new EvolutionValidationSelection(
                valid.schema(),
                valid.studyPlanHash(),
                valid.splitManifestHash(),
                valid.trainPopulationRunHash(),
                valid.validationSuiteHash(),
                valid.validationCaseIds(),
                valid.candidates(),
                valid.selectionPolicy(),
                valid.selectionOutcome(),
                right.genomeHash(),
                right.configurationHash(),
                valid.validationStatus(),
                valid.finalTestStatus(),
                valid.proofStatus(),
                valid.externalNoveltyStatus(),
                valid.promotionStatus(),
                valid.publicEvidenceStatus(),
                valid.contentHash()));

        String canonical = valid.toCanonicalJson().trim();
        String unknown = canonical.substring(0, canonical.length() - 1)
            + ",\"unknown\":true}";
        assertThrows(
            IllegalArgumentException.class,
            () -> EvolutionValidationSelection.fromCanonicalJson(unknown));
    }

    private static EvolutionValidationSelection selection(
        List<CandidateValidation> candidates
    ) {
        return EvolutionValidationSelection.create(
            hash("study-plan"),
            hash("split-manifest"),
            hash("train-population-run"),
            hash("validation-suite"),
            CASE_IDS,
            candidates);
    }

    private static CandidateValidation candidate(
        String id,
        SearchConfiguration configuration,
        List<ValidationCaseEvidence> cases,
        List<String> blockers
    ) {
        return CandidateValidation.create(
            hash("genome-" + id),
            hash("structure-" + id),
            configuration,
            cases,
            blockers);
    }

    private static List<ValidationCaseEvidence> allUnchangedEvidence(
        long exploredStates
    ) {
        return List.of(
            evidence("validation-case-a", "family-shared", true, true, exploredStates, 4),
            evidence("validation-case-b", "family-shared", false, false, exploredStates, -1),
            evidence("validation-case-c", "family-other", false, false, exploredStates, -1));
    }

    private static ValidationCaseEvidence evidence(
        String caseId,
        String family,
        boolean baselineReached,
        boolean candidateReached,
        long candidateExploredStates,
        int candidateDepth
    ) {
        return new ValidationCaseEvidence(
            caseId,
            family,
            baselineReached,
            candidateReached,
            baselineReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            candidateReached ? "TARGET_REACHED" : "FRONTIER_EXHAUSTED",
            baselineReached ? 5 : -1,
            candidateDepth,
            20,
            candidateExploredStates,
            8,
            Math.max(1, candidateExploredStates / 2),
            !baselineReached && candidateReached,
            baselineReached && !candidateReached);
    }

    private static String hash(String value) {
        return EvolutionGenome.hash(value);
    }
}
