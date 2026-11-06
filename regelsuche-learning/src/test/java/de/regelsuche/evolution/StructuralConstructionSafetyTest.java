package de.regelsuche.evolution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.search.moves.IncrementalMoveProvider;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    public void auxiliaryConstructionStillBindsSourceAndCannotHideBudgetOverrun() {
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.AUXILIARY_COMPLEMENT);
        var codec = new CompiledAstReplayCodec();
        var source = parse("x^2+y^2");
        var state = MoveState.root(codec.encodeExpression(source));
        var batch = provider.candidates(state, MoveContext.frozen(""));
        require(!batch.moves().isEmpty(), "the new provider emits checked candidates");
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var actual = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        var foreign = new TypedMoveSearch.State(parse("x^2+2*y^2"), 0, 0, "", List.of(), Set.of(), 0);
        require(provider.verifier().verify(actual, batch.moves().getFirst(), context).accepted(), "matching source replays");
        var rejected = provider.verifier().verify(foreign, batch.moves().getFirst(), context);
        require(!rejected.accepted() && rejected.work() > 0, "foreign source cannot reuse the construction");
        var cursor = ((IncrementalMoveProvider) provider.incremental()).openSession(state, MoveContext.frozen(""));
        for (int i = 0; i < 8 && cursor.snapshot().work().metrics().totalWorkUnitsV2() < batch.work().totalWorkUnitsV2(); i++) {
            cursor.next(1);
        }
        long charged = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.close();
        require(charged >= batch.work().totalWorkUnitsV2(), "an atomic load retains all generated work");
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), profile);
        var tiny = new CheckedSchemaTaskSolver.Task(source, List.of(), StructuralTaskObjective.FACTORIZATION.goal(),
            new MoveSearch.Budget(8, 4, 1_000_000, 32, 1));
        require(!knowledge.solve(tiny, CumulativeTaskKnowledge.Strategy.BASELINE).solved(), "an exhausted budget cannot report success");
    }

    @Test
    public void squareRecognitionChargesProductWorkBeforeCancelledTermsDisappear() {
        String source = "((x+y+z+w)^10-(x+y+z+w)^10+x)*(x^2+2*x*y+y^2)";
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.RECOGNIZE_SQUARE);
        var codec = new CompiledAstReplayCodec();
        var batch = provider.candidates(MoveState.root(codec.encodeExpression(parse(source))), MoveContext.frozen(""));
        require(!batch.moves().isEmpty(), "a recognized square survives within the bounded input");
        String target = ExpressionFormatter.format(codec.decodeExpression(batch.moves().getFirst().transformation().transformedExpression()));
        long[] independentWork = {0};
        ExactPolynomialAnalysis.withProductWork(units -> independentWork[0] += units).requireEquivalent(source, target);
        require(batch.work().totalWorkUnitsV2() >= independentWork[0],
            "candidate verification must charge the actual exact source and target projections");
    }

    @Test
    public void rationalSquareStepsAreSourceBoundAndChargeAtomicWork() {
        var codec = new CompiledAstReplayCodec();
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        for (var operation : List.of(StructuralConstructionPrograms.Operation.RATIONAL_EXPOSE_SQUARE,
                StructuralConstructionPrograms.Operation.RECOGNIZE_RATIONAL_SQUARE,
                StructuralConstructionPrograms.Operation.RECOGNIZE_METERED_SQUARE)) {
            String text = switch (operation) {
                case RATIONAL_EXPOSE_SQUARE -> "(4/9)*y*y";
                case RECOGNIZE_RATIONAL_SQUARE -> "x^2+(4/3)*x*y+(2/3*y)^2";
                case RECOGNIZE_METERED_SQUARE -> "x^2+2*x*y+y^2";
                default -> throw new AssertionError("unexpected operation");
            };
            var source = parse(text);
            var provider = new StructuralConstructionProvider(operation);
            var state = MoveState.root(codec.encodeExpression(source));
            var batch = provider.candidates(state, MoveContext.frozen(""));
            require(!batch.moves().isEmpty() && batch.work().totalWorkUnitsV2() > 0,
                "a useful candidate and its generation work must be retained");
            var actual = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
            var foreign = new TypedMoveSearch.State(parse("x^2+y^2"), 0, 0, "", List.of(), Set.of(), 0);
            require(provider.verifier().verify(actual, batch.moves().getFirst(), context).accepted(), "source-bound replay");
            var rejection = provider.verifier().verify(foreign, batch.moves().getFirst(), context);
            require(!rejection.accepted() && rejection.work() > 0, "a different source must be rejected and charged");
            var cursor = ((IncrementalMoveProvider) provider.incremental()).openSession(state, MoveContext.frozen(""));
            for (int i = 0; i < 8 && cursor.snapshot().work().metrics().totalWorkUnitsV2() < batch.work().totalWorkUnitsV2(); i++) {
                cursor.next(1);
            }
            long charged = cursor.snapshot().work().metrics().totalWorkUnitsV2();
            cursor.close();
            require(charged >= batch.work().totalWorkUnitsV2(), "small allowance cannot hide an atomic generation bill");
        }
        var rejected = StructuralConstructionPrograms.generate(StructuralConstructionPrograms.Operation.RATIONAL_EXPOSE_SQUARE,
            parse("(4/9)*x*x/(x-x)"));
        require(rejected.candidates().isEmpty() && rejected.work() > 0, "undefined inputs are rejected and charged");
    }

    @Test
    public void auxiliaryProvidersDoNotSprayTrivialCandidatesWhenNoUsefulStructureExists() {
        var state = MoveState.root(new CompiledAstReplayCodec().encodeExpression(parse("u^2+2")));
        var complement = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.AUXILIARY_COMPLEMENT)
            .candidates(state, MoveContext.frozen(""));
        var factor = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.AUXILIARY_FACTOR)
            .candidates(state, MoveContext.frozen(""));
        require(complement.moves().isEmpty(), "one square gives no pair-derived neutral addition");
        require(factor.moves().isEmpty(), "constant and whole-source factors are not useful splits");
        require(complement.work().totalWorkUnitsV2() > 0 && factor.work().totalWorkUnitsV2() > 0,
            "unsuccessful applicability checks still pay for their work");
    }

    @Test
    public void auxiliaryProfileChargesObjectiveProjectionBeforeCancellation() {
        String factor = "(x+y+z+w)^8-(x+y+z+w)^8+x";
        var task = new CheckedSchemaTaskSolver.Task(parse("(" + factor + ")*y"), List.of(),
            StructuralTaskObjective.FACTORIZATION.goal(), new MoveSearch.Budget(8, 2, 1_000_000, 32, 100_000));
        var profile = TaskConstructionProfile.STRUCTURAL_AUX_V1;
        var result = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), profile)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        require(result.solved(), "the input is an already valid product");
        long[] independentWork = {0};
        ExactPolynomialAnalysis.withProductWork(units -> independentWork[0] += units).normalForm(factor);
        require(result.solution().orElseThrow().evidence().search().metrics().totalWork() >= independentWork[0],
            "the structural objective cannot hide its own polynomial product work");
    }

    @Test
    public void squareProfileRejectsAnUndefinedSourceBeforeItCanBeCancelled() {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var task = StructuralConstructionTest.task("(x^2-(1/y))+(1/y)", StructuralTaskObjective.PERFECT_SQUARE);
        var failure = assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE));
        require(failure.inspectionWork() > 0, "rejected source inspection must retain its declared work");
        require(!failure.accountingComplete(), "only the observed prefix is claimed");
        var wrapped = new CheckedSchemaTaskSolver.Task(task.source(), List.of(),
            new CheckedSchemaTaskSolver.Goal(StructuralTaskObjective.PERFECT_SQUARE::evaluate, 8), task.budget());
        assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> knowledge.solve(wrapped, CumulativeTaskKnowledge.Strategy.BASELINE));
    }

    @Test
    public void squareProfileDoesNotTreatArbitraryGenomeReplayAsMathematicalProof() {
        var source = TraceStrategyTransferExample.inventory();
        var invalid = source.withRewrites(List.of(source.rewrites().getFirst().withPatterns("?A+?B", "?A")));
        var knowledge = CumulativeTaskKnowledge.empty(invalid, TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var task = StructuralConstructionTest.task("x^2+1", StructuralTaskObjective.PERFECT_SQUARE);
        var result = knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        require(!result.solved(), "discarding 1 cannot prove that x^2+1 is a square");
        require(result.solution().orElseThrow().evidence().search().events().stream()
            .anyMatch(event -> event.decision() == MoveSearch.Decision.PROOF_REJECTED
                && event.verification() != null && event.verification().work() > 0),
            "the false primitive must be rejected with a paid exact check");
    }

    @Test
    public void squareProfileRejectsAnUndefinedIntermediateEvenIfItWouldCancelLater() {
        var source = TraceStrategyTransferExample.inventory();
        var introducing = source.rewrites().getFirst().withPatterns("(?A+0)*1", "(?A+(1/?A))-(1/?A)");
        var cancelling = source.rewrites().get(1).withPatterns("(?A+?B)-?B", "?A");
        var inventory = source.withRewrites(List.of(introducing, cancelling));
        var task = StructuralConstructionTest.task("(x^2+0)*1", StructuralTaskObjective.PERFECT_SQUARE);
        var result = CumulativeTaskKnowledge.empty(inventory)
            .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
        require(!result.solved(), "a path through 1/x^2 loses the original domain at x=0");
        require(result.solution().orElseThrow().evidence().search().events().stream()
            .anyMatch(event -> event.decision() == MoveSearch.Decision.PROOF_REJECTED
                && event.verification() != null && event.verification().work() > 0),
            "the undefined intermediate must be rejected and paid");
    }

    @Test
    public void squareProfileRejectsSymbolNamesThatWouldBeParsedAsExpressions() {
        var disguised = new de.regelsuche.ast.BinaryExpr(new de.regelsuche.ast.VariableExpr("x^2+1"),
            de.regelsuche.ast.BinaryOperator.SUB, new de.regelsuche.ast.NumberExpr(1));
        var ordinary = StructuralConstructionTest.task("x^2+1", StructuralTaskObjective.PERFECT_SQUARE);
        var task = new CheckedSchemaTaskSolver.Task(disguised, List.of(), ordinary.goal(), ordinary.budget());
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_SQUARE_V1);
        var rejected = assertThrows(CheckedSchemaTaskSolver.UnsupportedTaskException.class,
            () -> knowledge.solve(task, CumulativeTaskKnowledge.Strategy.BASELINE));
        require(rejected.inspectionWork() > 0, "an ambiguous symbol rejection is paid");
    }

    @Test
    public void checkedStepRejectsAnAmbiguousSymbolInTheTargetToo() {
        var codec = new CompiledAstReplayCodec();
        var source = parse("x^2+1");
        var disguisedTarget = new de.regelsuche.ast.VariableExpr("x^2+1");
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.COMPLETE_SQUARE);
        var transformation = new de.regelsuche.transform.Transformation("forged", codec.encodeExpression(disguisedTarget));
        var move = de.regelsuche.search.moves.SearchMove.from(transformation, provider.descriptor(), 0);
        var state = new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0);
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var result = StructuralStepVerifier.checked((s, m, c) ->
            new de.regelsuche.search.moves.MoveVerifier.Verification(true, 1, List.of("forged-replay"), "accepted"))
            .verify(state, move, context);
        require(!result.accepted() && result.work() > 1, "the target cannot disguise a symbol as an expression");
    }

    @Test
    public void checkedSquareDomainAcceptsParserIdentifiers() {
        var expression = parse("(α+β_1)^2");
        var work = new CheckedSchemaSupport.Work();
        StructuralCheckedDomain.inspect(expression, work);
        require(work.units > 0, "ordinary Unicode parser identifiers remain supported");
        var symbol = de.regelsuche.ast.VariableExpr.scoped(new de.regelsuche.symbol.SymbolId(
            java.util.UUID.fromString("16aa7cdd-b873-4f23-9c80-2011439ed883"), 1));
        StructuralCheckedDomain.inspect(symbol, work);
        require(new ExpressionParser().parseExactTerm(symbol.name()).expression().equals(symbol), "scoped symbol round-trip");
        StructuralCheckedDomain.inspect(parse("(x-x)^0"), work);
        new ExactPolynomialAnalysis().requireEquivalent("(x-x)^0", "1");
    }

    @Test
    public void exhaustedSourceInspectionDoesNotInvokeTheObjective() {
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        var goal = new CheckedSchemaTaskSolver.Goal(state -> {
            invoked.set(true);
            return new de.regelsuche.search.moves.TypedSourceOnlySearch.Score(0, 1);
        }, 0);
        var source = parse("(x+y)^2");
        var count = new CheckedSchemaSupport.Work();
        StructuralCheckedDomain.inspect(source, count);
        for (long limit : List.of(1L, count.units + 5)) {
            var task = new CheckedSchemaTaskSolver.Task(source, List.of(), goal,
                new MoveSearch.Budget(8, 4, 1_000_000, 32, limit));
            var result = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory(), TaskConstructionProfile.STRUCTURAL_SQUARE_V1)
                .solve(task, CumulativeTaskKnowledge.Strategy.BASELINE);
            require(!result.solved() && !invoked.get(), "an exhausted root assessment must not run an arbitrary objective");
            require(result.solution().orElseThrow().evidence().totalWork() > task.budget().totalWork(), "inspection is charged");
        }
    }

    @Test
    public void exactStepCheckDoesNotSwallowAnActiveObserverFailure() {
        var codec = new CompiledAstReplayCodec();
        var expression = parse("x^2+y^2");
        var provider = new StructuralConstructionProvider(StructuralConstructionPrograms.Operation.COMPLETE_SQUARE);
        var move = provider.candidates(MoveState.root(codec.encodeExpression(expression)), MoveContext.frozen("")).moves().getFirst();
        var state = new TypedMoveSearch.State(expression, 0, 0, "", List.of(), Set.of(), 0);
        var context = TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var sink = new de.regelsuche.retention.RetainedOperation.Sink() {
            @Override public void executionWork(long units) { }
            @Override public void validationWork(long units) { }
            @Override public void checkpoint() { }
            @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor visitor) { }
        };
        try (var scope = de.regelsuche.retention.RetainedOperation.open(sink)) {
            assertThrows(IllegalStateException.class,
                () -> StructuralStepVerifier.checked(provider.verifier()).verify(state, move, context));
        }
    }
    public static void main(String[] args) {
        var contract = new StructuralConstructionSafetyTest();
        contract.rejectsForeignSourcesAndRetainsUnsuccessfulWork();
        contract.incrementalConstructionCannotHideAnAtomicOverrun();
        contract.unhelpfulPartialCandidatesAreNotPromoted();
        contract.auxiliaryConstructionStillBindsSourceAndCannotHideBudgetOverrun();
        contract.squareRecognitionChargesProductWorkBeforeCancelledTermsDisappear();
        contract.auxiliaryProvidersDoNotSprayTrivialCandidatesWhenNoUsefulStructureExists();
        contract.auxiliaryProfileChargesObjectiveProjectionBeforeCancellation();
        System.out.println("PASS source-bound replay, paid failures, incremental overruns and rejected partial generation");
    }
}
