package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(180)
class PolynomialSimplificationTest {
    private static final MoveSearch.Budget BUDGET = new MoveSearch.Budget(8, 8, 1_000_000, 32, 1_000_000, 64);

    @Test
    void closesArithmeticGroupingExpansionAndFactorizationGaps() {
        String[][] cases = {
            {"(1/3)*a+(2/3)*a", "0"},
            {"(a+b)*(a-b)+b*(b+2)", "3"},
            {"3*a*b+3*a*c+5*d*b+5*d*c", "5"},
            {"a*a*a+3*a*a+3*a+1", "2"},
            {"-((a+b)*(a-b)+b*b)+17", "2"},
            {"((a+b)*(a-b)+b*b)*(1/7)", "2"},
            {"(a+b)*(a-b)+b*b*b+2", "6"},
            {"(a+3)*c+(b+5)*c", "3"},
            {"((a+b)*(a-b)+b*(b+2))+((c+d)*(c-d)+d*(d+3))", "7"},
            {"((a*b+c)*(a*b-c)+c*c)*(d+7)", "4"}
        };
        var solver = solver();
        for (var example : cases) {
            var result = solver.simplify(parse(example[0]), List.of(), BUDGET);
            String output = ExpressionFormatter.format(result.incumbent().expression());
            new ExactPolynomialAnalysis().requireEquivalent(example[0], output);
            assertTrue(result.withinBudget(), example[0] + " work=" + result.totalWork());
            assertTrue(result.outputScore() <= Long.parseLong(example[1]), example[0] + " -> " + output
                + "; cost=" + result.outputScore() + "; work=" + result.totalWork());
            assertTrue(result.witness().stream().allMatch(edge -> edge.verification().accepted()));
        }
    }

    @Test
    void rejectsVariableDenominatorsBeforeAnyCancellation() {
        for (String source : List.of("x/x", "0/(x-x)", "(x-1/y)+1/y", "sin(x)+1")) {
            assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
                () -> solver().simplify(parse(source), List.of(), BUDGET), source);
        }
    }

    @Test
    void retainsBetterInputsAndChargesFreshInvocations() {
        var solver = solver();
        for (String source : List.of("a+23", "(a+2)*(b+3)*(c+5)", "(a*b)^2*(c+7)")) {
            var input = parse(source);
            var first = solver.simplify(input, List.of(), BUDGET);
            var second = solver.simplify(input, List.of(), BUDGET);
            assertEquals(TypedPolynomialSurfaceCost.evaluate(input).value(), first.outputScore());
            assertEquals(input, first.incumbent().expression(), "ties preserve the actual source");
            assertEquals(first.totalWork(), second.totalWork(), "a repeated call is not a free cached answer");
            assertTrue(first.totalWork() > 0);
        }
    }

    @Test
    void tinyBudgetCannotAuthorizeAnUnpaidSimplification() {
        var tiny = new MoveSearch.Budget(8, 8, 1_000_000, 32, 1, 64);
        var result = solver().simplify(parse("(1/3)*a+(2/3)*a"), List.of(), tiny);
        assertFalse(result.withinBudget());
        assertTrue(result.totalWork() > 1);
    }

    @Test
    void persistedKnowledgeUsesTheSameNativeProfile() {
        var genome = TraceStrategyTransferExample.inventory();
        var knowledge = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.POLYNOMIAL_SIMPLIFICATION_V1);
        var restored = CumulativeTaskKnowledge.restore(genome, knowledge.toCanonicalJson());
        var goal = new CheckedSchemaTaskSolver.Goal(state -> TypedPolynomialSurfaceCost.evaluate(state.expression()), 0);
        var task = new CheckedSchemaTaskSolver.Task(parse("(1/3)*a+(2/3)*a"), List.of(), goal, BUDGET);
        assertTrue(restored.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
    }

    @Test
    void suppliedFalseRulesAndMalformedSymbolsCannotAcquireAuthority() {
        var genome = TraceStrategyTransferExample.inventory();
        var invalid = genome.withRewrites(List.of(genome.rewrites().getFirst().withPatterns("?A+?B", "?A")));
        var result = TypedLearnedMoveInventory.primitives(invalid)
            .newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_SIMPLIFICATION_V1)
            .simplify(parse("x+y"), List.of(), BUDGET);
        new ExactPolynomialAnalysis().requireEquivalent("x+y", ExpressionFormatter.format(result.incumbent().expression()));
        assertTrue(result.search().events().stream().anyMatch(event -> event.decision() == MoveSearch.Decision.PROOF_REJECTED));
        assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> solver().simplify(new de.regelsuche.ast.VariableExpr("x+y"), List.of(), BUDGET));
    }

    @Test
    void nativeCandidatesRemainSourceBoundAndFailedFactorizationIsCharged() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.POLYNOMIAL_CANONICAL);
        var codec = new de.regelsuche.search.program.CompiledAstReplayCodec();
        var source = parse("(1/3)*x+(2/3)*x");
        var batch = provider.candidates(de.regelsuche.search.moves.MoveState.root(codec.encodeExpression(source)),
            de.regelsuche.search.moves.MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty());
        var context = de.regelsuche.search.moves.TypedMoveSearch.Context.sourceOnly(List.of(),
            de.regelsuche.search.moves.MoveContext.Phase.FROZEN_EVALUATION);
        var actual = new de.regelsuche.search.moves.TypedMoveSearch.State(source, 0, 0, "", List.of(), java.util.Set.of(), 0);
        var foreign = new de.regelsuche.search.moves.TypedMoveSearch.State(parse("x+1"), 0, 0, "", List.of(), java.util.Set.of(), 0);
        assertTrue(provider.verifier().verify(actual, batch.moves().getFirst(), context).accepted());
        var rejected = provider.verifier().verify(foreign, batch.moves().getFirst(), context);
        assertFalse(rejected.accepted());
        assertTrue(rejected.work() > 0);
        var noFactor = StructuralConstructionPrograms.generate(
            StructuralConstructionPrograms.Operation.POLYNOMIAL_UNIVARIATE_FACTOR, parse("x*x+1"));
        assertTrue(noFactor.candidates().isEmpty());
        assertTrue(noFactor.work() > 0);
    }

    @Test
    void callerCanForbidExactTheoryWork() {
        var noTheory = new MoveSearch.Budget(8, 8, 0, 32, 1_000_000, 64);
        var result = solver().simplify(parse("(1/3)*a+(2/3)*a"), List.of(), noTheory);
        assertTrue(result.outputScore() > 0);
        assertTrue(result.witness().stream().noneMatch(edge -> edge.move().transformation().exactTheoryStepCount() > 0));
    }

    @Test
    void negativeUnitFractionsDoNotKeepAnUnnecessaryMultiplication() {
        var result = solver().simplify(parse("-(1/7)*z"), List.of(), BUDGET);
        new ExactPolynomialAnalysis().requireEquivalent("-(1/7)*z", ExpressionFormatter.format(result.incumbent().expression()));
        assertTrue(result.outputScore() <= 2, ExpressionFormatter.format(result.incumbent().expression()));
    }

    private static CheckedSchemaTaskSolver solver() {
        return TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory())
            .newTaskSolverWithProfile(TaskConstructionProfile.POLYNOMIAL_SIMPLIFICATION_V1);
    }

    private static de.regelsuche.ast.Expr parse(String source) {
        return new ExpressionParser().parseExactTerm(source).expression();
    }
}
