package de.regelsuche.search;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeachingPathScorerTest {

    @Test
    void ranksTeachingPath() {
        // Short, monotonic, with explanations -> high
        DiscoveredTransformation good = transformation("good", List.of(
            step(0, "a", "b", "rule1", 10, 8, "weil"),
            step(1, "b", "c", "rule2", 8, 5, "weil noch mehr")
        ));
        // Longer, with an expansion step, no explanations -> lower
        DiscoveredTransformation bad = transformation("bad", List.of(
            step(0, "a", "x", "rule1", 10, 12, ""),
            step(1, "x", "y", "rule2", 12, 11, ""),
            step(2, "y", "z", "rule3", 11, 10, ""),
            step(3, "z", "w", "rule4", 10, 9, "")
        ));

        TeachingPathScorer scorer = new TeachingPathScorer();
        double goodScore = scorer.score(good);
        double badScore = scorer.score(bad);
        assertTrue(goodScore > badScore,
            "Good teaching path (" + goodScore + ") should outrank bad (" + badScore + ")");
        assertTrue(goodScore <= 1.0);
        assertTrue(badScore >= 0.0);
    }

    private static DiscoveredTransformation transformation(String id, List<TransformationStep> steps) {
        ExpressionScore origin = new ExpressionScore(20, 20, 10, 2, 0);
        ExpressionScore improved = new ExpressionScore(5, 5, 2, 1, 0);
        return new DiscoveredTransformation(
            id,
            steps.getFirst().beforeExpression(),
            steps.getLast().afterExpression(),
            steps,
            origin,
            improved,
            origin.improvementTo(improved),
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            "hash-" + id
        );
    }

    private static TransformationStep step(int index, String from, String to, String rule,
                                           int scoreBefore, int scoreAfter, String explanation) {
        return new TransformationStep(index, from, to, rule, RewriteKind.SIMPLIFY,
            scoreBefore, scoreAfter, true, explanation);
    }
}
