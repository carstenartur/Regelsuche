package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SparseHornerSearchTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test void taskSolverPaysForTheSameCheckedSourceOnlyMove() {
        String input = "1+t/2-t*t/8+t*t*t/16-5*t*t*t*t/128";
        var source = parser.parseTerm(input);
        var solver = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory())
            .newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_HORNER_V1);
        var result = solver.simplify(source, List.of(), new MoveSearch.Budget(9, 9, 16_384, 256, 16_384, 24));
        assertTrue(result.withinBudget());
        assertEquals(9, result.outputScore());
        new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(result.incumbent().expression()));
        assertTrue(result.witness().stream().anyMatch(step -> step.move().transformation().exactTheoryStepCount() == 1));
        assertTrue(result.replayWork() > 0);

        var forbidden = solver.simplify(source, List.of(), new MoveSearch.Budget(9, 9, 0, 256, 16_384, 24));
        assertTrue(forbidden.witness().stream().noneMatch(step -> step.move().transformation().exactTheoryStepCount() > 0));
        assertTrue(forbidden.search().events().stream().anyMatch(event -> event.decision() == MoveSearch.Decision.PATH_BOUND));
    }

    @Test void receiverRejectsAnEquivalentButDifferentSourceAndPartialInput() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.POLYNOMIAL_HORNER);
        var codec = new CompiledAstReplayCodec();
        var source = parser.parseTerm("a*b+a*c-a");
        var move = provider.candidates(MoveState.root(codec.encodeExpression(source)), MoveContext.frozen("")).moves().getFirst();
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var actual = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        assertTrue(provider.verifier().verify(actual, move, context).accepted());
        var other = new TypedMoveSearch.State(parser.parseTerm("a*c+a*b-a"), 0, 0, "", List.of(), Set.of(), 0);
        var rejected = provider.verifier().verify(other, move, context);
        assertFalse(rejected.accepted());
        assertTrue(rejected.work() > 0);
        var partial = provider.candidates(MoveState.root(codec.encodeExpression(parser.parseTerm("b/a-b/a"))), MoveContext.frozen(""));
        assertTrue(partial.moves().isEmpty());
        assertTrue(partial.work().totalWorkUnits() > 0);
    }

    @Test void sharedSurfaceObjectiveRefusesIllegalCheapCandidates() {
        for (String text : List.of("a^2", "a/(b-b+1)", "a/(2-2)", "a*9223372036854775808")) {
            var scored = TypedPolynomialSurfaceCost.evaluateSharedArithmetic(parser.parseTerm(text));
            assertEquals(Long.MAX_VALUE, scored.value(), text);
            assertTrue(scored.work() > 0, text);
        }
        var rational = TypedPolynomialSurfaceCost.evaluateSharedArithmetic(parser.parseTerm("a*(a+2)/(2+3)"));
        assertEquals(4, rational.value());
        assertTrue(rational.work() > 4);
    }

    @Test void rootOnlyProfileChecksOneImprovementAndPaysForANoOp() throws Exception {
        String profile = "POLYNOMIAL_HORNER_ROOT_V1";
        var worker = new TypedExternalPolynomialComparisonWorker();
        var setup = worker.initialize(profile);
        assertTrue(setup.get("model").toString().contains("ROOT_HORNER_ONLY"));
        String input = "a*b+a*c-a*b+a-b+c+b";
        var improved = worker.run(profile, input);
        assertEquals(true, improved.get("internalWorkWithinBudget"));
        assertTrue((Long) improved.get("outputCost") < (Long) improved.get("inputCost"));
        assertTrue((Long) improved.get("selectedReplayWork") > 0);
        assertEquals(1, ((List<?>) improved.get("witness")).size());
        new ExactPolynomialAnalysis().requireEquivalent(input, (String) improved.get("output"));

        var rejected = worker.run(profile, "(0-t*t*t)/6");
        assertEquals(rejected.get("inputCost"), rejected.get("outputCost"));
        assertEquals(List.of(), rejected.get("witness"));
        assertEquals(0L, rejected.get("selectedReplayWork"));
        assertTrue((Long) rejected.get("totalWork") > 0);
        var trace = new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) rejected.get("search"));
        assertEquals(0, trace.path("metrics").path("generatedSuccessors").intValue());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> worker.run(profile, "t^3"));
    }

    @Test void rootReceiverRejectsAValidEqualityThatWouldNotImproveTheSurface() {
        var operation = StructuralConstructionPrograms.Operation.POLYNOMIAL_HORNER;
        var general = new StructuralConstructionProvider(operation);
        var root = new StructuralConstructionProvider(operation, 0, true);
        var source = parser.parseTerm("a+a");
        var state = MoveState.root(new CompiledAstReplayCodec().encodeExpression(source));
        var moveContext = MoveContext.frozen("");
        var move = general.candidates(state, moveContext).moves().getFirst();
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var typed = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        assertTrue(general.verifier().verify(typed, move, context).accepted());
        assertTrue(root.candidates(state, moveContext).moves().isEmpty());
        var rejected = root.verifier().verify(typed, move, context);
        assertFalse(rejected.accepted());
        assertTrue(rejected.work() > 0);
    }
}
