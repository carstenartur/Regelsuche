package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
