package de.regelsuche.search.program;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteProgramWorkMetricsTest {
    @Test
    void sequenceRetainsPrimitiveLineageAndCompositionWork() {
        RewriteProgram program = RewritePrograms.sequence(
            "two_stage",
            RewritePrograms.source(
                "first_source",
                exact("a", transformation("r1", "b"))),
            RewritePrograms.source(
                "second_source",
                exact("b", transformation("r2", "c"))));

        RewriteExecution execution = new RewriteProgramInterpreter()
            .execute(program, "a");

        assertTrue(execution.complete());
        assertEquals(1, execution.candidates().size());
        Transformation macro = execution.transformations().getFirst();
        assertEquals(List.of("r1", "r2"), macro.primitiveRuleIds());
        assertEquals(2, macro.primitiveStepCount());
        assertEquals(1, execution.workMetrics().engineInvocations());
        assertEquals(3, execution.workMetrics().programNodeVisits());
        assertEquals(2, execution.workMetrics().sourceInvocations());
        assertEquals(2, execution.workMetrics().sourceCandidates());
        assertEquals(1, execution.workMetrics().composedCandidates());
        assertTrue(execution.workMetrics().totalWorkUnits() >= 9);
    }

    @Test
    void guardsPruningAndAlternativesRemainVisibleWithoutTracing() {
        TransformationEngine empty = expression -> List.of();
        TransformationEngine three = expression -> List.of(
            transformation("keep", "x1"),
            transformation("reject_a", "x2"),
            transformation("reject_b", "x3"));
        TransformationEngine neverReached = expression -> List.of(
            transformation("never", "x4"));
        RewriteProgram guarded = RewritePrograms.require(
            "keep_only",
            RewritePrograms.source("three_candidates", three),
            "keep rule",
            candidate -> candidate.lastStep().rule().equals("keep"));
        RewriteProgram pruned = RewritePrograms.prune(
            "prune_to_one",
            guarded,
            1,
            "bounded test output");
        RewriteProgram program = RewritePrograms.firstApplicable(
            "fallback",
            RewritePrograms.source("empty_source", empty),
            pruned,
            RewritePrograms.source("skipped_source", neverReached));

        RewriteExecution execution = new RewriteProgramInterpreter()
            .execute(program, "x");

        assertEquals(1, execution.candidates().size());
        assertEquals(3, execution.workMetrics().requirementEvaluations());
        assertEquals(2, execution.workMetrics().requirementRejections());
        assertEquals(1, execution.workMetrics().alternativeSelections());
        assertEquals(1, execution.workMetrics().alternativesSkipped());
        assertEquals(0, execution.workMetrics().prunedCandidates(),
            "the guard already reduced the batch to one");
        assertFalse(execution.workMetrics().equals(
            de.regelsuche.transform.TransformationWorkMetrics.ZERO));
    }

    @Test
    void explicitPruningCountsEveryDroppedCandidateAndMarksIncomplete() {
        RewriteProgram program = RewritePrograms.prune(
            "prune_two",
            RewritePrograms.source(
                "three",
                expression -> List.of(
                    transformation("a_rule", "a"),
                    transformation("b_rule", "b"),
                    transformation("c_rule", "c"))),
            1,
            "retain one");

        RewriteExecution execution = new RewriteProgramInterpreter()
            .execute(program, "x");

        assertFalse(execution.complete());
        assertEquals(1, execution.candidates().size());
        assertEquals(2, execution.workMetrics().prunedCandidates());
    }

    private static TransformationEngine exact(
        String input,
        Transformation output
    ) {
        return expression -> expression.equals(input)
            ? List.of(output)
            : List.of();
    }

    private static Transformation transformation(String rule, String output) {
        return new Transformation(
            rule,
            output,
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true,
            rule + ":" + output);
    }
}
