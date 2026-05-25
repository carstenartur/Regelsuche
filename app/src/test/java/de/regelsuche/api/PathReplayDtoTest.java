package de.regelsuche.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.explain.ExplanationService;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Stage 3 pin: {@link PathReplayDto} carries the comparator-flip flag and
 * per-step diff payload computed server-side, and the back-compat
 * 9-arg constructor of {@link PathReplayDto.ReplayStep} derives them
 * automatically so older codec round-trips keep working.
 */
class PathReplayDtoTest {

    private static ExpressionScore score(int total) {
        return new ExpressionScore(total, total, total, 0, 0);
    }

    private static DiscoveredTransformation pathWithFlippingInequality() {
        TransformationStep step = new TransformationStep(
            0,
            "x < 3",
            "-x > -3",
            "inequality_multiply_both_sides",
            RewriteKind.NORMALIZE,
            10, 8, true, "Mit -1 multiplizieren"
        );
        return new DiscoveredTransformation(
            "p-flip",
            "x < 3",
            "-x > -3",
            List.of(step),
            score(10),
            score(8),
            1,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2026-01-01T00:00:00Z"),
            "hash"
        );
    }

    private static DiscoveredTransformation pathWithoutFlip() {
        TransformationStep step = new TransformationStep(
            0,
            "x*(y+1)",
            "x*y + x",
            "polynomial_distribute",
            RewriteKind.NORMALIZE,
            10, 8, true, "Distribuiergesetz"
        );
        return new DiscoveredTransformation(
            "p-nofold",
            "x*(y+1)",
            "x*y + x",
            List.of(step),
            score(10),
            score(8),
            2,
            CandidateProofStatus.OBSERVED,
            Instant.parse("2026-01-01T00:00:00Z"),
            "hash"
        );
    }

    @Test
    void comparatorFlippedIsSetForInequalityFlipRule() {
        PathReplayDto dto = PathReplayDto.from(pathWithFlippingInequality(), new ExplanationService());
        assertEquals(1, dto.steps().size());
        PathReplayDto.ReplayStep step = dto.steps().get(0);
        assertTrue(step.comparatorFlipped(),
            "Stage 3: comparator-flip flag must be set server-side for "
                + "inequality_multiply_both_sides when the comparator changes direction");
    }

    @Test
    void comparatorFlippedIsFalseForNonFlippingRules() {
        PathReplayDto dto = PathReplayDto.from(pathWithoutFlip(), new ExplanationService());
        PathReplayDto.ReplayStep step = dto.steps().get(0);
        assertFalse(step.comparatorFlipped());
    }

    @Test
    void changedSpansAreNonEmptyForActualChange() {
        PathReplayDto dto = PathReplayDto.from(pathWithoutFlip(), new ExplanationService());
        PathReplayDto.ReplayStep step = dto.steps().get(0);
        assertNotNull(step.changedFromSpans());
        assertNotNull(step.changedToSpans());
        assertFalse(step.changedToSpans().isEmpty(),
            "diff payload must highlight the rewritten substring");
    }

    @Test
    void backCompatNineArgConstructorDerivesFlipAndDiff() {
        PathReplayDto.ReplayStep step = new PathReplayDto.ReplayStep(
            0, "x<3", "x<3", "-x>-3", "-x>-3",
            "inequality_multiply_both_sides", "expl", -2, true);
        assertTrue(step.comparatorFlipped());
        assertNotNull(step.changedToSpans());
        assertFalse(step.changedToSpans().isEmpty());
    }

    @Test
    void replayStepCarriesCollapsedMacroExpansionWithAtomicSteps() {
        List<TransformationStep> atomic = List.of(
            new TransformationStep(0, "A", "mid", "atomic-1", RewriteKind.NORMALIZE, 5, 4, true, ""),
            new TransformationStep(1, "mid", "B", "atomic-2", RewriteKind.NORMALIZE, 4, 2, true, "")
        );
        TransformationStep macroStep = new TransformationStep(
            0, "A", "B", "macro_demo", RewriteKind.NORMALIZE, 5, 2, true, "macro"
        );
        DiscoveredTransformation path = new DiscoveredTransformation(
            "macro-path",
            "A",
            "B",
            List.of(macroStep),
            new ExpressionScore(5, 0, 0, 0, 0),
            new ExpressionScore(2, 0, 0, 0, 0),
            3,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            "hash"
        );

        MacroMoveExpansion expansion = new MacroMoveExpansion(
            "macro_demo", "A", "B", atomic, List.of("supporting-path"), 2.0, false
        );
        PathReplayDto replay = PathReplayDto.from(path, new ExplanationService(), Map.of(0, expansion));

        assertEquals(1, replay.steps().size());
        PathReplayDto.ReplayStep step = replay.steps().getFirst();
        assertNotNull(step.macroMoveExpansion());
        assertFalse(step.macroMoveExpansion().expanded(), "macro replay should be collapsed by default");
        assertEquals(2, step.macroMoveExpansion().atomicSteps().size());
    }
}
