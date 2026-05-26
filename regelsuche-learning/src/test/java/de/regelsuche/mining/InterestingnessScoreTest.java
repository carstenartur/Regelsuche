package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InterestingnessScoreTest {
    @Test
    void trivialIdentityRanksBelowReusableGeneralizedHypothesis() {
        HypothesisCandidate trivial = candidate("trivial", "A + 0", "A", List.of("p1"), List.of(), 0.9);
        HypothesisCandidate reusable = candidate("reusable", "(A + B) ^ 2", "A ^ 2 + 2 * A * B + B ^ 2",
            List.of("algebra:p1", "calculus:p2", "matrix:p3"),
            List.of(new HypothesisCandidate.ExpressionPair("(x+1)^2", "x^2+2*x+1"),
                new HypothesisCandidate.ExpressionPair("(y+2)^2", "y^2+4*y+4")),
            0.1);

        assertTrue(InterestingnessScore.from(reusable, 0.1).total()
            > InterestingnessScore.from(trivial, 0.9).total());
    }

    @Test
    void defaultInterestingnessModelIsSplitIntoIndependentFactors() {
        assertEquals(List.of(
                "compression",
                "generalization",
                "reusability",
                "surprise",
                "crossDomain",
                "assumptionComplexity",
                "proofConfidence",
                "counterexampleRobustness"),
            InterestingnessScore.defaultModules().stream().map(InterestingnessScoringModule::name).toList());
    }

    @Test
    void knownRuleSimilarityDemotesTrivialRediscovery() {
        KnownRuleSimilarityService service = new KnownRuleSimilarityService();

        double known = service.similarityToKnownRules("x^2 + 2*a*x + a^2", "(x + a)^2", new KnownRuleRepository());
        double novel = service.similarityToKnownRules("sin(x) + det(M)", "trace(M) + cos(x)", new KnownRuleRepository());

        assertTrue(known > 0.95);
        assertTrue(novel < known);
        assertTrue(InterestingnessScore.from(candidate("known", "x^2 + 2*a*x + a^2", "(x + a)^2",
                List.of("algebra:p1"), List.of(), 0.1), new KnownRuleRepository()).similarityToKnownRules()
            > 0.95);
    }

    @Test
    void crossDomainRecurrenceAndReplayUsefulnessIncreaseScore() {
        HypothesisCandidate singleUse = candidate("single", "A * 1", "A", List.of("algebra:p1"), List.of(), 0.5);
        HypothesisCandidate recurring = candidate("recurring", "F(A, B)", "G(A, B)",
            List.of("algebra:p1>p2>p3", "trig:p4>p5", "matrix:p6>p7", "combinatorics:p8>p9"),
            List.of(
                new HypothesisCandidate.ExpressionPair("f(x,y)", "g(x,y)"),
                new HypothesisCandidate.ExpressionPair("f(a,b)", "g(a,b)")
            ),
            0.8);

        InterestingnessScore singleScore = InterestingnessScore.from(singleUse, 0.2);
        InterestingnessScore recurringScore = InterestingnessScore.from(recurring, 0.2);

        assertTrue(recurringScore.crossDomainRecurrence() > singleScore.crossDomainRecurrence());
        assertTrue(recurringScore.macroReusability() > singleScore.macroReusability());
        assertTrue(recurringScore.total() > singleScore.total());
    }

    @Test
    void fewerAndWeakerAssumptionsRankHigher() {
        HypothesisCandidate weak = candidate("weak", "x / x", "1", List.of("algebra:p1"), List.of(), 0.5)
            .withAssumptions(List.of("x != 0"));
        HypothesisCandidate strong = candidate("strong", "x / x", "1", List.of("algebra:p1"), List.of(), 0.5)
            .withAssumptions(List.of("x > 0", "x < 10", "x != 0"));

        assertTrue(InterestingnessScore.from(weak, 0.2).minimalAssumptions()
            > InterestingnessScore.from(strong, 0.2).minimalAssumptions());
    }

    @Test
    void compressionScoreRewardsLongPathsCollapsedIntoMacro() {
        HypothesisCandidate direct = candidate("direct", "A + 0", "A", List.of("algebra:p1"), List.of(), 0.4);
        HypothesisCandidate compressed = candidate("compressed", "A", "B",
            List.of("algebra:r1>r2>r3>r4>r5", "algebra:r6>r7>r8>r9"),
            List.of(), 0.4);

        assertTrue(InterestingnessScore.from(compressed, 0.2).compressionGain()
            > InterestingnessScore.from(direct, 0.2).compressionGain());
    }

    @Test
    void assumptionMinimizerKeepsRequiredNonZeroAndDropsIrrelevantAssumption() {
        HypothesisCandidate hypothesis = candidate("divide", "x / x", "1", List.of("p1"), List.of(), 0.0)
            .withAssumptions(List.of("x != 0", "y > 0"));

        HypothesisCandidate minimized = AssumptionMinimizer.minimize(hypothesis,
            candidate -> candidate.assumptions().contains("x != 0"));

        assertTrue(minimized.assumptions().contains("x != 0"));
        assertTrue(!minimized.assumptions().contains("y > 0"));
    }

    private static HypothesisCandidate candidate(
        String id,
        String left,
        String right,
        List<String> paths,
        List<HypothesisCandidate.ExpressionPair> evidence,
        double novelty
    ) {
        return new HypothesisCandidate(id, left, right, paths, evidence, List.of(), novelty,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES, false, List.of(), Map.of("A", List.of("x")), Instant.now());
    }
}
