package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(180)
class StructuralQuarticConstructionTest {
    private static final String QUARTIC = "x^4+4*x^3*y+6*x^2*y^2+7*x*y^3+2*y^4";
    private static de.regelsuche.ast.Expr parse(String expression) {
        return new ExpressionParser().parseExactTerm(expression).expression();
    }

    @Test
    void constructsAndReplaysGeneralCoefficientFactorization() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.BINARY_QUARTIC);
        var codec = new CompiledAstReplayCodec();
        var batch = provider.candidates(MoveState.root(codec.encodeExpression(parse(QUARTIC))), MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty(), "the coefficient solver supplies a nontrivial product");
        assertTrue(batch.work().totalWorkUnitsV2() > 0);
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var state = new TypedMoveSearch.State(parse(QUARTIC), 0, 0, "", List.of(), Set.of(), 0);
        var changed = new TypedMoveSearch.State(parse("x^4+4*x^3*y+6*x^2*y^2+7*x*y^3+3*y^4"),
            0, 0, "", List.of(), Set.of(), 0);
        assertTrue(provider.verifier().verify(state, batch.moves().getFirst(), context).accepted());
        var rejected = provider.verifier().verify(changed, batch.moves().getFirst(), context);
        assertFalse(rejected.accepted());
        assertTrue(rejected.work() > 0);
    }

    @Test
    void handlesUnitAndDeclinesUnsupportedOrUnresolvedInputs() {
        var op = StructuralConstructionPrograms.Operation.BINARY_QUARTIC;
        var exact = new ExactPolynomialAnalysis();
        String withUnit = "4*x^4+12*x^2*y^2+8*y^4";
        var unit = StructuralConstructionPrograms.generate(op, parse(withUnit));
        assertFalse(unit.candidates().isEmpty());
        for (String candidate : unit.candidates()) exact.requireEquivalent(withUnit, candidate);
        String anotherFamily = "2*x^4+3*x^3*y+5*x^2*y^2-x*y^3+3*y^4";
        var second = StructuralConstructionPrograms.generate(op, parse(anotherFamily));
        assertFalse(second.candidates().isEmpty());
        for (String candidate : second.candidates()) exact.requireEquivalent(anotherFamily, candidate);
        for (String input : List.of("x^4+x^2*y^2+2*y^4", "x^4+x^3*y+x^2*y^2", "x^4+y^4+z^4", "x^4+1/2*x^2*y^2+y^4")) {
            var report = StructuralConstructionPrograms.generate(op, parse(input));
            assertTrue(report.candidates().isEmpty(), input);
            assertTrue(report.work() > 0, input);
        }
    }

    @Test
    void v2ReachesNewFactorizationAndPersistsItsCapability() {
        var genome = TraceStrategyTransferExample.inventory();
        var v1 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1);
        var v2 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2);
        var budget = new MoveSearch.Budget(24, 1, 1_000_000, 128, 2_000_000);
        var task = new CheckedSchemaTaskSolver.Task(parse(QUARTIC), List.of(),
            StructuralTaskObjective.FACTORIZATION.goal(), budget);
        assertFalse(v1.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        var solution = v2.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(solution.solved());
        assertTrue(solution.solution().orElseThrow().evidence().replayWork() > 0);
        var restored = CumulativeTaskKnowledge.restore(genome, v2.toCanonicalJson());
        assertEquals(TaskConstructionProfile.STRUCTURAL_V2, restored.constructionProfile());
        assertTrue(restored.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        var tiny = new CheckedSchemaTaskSolver.Task(task.source(), List.of(), task.goal(),
            new MoveSearch.Budget(24, 1, 1_000_000, 128, 10));
        assertFalse(v2.solve(tiny, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
    }

    @Test
    void verifiedQuarticResultCanBeLearnedAndReusedAfterRestore() {
        var genome = TraceStrategyTransferExample.inventory();
        var initial = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2);
        var lessons = List.of(StructuralConstructionTest.task(QUARTIC, StructuralTaskObjective.FACTORIZATION),
            StructuralConstructionTest.task("a^4+4*a^3*b+6*a^2*b^2+7*a*b^3+2*b^4", StructuralTaskObjective.FACTORIZATION));
        var validation = List.of(StructuralConstructionTest.task(
            "(p+1)^4+4*(p+1)^3*q+6*(p+1)^2*q^2+7*(p+1)*q^3+2*q^4", StructuralTaskObjective.FACTORIZATION));
        var update = initial.learnBatch(lessons, validation, 5_000_000);
        assertTrue(update.promoted(), () -> update.reason() + " " + update.attempts());
        var restored = CumulativeTaskKnowledge.restore(genome, update.next().toCanonicalJson());
        var transfer = StructuralConstructionTest.task(
            "(u+2)^4+4*(u+2)^3*v+6*(u+2)^2*v^2+7*(u+2)*v^3+2*v^4", StructuralTaskObjective.FACTORIZATION);
        var before = initial.solve(transfer, CumulativeTaskKnowledge.Strategy.BASELINE);
        var result = restored.solve(transfer, CumulativeTaskKnowledge.Strategy.AUTOMATIC);
        assertTrue(result.solved());
        assertTrue(result.solution().orElseThrow().learnedApplications() > 0);
        System.out.println("QUARTIC_TRANSFER baselineSolved=" + before.solved() + " baselineWork=" + before.totalWork()
            + " learnedWork=" + result.totalWork() + " acquisition=" + update.next().acquisitionWork()
            + " restore=" + restored.sessionSetupWork());
    }

    @Test
    void expandedSquareSumCanUseTheSameResidualComposition() {
        var genome = TraceStrategyTransferExample.inventory();
        var v1 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V1);
        var v2 = CumulativeTaskKnowledge.empty(genome, TaskConstructionProfile.STRUCTURAL_V2);
        String expanded = "(a*c)^2+(a*d)^2+(b*c)^2+(b*d)^2";
        var task = new CheckedSchemaTaskSolver.Task(parse(expanded), List.of(),
            StructuralTaskObjective.SUM_OF_TWO_SQUARES.goal(),
            new MoveSearch.Budget(24, 1, 1_000_000, 128, 2_000_000));
        assertFalse(v1.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
        var result = v2.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(result.solved(), "the source need not retain its original product syntax");
        assertTrue(result.solution().orElseThrow().evidence().replayWork() > 0);
    }

    @Test
    void incrementalQuarticBatchKeepsAtomicCostsAndHonorsTheTaskBudget() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.BINARY_QUARTIC);
        var state = MoveState.root(new CompiledAstReplayCodec().encodeExpression(parse(QUARTIC)));
        var batch = provider.candidates(state, MoveContext.frozen(""));
        assertFalse(batch.moves().isEmpty());
        var cursor = ((de.regelsuche.search.moves.IncrementalMoveProvider) provider.incremental())
            .openSession(state, MoveContext.frozen(""));
        long before = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.next(0);
        assertEquals(before, cursor.snapshot().work().metrics().totalWorkUnitsV2());
        for (int i = 0; i < 8 && cursor.snapshot().work().metrics().totalWorkUnitsV2() < batch.work().totalWorkUnitsV2(); i++) cursor.next(1);
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        assertTrue(paid >= batch.work().totalWorkUnitsV2());
        cursor.close();
        assertTrue(cursor.snapshot().work().metrics().totalWorkUnitsV2() >= paid);

        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_V2);
        var task = StructuralConstructionTest.task(QUARTIC, StructuralTaskObjective.FACTORIZATION);
        var success = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        assertTrue(success.solved());
        var shortTask = new CheckedSchemaTaskSolver.Task(task.source(), List.of(), task.goal(),
            new MoveSearch.Budget(24, 12, 1_000_000, 128, success.totalWork() - 1));
        assertFalse(knowledge.solve(shortTask, CumulativeTaskKnowledge.Strategy.BASELINE).solved());
    }

    @Test
    void quarticProjectionBillsExpensiveCancelledExpansion() {
        String prefix = "(p+q+r+s)^11-(p+q+r+s)^11+";
        var report = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.BINARY_QUARTIC,
            parse(prefix + QUARTIC));
        assertFalse(report.candidates().isEmpty());
        assertTrue(report.work() > 9_370, "the cancelled expansions still incur their term-product work");
        var aborted = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.BINARY_QUARTIC,
            parse("(p+q+r+s)^32+" + QUARTIC));
        assertTrue(aborted.candidates().isEmpty());
        assertTrue(aborted.work() > 0, "an aborted projection cannot discard earlier work");
    }

    @Test
    void exhaustedProjectionBudgetDoesNotStartMoreCoefficientSearch() {
        var one = new java.util.concurrent.atomic.AtomicLong();
        var two = new java.util.concurrent.atomic.AtomicLong();
        assertTrue(QuarticConstruction.generate(QUARTIC, 1, one::addAndGet).isEmpty());
        assertTrue(QuarticConstruction.generate(QUARTIC, 2, two::addAndGet).isEmpty());
        assertTrue(one.get() > 2, "the exact projection already consumed both allowances");
        assertEquals(one.get(), two.get(), "no additional coefficient search should run after that projection");
    }
}
