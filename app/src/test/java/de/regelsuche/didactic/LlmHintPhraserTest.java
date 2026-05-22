package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmHintPhraserTest {

    @Test
    void noOpPhraserReturnsInputUnchanged() {
        HintGenerator.Hint in = new HintGenerator.Hint(
            HintGenerator.Strength.SMALL, "Tipp.");
        HintGenerator.Hint out = new LlmHintPhraser.NoOpLlmHintPhraser()
            .rephrase(in, PedagogyProfile.SCHOOL);
        assertEquals(in, out);
    }

    @Test
    void customPhraserIsInvokedForEveryHint() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmHintPhraser phraser = (hint, profile) -> {
            calls.incrementAndGet();
            return new HintGenerator.Hint(hint.strength(), "[LLM] " + hint.text());
        };
        HintGenerator generator = new HintGenerator(
            new de.regelsuche.explain.ExplanationService(), phraser);
        List<HintGenerator.Hint> hints = generator.hintsFor(
            sampleDerivation(), "a*(b + c)", PedagogyProfile.SCHOOL);
        assertEquals(3, hints.size());
        assertEquals(3, calls.get());
        for (HintGenerator.Hint hint : hints) {
            assertTrue(hint.text().startsWith("[LLM] "), hint.text());
        }
    }

    private static DiscoveredTransformation sampleDerivation() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10, 12, true,
            "Distributivgesetz angewandt");
        return new DiscoveredTransformation(
            "phraser-test",
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
