package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.InterestingnessAssessment.Eligibility;
import de.regelsuche.mining.InterestingnessEvidence.ControlClassification;
import de.regelsuche.mining.InterestingnessEvidence.ProjectNoveltyStatus;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EvidenceAwareInterestingnessAssessorTest {
    private final EvidenceAwareInterestingnessAssessor assessor =
        new EvidenceAwareInterestingnessAssessor();

    @Test
    void counterexampleIsAHardBlockerRegardlessOfStructuralScore() {
        HypothesisCandidate candidate = reusableCandidate("blocked");
        InterestingnessEvidence refuted = evidence(
            "refuted-evidence",
            CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND,
            3,
            4,
            4,
            0,
            4,
            4,
            0,
            0,
            1,
            1,
            true,
            900,
            ControlClassification.NONE);

        InterestingnessAssessment assessment = assessor.assess(
            candidate, 0.0, Set.of("algebra", "rational", "functional"),
            refuted, InterestingnessProfile.THEORY_DISCOVERY);

        assertEquals(Eligibility.BLOCKED, assessment.eligibility());
        assertEquals(-1000, assessment.totalPermille());
        assertTrue(assessment.hardBlockers().contains("counterexample-found"));
        assertFalse(assessment.contributions().isEmpty());
    }

    @Test
    void incompleteEvidenceReceivesVisiblePenaltyAndRanksBelowCompleteTwin() {
        HypothesisCandidate completeCandidate = reusableCandidate("complete");
        HypothesisCandidate incompleteCandidate = reusableCandidate("incomplete");
        InterestingnessEvidence complete = completeEvidence("complete-evidence");
        InterestingnessEvidence incomplete = evidence(
            "incomplete-evidence",
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            3,
            4,
            3,
            1,
            4,
            4,
            0,
            0,
            1,
            0,
            false,
            0,
            ControlClassification.NONE);

        InterestingnessAssessment completeAssessment = assessor.assess(
            completeCandidate, 0.1, Set.of("algebra", "rational", "functional"),
            complete, InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment incompleteAssessment = assessor.assess(
            incompleteCandidate, 0.1, Set.of("algebra", "rational", "functional"),
            incomplete, InterestingnessProfile.THEORY_DISCOVERY);

        assertEquals(Eligibility.RANKABLE_COMPLETE, completeAssessment.eligibility());
        assertEquals(Eligibility.RANKABLE_INCOMPLETE, incompleteAssessment.eligibility());
        assertTrue(incompleteAssessment.unresolvedRiskPenaltyPermille() > 0);
        assertTrue(incompleteAssessment.warnings().contains("positive-checks-skipped=1"));
        assertTrue(incompleteAssessment.warnings().contains("held-out-transfer-incomplete"));
        assertTrue(incompleteAssessment.warnings().contains("paired-utility-not-evaluated"));
        assertTrue(completeAssessment.compareTo(incompleteAssessment) < 0);
    }

    @Test
    void trivialNormalizationRanksBelowReusableHeldOutBridge() {
        HypothesisCandidate trivial = candidate(
            "trivial", "A + 0", "A", List.of("r1"),
            List.of(new HypothesisCandidate.ExpressionPair("x + 0", "x")),
            0.95);
        HypothesisCandidate reusable = reusableCandidate("reusable");
        InterestingnessEvidence trivialEvidence = evidence(
            "trivial-evidence",
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            2,
            2,
            2,
            0,
            2,
            2,
            0,
            0,
            0,
            0,
            true,
            50,
            ControlClassification.GENERIC_NORMALIZATION);

        InterestingnessAssessment trivialAssessment = assessor.assess(
            trivial, 0.95, Set.of("algebra"), trivialEvidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment reusableAssessment = assessor.assess(
            reusable, 0.1, Set.of("algebra", "rational", "functional"),
            completeEvidence("reusable-evidence"),
            InterestingnessProfile.THEORY_DISCOVERY);

        assertEquals(Eligibility.RANKABLE_COMPLETE, trivialAssessment.eligibility());
        assertTrue(trivialAssessment.controlPenaltyPermille() > 0);
        assertTrue(reusableAssessment.totalPermille() > trivialAssessment.totalPermille());
    }

    @Test
    void variableRenamingAndFormattingLeaveNamedComponentsStable() {
        HypothesisCandidate first = candidate(
            "first",
            "A * B + A * C",
            "A * (B + C)",
            List.of("r1>r2", "r3>r4"),
            List.of(
                new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                new HypothesisCandidate.ExpressionPair("a*4+a*5", "a*(4+5)")),
            0.8);
        HypothesisCandidate renamed = candidate(
            "renamed",
            " X*Y+X*Z ",
            "X*(Y+Z)",
            List.of("s1>s2", "s3>s4"),
            List.of(
                new HypothesisCandidate.ExpressionPair("p*6+p*7", "p*(6+7)"),
                new HypothesisCandidate.ExpressionPair("q*8+q*9", "q*(8+9)")),
            0.8);
        InterestingnessEvidence evidence = completeEvidence("rename-evidence");

        InterestingnessAssessment firstAssessment = assessor.assess(
            first, 0.2, Set.of("algebra", "rational"), evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment renamedAssessment = assessor.assess(
            renamed, 0.2, Set.of("rational", "algebra"), evidence,
            InterestingnessProfile.THEORY_DISCOVERY);

        assertEquals(rawComponents(firstAssessment), rawComponents(renamedAssessment));
        assertEquals(firstAssessment.totalPermille(), renamedAssessment.totalPermille());
    }

    @Test
    void profilesChangeWeightsButNotRawEvidenceComponents() {
        HypothesisCandidate candidate = reusableCandidate("profiled");
        InterestingnessEvidence evidence = completeEvidence("profile-evidence");

        InterestingnessAssessment theory = assessor.assess(
            candidate, 0.1, Set.of("algebra", "rational", "functional"), evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment reuse = assessor.assess(
            candidate, 0.1, Set.of("algebra", "rational", "functional"), evidence,
            InterestingnessProfile.SEARCH_REUSE);

        assertEquals(rawComponents(theory), rawComponents(reuse));
        assertFalse(weightedComponents(theory).equals(weightedComponents(reuse)));
        assertEquals(1000, theory.profile().weights().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1000, reuse.profile().weights().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void canonicalAssessmentIsDeterministic() {
        HypothesisCandidate candidate = reusableCandidate("deterministic");
        InterestingnessEvidence evidence = completeEvidence("deterministic-evidence");

        InterestingnessAssessment first = assessor.assess(
            candidate, 0.15, Set.of("functional", "algebra", "rational"), evidence,
            InterestingnessProfile.THEORY_DISCOVERY);
        InterestingnessAssessment second = assessor.assess(
            candidate, 0.15, Set.of("rational", "functional", "algebra"), evidence,
            InterestingnessProfile.THEORY_DISCOVERY);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.interestingness-assessment/v1\""));
        assertTrue(first.toCanonicalJson().contains("\"hardBlockers\":[]"));
        assertTrue(first.toCanonicalJson().contains("\"externalNoveltyStatus\":\"NOT_EVALUATED\""));
    }

    private static InterestingnessEvidence completeEvidence(String id) {
        return evidence(
            id,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            4,
            4,
            4,
            0,
            4,
            4,
            0,
            0,
            1,
            1,
            true,
            700,
            ControlClassification.NONE);
    }

    private static InterestingnessEvidence evidence(
        String id,
        CounterexampleSearchService.Status counterexampleStatus,
        int sources,
        int configuredPositive,
        int executedPositive,
        int skippedPositive,
        int configuredNegative,
        int executedNegative,
        int skippedNegative,
        int failedNegative,
        int heldOutConfigured,
        int heldOutPassed,
        boolean utilityEvaluated,
        int utilityPermille,
        ControlClassification control
    ) {
        return new InterestingnessEvidence(
            id,
            configuredPositive,
            executedPositive,
            skippedPositive,
            0,
            configuredNegative,
            executedNegative,
            skippedNegative,
            failedNegative,
            counterexampleStatus,
            sources,
            false,
            ProjectNoveltyStatus.NOVEL_WITHIN_PROJECT,
            heldOutConfigured > 0 ? 3 : 1,
            heldOutConfigured > 0,
            heldOutConfigured,
            heldOutPassed,
            utilityEvaluated,
            utilityPermille,
            control);
    }

    private static HypothesisCandidate reusableCandidate(String id) {
        return candidate(
            id,
            "A * B + A * C",
            "A * (B + C)",
            List.of("r1>r2>r3", "r4>r5", "r6>r7"),
            List.of(
                new HypothesisCandidate.ExpressionPair("x*2+x*3", "x*(2+3)"),
                new HypothesisCandidate.ExpressionPair("a*4+a*5", "a*(4+5)"),
                new HypothesisCandidate.ExpressionPair("p*6+p*7", "p*(6+7)")),
            0.85);
    }

    private static HypothesisCandidate candidate(
        String id,
        String left,
        String right,
        List<String> paths,
        List<HypothesisCandidate.ExpressionPair> witnesses,
        double novelty
    ) {
        return new HypothesisCandidate(
            id,
            left,
            right,
            paths,
            witnesses,
            List.of(),
            novelty,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Boolean.FALSE,
            List.of("A and B are defined"),
            Map.of("A", List.of("x", "a", "p")),
            Instant.EPOCH);
    }

    private static Map<String, Integer> rawComponents(InterestingnessAssessment assessment) {
        return assessment.contributions().stream().collect(Collectors.toMap(
            InterestingnessAssessment.ComponentContribution::name,
            InterestingnessAssessment.ComponentContribution::rawPermille));
    }

    private static Map<String, Integer> weightedComponents(InterestingnessAssessment assessment) {
        return assessment.contributions().stream().collect(Collectors.toMap(
            InterestingnessAssessment.ComponentContribution::name,
            InterestingnessAssessment.ComponentContribution::weightedPermille));
    }
}
