package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.IncrementalMoveProvider;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;

/** Source binding, failed work and incremental admission are not authorized by a pretty formula. */
@Timeout(180)
public final class StructuralConstructionSafetyTest {
    private static void require(boolean condition, String detail) { StructuralConstructionTest.require(condition, detail); }
    private static de.regelsuche.ast.Expr parse(String text) { return new ExpressionParser().parseExactTerm(text).expression(); }

    @Test
    public void rejectsForeignSourcesAndRetainsUnsuccessfulWork() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.COMPLETE_SQUARE);
        var codec = new CompiledAstReplayCodec();
        var state = MoveState.root(codec.encodeExpression(parse("x^2+y^2")));
        var context = MoveContext.frozen("");
        var batch = provider.candidates(state, context);
        require(!batch.moves().isEmpty(), "legitimate generic construction exists");
        var typedContext = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var actual = new TypedMoveSearch.State(parse("x^2+y^2"), 0, 0, "", List.of(), Set.of(), 0);
        var foreign = new TypedMoveSearch.State(parse("x^2+2*y^2"), 0, 0, "", List.of(), Set.of(), 0);
        require(provider.verifier().verify(actual, batch.moves().getFirst(), typedContext).accepted(), "replay the genuine source-bound move");
        var rejection = provider.verifier().verify(foreign, batch.moves().getFirst(), typedContext);
        require(!rejection.accepted() && rejection.work() > 0, "a valid theorem for another source does not authorize this edge");
        var unsupported = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.EXPOSE_SQUARE, parse("sin(x)"));
        require(unsupported.candidates().isEmpty() && unsupported.work() > 0, "a rejected domain inspection is not free work");
    }
    @Test
    public void incrementalConstructionCannotHideAnAtomicOverrun() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.RESIDUAL_BALANCE);
        var source = parse("(a^2+b^2)*(c^2+d^2)");
        var state = MoveState.root(new CompiledAstReplayCodec().encodeExpression(source));
        var batch = provider.candidates(state, MoveContext.frozen(""));
        require(batch.moves().size() == 2, "both bounded residual candidates are present");
        var incremental = (IncrementalMoveProvider) provider.incremental();
        var cursor = incremental.openSession(state, MoveContext.frozen(""));
        long before = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.next(0);
        require(cursor.snapshot().work().metrics().totalWorkUnitsV2() == before, "zero allowance performs no generation");
        for (int i = 0; i < 8 && cursor.snapshot().work().metrics().totalWorkUnitsV2() < batch.work().totalWorkUnitsV2(); i++) cursor.next(1);
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        require(paid >= batch.work().totalWorkUnitsV2(), "small cursor quanta do not discard the atomic batch bill");
        cursor.close();
        require(cursor.snapshot().work().metrics().totalWorkUnitsV2() >= paid, "closing retains all generated candidate work");
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_V1);
        var task = StructuralConstructionTest.task("(a^2+b^2)*(c^2+d^2)", StructuralTaskObjective.SUM_OF_TWO_SQUARES);
        var tiny = new CheckedSchemaTaskSolver.Task(source, List.of(), task.goal(), new MoveSearch.Budget(24,12,1_000_000,128,10));
        require(!knowledge.solve(tiny, CumulativeTaskKnowledge.Strategy.BASELINE).solved(), "an insufficient end-to-end budget cannot report a construction success");
    }
    @Test
    public void unhelpfulPartialCandidatesAreNotPromoted() {
        var initial = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_V1);
        var limited = new MoveSearch.Budget(24,2,1_000_000,128,2_000_000);
        var a = StructuralConstructionTest.task("x^4+4*y^4", StructuralTaskObjective.FACTORIZATION);
        var b = StructuralConstructionTest.task("p^4+4*q^4", StructuralTaskObjective.FACTORIZATION);
        var lessons = List.of(new CheckedSchemaTaskSolver.Task(a.source(),List.of(),a.goal(),limited),
            new CheckedSchemaTaskSolver.Task(b.source(),List.of(),b.goal(),limited));
        var update = initial.learnBatch(lessons,
            List.of(StructuralConstructionTest.task("(p*q)^4+4*r^4", StructuralTaskObjective.FACTORIZATION)), 5_000_000);
        require(update.training().stream().noneMatch(CumulativeTaskKnowledge.Execution::solved), "both lessons are incomplete");
        require(update.attempts().stream().anyMatch(attempt -> attempt.status().equals("PROVED")), "partial paths still form an exact candidate");
        require(!update.promoted() && update.work().total() > 0, "correct but unhelpful candidates do not silently pass the benefit gate");
    }
    public static void main(String[] args) {
        var contract = new StructuralConstructionSafetyTest();
        contract.rejectsForeignSourcesAndRetainsUnsuccessfulWork();
        contract.incrementalConstructionCannotHideAnAtomicOverrun();
        contract.unhelpfulPartialCandidatesAreNotPromoted();
        System.out.println("PASS source-bound replay, paid failures, incremental overruns and rejected partial generation");
    }
}
