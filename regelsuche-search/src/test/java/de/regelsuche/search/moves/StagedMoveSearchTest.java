package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.symbol.SymbolId;
import java.util.UUID;
import static de.regelsuche.ast.BinaryOperator.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class StagedMoveSearchTest {

    @Test void typedFrontierPreservesGroupingAcrossActualPrimitiveSearchSteps() {
        var parser = new ExpressionParser();
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("typed-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a),
            new PatternRewriteRule("typed-square", PatternExpr.op(MUL, a, a), PatternExpr.op(POW, a, PatternExpr.num(2)))
        ), 100, 100);
        var descriptor = new MoveProvider.Descriptor("typed-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "typed-fixture");
        var provider = TypedMoveSearch.primitiveProvider(descriptor, transport);
        Expr source = parser.parseTerm("((a+(b+c))+0)*((a+(b+c))+0)");
        Expr target = parser.parseTerm("(a+(b+c))^2");

        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(provider), TypedMoveSearch.Policy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(4, 4, 0, 100, 10_000)));

        assertTrue(result.reached());
        assertEquals(target, result.witness().getLast().target().expression());
        assertTrue(result.reachedStates().stream().anyMatch(state -> state.expression().equals(target)));
        assertEquals(parser.parseTerm("a+(b+c)"),
            ((de.regelsuche.ast.BinaryExpr) target).left());
    }


    @Test void typedFrontierRetainsExactRationalAsOneNumericLeaf() {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("typed-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a)
        ), 100, 100);
        var descriptor = new MoveProvider.Descriptor("typed-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "typed-fixture");
        var source = new BinaryExpr(NumberExpr.exact("1/3"), ADD, new NumberExpr(0));
        var target = NumberExpr.exact("1/3");
        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(TypedMoveSearch.primitiveProvider(descriptor, transport)),
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(transport), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 20, 1_000)));

        assertTrue(result.reached());
        assertEquals(target, result.witness().getLast().target().expression());
        assertInstanceOf(NumberExpr.class, result.witness().getLast().target().expression());
    }

    @Test void typedFrontierRetainsScopedSymbolIdentityAcrossRewrite() {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("typed-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a)
        ), 100, 100);
        var descriptor = new MoveProvider.Descriptor("typed-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "typed-fixture");
        var symbol = new SymbolId(new UUID(0, 77), 3);
        var target = VariableExpr.scoped(symbol);
        var source = new BinaryExpr(target, ADD, new NumberExpr(0));
        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(TypedMoveSearch.primitiveProvider(descriptor, transport)),
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(transport), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 20, 1_000)));

        assertTrue(result.reached());
        var reached = (VariableExpr) result.witness().getLast().target().expression();
        assertEquals(symbol, reached.symbol().orElseThrow());
    }


    @Test void typedPrimitiveReplayChargesTheCompleteRegenerationWork() {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("typed-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a)
        ), 100, 100);
        var descriptor = new MoveProvider.Descriptor("typed-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "typed-fixture");
        var source = new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(0));
        var target = new VariableExpr("x");

        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(TypedMoveSearch.primitiveProvider(descriptor, transport)),
            TypedMoveSearch.Policy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(transport), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(2, 2, 0, 20, 1_000)));

        assertTrue(result.reached());
        assertEquals(de.regelsuche.transform.TransformationWorkMetrics.flatEngine(1).totalWorkUnits(),
            result.metrics().verificationWork(), "replay must charge the same complete generation mechanics");
    }

    @Test void typedPrimitiveReplayRejectsAForgedApplicationIdentity() {
        var a = PatternExpr.var("A");
        var transport = new AstRewriteTransport(List.of(
            new PatternRewriteRule("typed-zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a)
        ), 100, 100);
        var descriptor = new MoveProvider.Descriptor("typed-primitives", "*", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "typed-fixture");
        MoveProvider genuine = TypedMoveSearch.primitiveProvider(descriptor, transport);
        MoveProvider forged = new MoveProvider() {
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = genuine.candidates(state, context);
                var moves = batch.moves().stream().map(move -> {
                    var step = move.transformation();
                    var changed = new Transformation(step.rule(), step.transformedExpression(), step.kind(),
                        step.mayIncreaseComplexity(), step.estimatedCostDelta(),
                        step.equivalencePreservingByConstruction(), "forged-application-key",
                        step.assumptions(), step.packId(), step.license(), step.primitiveRuleIds());
                    return SearchMove.from(changed, descriptor, batch.work().totalWorkUnits());
                }).toList();
                return new Batch(moves, batch.work(), batch.complete());
            }
        };
        var source = new BinaryExpr(new VariableExpr("x"), ADD, new NumberExpr(0));
        var target = new VariableExpr("x");

        var result = new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            TypedMoveSearch.Context.frozen(target), List.of(forged), TypedMoveSearch.Policy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(transport), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(2, 2, 0, 20, 1_000)));

        assertFalse(result.reached());
        assertTrue(result.encodedResult().events().stream()
            .anyMatch(event -> event.decision() == MoveSearch.Decision.PROOF_REJECTED));
    }

    @Test void contextValidationAndOptionalVerificationAreExplicit() {
        var error = assertThrows(NullPointerException.class, () -> new MoveSearch.Problem("a", null, List.of(),
            MovePriorityPolicy.INVENTORY_ORDER, GRAPH, state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(1, 1, 0, 10, 100)));
        assertEquals("context", error.getMessage());
        var repeated = provider("rule", SearchMove.SourceKind.PRIMITIVE, s -> s.equals("a")
            ? List.of(new Transformation("rule", "b"), new Transformation("rule", "b")) : List.of(), true);
        var result = search(List.of(repeated), "absent", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.FAST, 1000);
        var duplicate = result.events().stream().filter(event -> event.decision() == MoveSearch.Decision.DUPLICATE).findFirst().orElseThrow();
        assertTrue(duplicate.verificationResult().isEmpty()); assertEquals("b", duplicate.target().expression());
        assertEquals(1, duplicate.target().primitiveDepth());
        var empty = new StagedMovePicker(List.of(), MovePriorityPolicy.INVENTORY_ORDER, MoveState.root("a"), MoveContext.frozen("b"));
        assertEquals(MovePriorityPolicy.Stage.values().length, empty.nextStage());
    }
    // Synthetic graph admission is deliberately separate from the real polynomial verifier's tests.
    private static final MoveVerifier GRAPH = (state, move, context) -> new MoveVerifier.Verification(true,
        move.transformation().primitiveStepCount(), List.of("synthetic-graph-edge"), "TEST_FIXTURE");
    private static MoveProvider provider(String id, SearchMove.SourceKind kind, de.regelsuche.transform.TransformationEngine engine, boolean complete) {
        return new EngineMoveProvider(new MoveProvider.Descriptor(id, id, kind, SearchMove.ProofStrength.REPLAYABLE, List.of(),
            new SearchMove.ValueEvidence(1, 0, 1, 2, 1, true, "fixture-reference"), "fixture"), engine, complete);
    }
    private static MoveSearch.Result search(List<MoveProvider> providers, String target, MoveSearch.Scheduling scheduling,
            MoveSearch.Mode mode, long work) {
        return new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen(target), providers,
            MovePriorityPolicy.INVENTORY_ORDER, GRAPH, state -> 0, mode, scheduling, new MoveSearch.Budget(4, 4, 0, 500, work)));
    }
    @Test void successfulEarlyChildPreventsExpensiveGenerationAndStillPaysForPrimitiveProof() {
        var expensive = new AtomicInteger();
        var macro = new RewriteCandidate("shortcut", "a", "c", List.of(new Transformation("one", "b"), new Transformation("two", "c"))).toTransformation();
        var learned = provider("learned", SearchMove.SourceKind.LEARNED, expression -> expression.equals("a") ? List.of(macro) : List.of(), true);
        var solver = provider("solver", SearchMove.SourceKind.SOLVER, expression -> { expensive.incrementAndGet(); return List.of(); }, true);
        var result = search(List.of(solver, learned), "c", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.FAST, 1000);
        assertTrue(result.reached()); assertEquals(0, expensive.get());
        assertEquals(1, result.metrics().firstHitDepth()); assertEquals(2, result.metrics().firstHitPrimitiveDepth());
        assertEquals(2, result.metrics().verificationWork()); assertEquals(2, result.witness().getFirst().move().primitiveExpansion().size());
        assertTrue(search(List.of(solver, learned), "c", MoveSearch.Scheduling.EAGER_CONTROL, MoveSearch.Mode.FAST, 1000).reached());
        assertTrue(expensive.get() > 0);
    }
    @Test void largeLearnedBatchYieldsToPrimitiveLaneAndReferenceRetainsEveryReachableState() {
        var learned = provider("noise", SearchMove.SourceKind.LEARNED, expression -> expression.equals("a")
            ? IntStream.range(0, 50).mapToObj(i -> new Transformation("noise", "n" + i)).toList() : List.of(), true);
        var primitive = provider("base", SearchMove.SourceKind.PRIMITIVE, expression -> expression.equals("a")
            ? List.of(new Transformation("base", "b")) : List.of(), true);
        var picker = new StagedMovePicker(List.of(learned, primitive), MovePriorityPolicy.INVENTORY_ORDER, MoveState.root("a"), MoveContext.frozen("b"));
        assertEquals("noise", picker.next().orElseThrow().ruleId()); assertEquals("noise", picker.next().orElseThrow().ruleId());
        assertEquals("base", picker.next().orElseThrow().ruleId());
        var base = search(List.of(primitive), "absent", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100000);
        var augmented = search(List.of(learned, primitive), "absent", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100000);
        assertTrue(base.completeBoundedRelation()); assertTrue(augmented.completeBoundedRelation());
        assertTrue(augmented.reachedStates().containsAll(base.reachedStates()));
        assertEquals(51, augmented.metrics().generatedSuccessors());
    }
    @Test void budgetOverrunOpaqueProviderAndInvalidProofCannotClaimSuccessOrClosure() {
        var costly = provider("costly", SearchMove.SourceKind.LEARNED, expression -> IntStream.range(0, 100)
            .mapToObj(i -> new Transformation("costly", "b")).toList(), true);
        var overrun = search(List.of(costly), "b", MoveSearch.Scheduling.STAGED, MoveSearch.Mode.FAST, 10);
        assertFalse(overrun.reached()); assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, overrun.outcome());
        assertTrue(overrun.metrics().totalWork() > 10); assertEquals(100, overrun.metrics().unconsumedSuccessors());
        var opaque = provider("opaque", SearchMove.SourceKind.PRIMITIVE, expression -> List.of(), false);
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, search(List.of(opaque), "b", MoveSearch.Scheduling.STAGED,
            MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, 100).outcome());
        var invalid = provider("invalid", SearchMove.SourceKind.LEARNED, expression -> List.of(new Transformation("bad", "b")), true);
        var rejected = new MoveSearch().search(new MoveSearch.Problem("a", MoveContext.frozen("b"), List.of(invalid),
            MovePriorityPolicy.INVENTORY_ORDER, (s, m, c) -> new MoveVerifier.Verification(false, 9, List.of(), "REJECTED"),
            state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 0, 10, 1000)));
        assertFalse(rejected.reached()); assertFalse(rejected.completeBoundedRelation()); assertEquals(9, rejected.metrics().verificationWork());
    }
}
