package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HintGeneratorTest {

    private final HintGenerator generator = new HintGenerator();

    @Test
    void hintSystemProducesGraduatedHints() {
        List<HintGenerator.Hint> hints = generator.hintsFor(samplePath(), "a*(b + c)");

        assertEquals(3, hints.size(), "expected three graduated hints");
        assertEquals(HintGenerator.Strength.SMALL,     hints.get(0).strength());
        assertEquals(HintGenerator.Strength.STRONG,    hints.get(1).strength());
        assertEquals(HintGenerator.Strength.FULL_STEP, hints.get(2).strength());

        // The "small" hint must not reveal the operator-level solution.
        assertTrue(!hints.get(0).text().contains("a*b + a*c"),
            "small hint must not reveal the final transformation");

        // The "full step" hint must show the before -> after pair.
        assertTrue(hints.get(2).text().contains("a*(b + c)"));
        assertTrue(hints.get(2).text().contains("a*b + a*c"));

        // Strong hint must be different from small hint and informative.
        assertNotEquals(hints.get(0).text(), hints.get(1).text());
    }

    @Test
    void hintsAreOmittedForEmptyDerivation() {
        DiscoveredTransformation empty = new DiscoveredTransformation(
            "tid", "x", "x", List.of(),
            new ExpressionScore(1, 0, 1, 0, 0),
            new ExpressionScore(1, 0, 1, 0, 0),
            0,
            CandidateProofStatus.OBSERVED,
            Instant.EPOCH,
            "hash");
        assertTrue(generator.hintsFor(empty, "x").isEmpty());
    }

    private static DiscoveredTransformation samplePath() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10,
            12,
            true,
            "Distributivgesetz angewandt");
        return new DiscoveredTransformation(
            "tid",
            "a*(b + c)",
            "a*b + a*c",
            List.of(step),
            new ExpressionScore(8, 5, 2, 2, 0),
            new ExpressionScore(10, 7, 3, 2, 0),
            -2,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash");
    }
}
