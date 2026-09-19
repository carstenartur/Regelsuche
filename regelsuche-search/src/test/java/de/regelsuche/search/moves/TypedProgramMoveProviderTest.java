package de.regelsuche.search.moves;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TypedProgramMoveProviderTest {
    @Test void missingDescriptorAssumptionsChargeOnlyRequirementRejection() {
        var descriptor = new MoveProvider.Descriptor("gated", "gated", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of("x != 0"), SearchMove.ValueEvidence.UNKNOWN, "gated-v1");
        var provider = new TypedProgramMoveProvider(descriptor, program(false));
        var batch = provider.candidates(MoveState.root(CODEC.encodeExpression(source(new VariableExpr("x")))),
            MoveContext.frozen("unused"));
        assertTrue(batch.moves().isEmpty());
        assertEquals(new TransformationWorkMetrics(0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0),
            batch.work());
    }

    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final MoveProvider.Descriptor DESCRIPTOR = new MoveProvider.Descriptor("learned-cleanup", "cleanup",
        SearchMove.SourceKind.LEARNED, SearchMove.ProofStrength.REPLAYABLE, List.of(),
        SearchMove.ValueEvidence.UNKNOWN, "cleanup-v1");

    @Test void candidateLimitRetainsGenerationAndReplayWorkInsteadOfAbortingTheSearch() {
        Expr x = new VariableExpr("x");
        Expr y = new VariableExpr("y");
        Expr source = new FunctionExpr("f", List.of(new BinaryExpr(x, ADD, new NumberExpr(0)),
            new BinaryExpr(y, ADD, new NumberExpr(0))));
        Expr goal = new FunctionExpr("f", List.of(x, y));
        var generous = repeatedZeroProgram(128);
        var saved = CODEC.encode(generous.transformMeasured(source).candidates().getFirst());
        var limited = new TypedProgramMoveProvider(DESCRIPTOR, repeatedZeroProgram(1));
        var proposal = limited.proposal(CODEC.decode(saved));
        var replay = assertDoesNotThrow(() -> limited.verifier().verify(
            new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0), proposal,
            TypedMoveSearch.Context.frozen(goal)));
        assertFalse(replay.accepted());
        assertEquals("TYPED_PROGRAM_CANDIDATE_LIMIT", replay.reason());
        assertTrue(replay.work() >= 4, "both emitted primitive candidates and their mechanical work must be charged");

        var live = assertDoesNotThrow(() -> run(source, goal, limited, limited.verifier(), List.of(), 2, 10_000));
        assertEquals(MoveSearch.Outcome.INCONCLUSIVE, live.outcome());
        assertEquals(2, live.metrics().primitiveWork());
        assertTrue(live.metrics().searchWork() > 0);
        var restored = assertDoesNotThrow(() -> run(source, goal, proposals(proposal), limited.verifier(), List.of(), 2, 10_000));
        assertFalse(restored.reached());
        assertEquals(replay.work(), restored.metrics().verificationWork());
    }

    @Test void compiledContinuationRetainsTypedIntermediateStatesAndPrimitiveDepth() {
        Expr goal = new FunctionExpr("f", List.of(VariableExpr.scoped(new SymbolId(new UUID(0, 55), 2)),
            NumberExpr.exact("-7/13"), new BinaryExpr(new VariableExpr("a"), MUL,
                new BinaryExpr(new VariableExpr("b"), MUL, new VariableExpr("c")))));
        Expr source = source(goal);
        var provider = new TypedProgramMoveProvider(DESCRIPTOR, program(false));
        var result = run(source, goal, provider, provider.verifier(), List.of(), 2, 10_000);
        assertTrue(result.reached());
        assertEquals(1, result.witness().size());
        var move = result.witness().getFirst().move();
        assertEquals(2, move.transformation().primitiveStepCount());
        assertEquals(2, result.metrics().firstHitPrimitiveDepth());
        assertEquals(new BinaryExpr(goal, MUL, new NumberExpr(1)),
            CODEC.decodeExpression(move.primitiveExpansion().getFirst().transformedExpression()));
        assertEquals(goal, result.witness().getFirst().target().expression());
        assertTrue(result.metrics().primitiveWork() >= 2);
        assertTrue(result.metrics().verificationWork() > 0);
        assertFalse(run(source, goal, provider, provider.verifier(), List.of(), 1, 10_000).reached());
    }

    @Test void persistedHistoryReentersSearchOnlyAfterFullRegisteredProgramRegeneration() {
        Expr goal = NumberExpr.exact("1/3");
        Expr source = source(goal);
        var compiled = program(false);
        var saved = CODEC.encode(compiled.transformMeasured(source).candidates().getFirst());
        var receiving = new TypedProgramMoveProvider(DESCRIPTOR, program(false));
        var proposal = receiving.proposal(CODEC.decode(saved));
        var result = run(source, goal, proposals(proposal), receiving.verifier(), List.of(), 2, 10_000);
        assertTrue(result.reached());
        assertTrue(result.metrics().verificationWork() >= compiled.transformMeasured(source).workMetrics().totalWorkUnits());
        var otherSource = source(NumberExpr.exact("2/3"));
        assertThrows(IllegalArgumentException.class,
            () -> run(otherSource, goal, proposals(proposal), receiving.verifier(), List.of(), 2, 10_000));
        assertFalse(receiving.verifier().verify(
            new TypedMoveSearch.State(otherSource, 0, 0, "", List.of(), Set.of(), 0),
            proposal, TypedMoveSearch.Context.frozen(goal)).accepted());
    }

    @Test void forgedStageMetadataAndIntermediateOccurrenceAreNotAuthorizedByTheSameEndpoint() {
        Expr goal = new VariableExpr("x");
        Expr source = source(goal);
        var compiled = program(false);
        var provider = new TypedProgramMoveProvider(DESCRIPTOR, compiled);
        var genuine = compiled.transformMeasured(source).candidates().getFirst();
        var forgedStage = new CompiledAstRewriteProgram.Candidate(genuine.programId(),
            List.of("other-stage", genuine.sourceIds().getLast()), genuine.steps());
        var first = genuine.steps().getFirst();
        var wrongIntermediate = new BinaryExpr(new NumberExpr(1), MUL, goal);
        var second = genuine.steps().getLast();
        var forgedOccurrence = new CompiledAstRewriteProgram.Candidate(genuine.programId(), genuine.sourceIds(), List.of(
            new AstRewriteTransport.Step(first.source(), wrongIntermediate, first.rule(), first.kind(),
                first.mayIncreaseComplexity(), first.estimatedCostDelta(), first.equivalencePreservingByConstruction(),
                first.assumptions(), first.packId(), first.license()),
            new AstRewriteTransport.Step(wrongIntermediate, second.target(), second.rule(), second.kind(),
                second.mayIncreaseComplexity(), second.estimatedCostDelta(), second.equivalencePreservingByConstruction(),
                second.assumptions(), second.packId(), second.license())));
        for (var forged : List.of(forgedStage, forgedOccurrence)) {
            var result = run(source, goal, proposals(provider.proposal(forged)), provider.verifier(), List.of(), 2, 10_000);
            assertFalse(result.reached());
            assertEquals(MoveSearch.Decision.PROOF_REJECTED, result.events().getFirst().decision());
            assertTrue(result.metrics().verificationWork() > 0);
        }
    }

    @Test void assumptionsAreRequiredForBothSearchAndDirectVerification() {
        Expr goal = new VariableExpr("x");
        Expr source = source(goal);
        var compiled = program(true);
        var provider = new TypedProgramMoveProvider(DESCRIPTOR, compiled);
        assertFalse(run(source, goal, provider, provider.verifier(), List.of(), 2, 10_000).reached());
        assertTrue(run(source, goal, provider, provider.verifier(), List.of("x != 0"), 2, 10_000).reached());
        var move = provider.proposal(compiled.transformMeasured(source).candidates().getFirst());
        assertFalse(provider.verifier().verify(new TypedMoveSearch.State(source, 0, 0, "", List.of(), Set.of(), 0),
            move, TypedMoveSearch.Context.frozen(goal)).accepted());
    }

    @Test void exhaustedRegenerationCannotProduceABudgetRespectingSuccess() {
        Expr goal = NumberExpr.exact("1/3");
        Expr source = source(goal);
        var compiled = program(false);
        var provider = new TypedProgramMoveProvider(DESCRIPTOR, compiled);
        var proposal = provider.proposal(compiled.transformMeasured(source).candidates().getFirst());
        var ample = run(source, goal, proposals(proposal), provider.verifier(), List.of(), 2, 10_000);
        assertTrue(ample.reached());
        var bounded = run(source, goal, proposals(proposal), provider.verifier(), List.of(), 2,
            ample.metrics().totalWork() - 3);
        assertEquals(MoveSearch.Outcome.WORK_EXHAUSTED, bounded.outcome());
        assertFalse(bounded.reached());
        assertTrue(bounded.metrics().verificationWork() > 0);
        assertFalse(bounded.completeBoundedRelation());
    }

    private static Expr source(Expr goal) {
        return new BinaryExpr(new BinaryExpr(goal, MUL, new NumberExpr(1)), ADD, new NumberExpr(0));
    }

    private static CompiledAstRewriteProgram program(boolean conditional) {
        var a = PatternExpr.var("A");
        var zero = new PatternRewriteRule("zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a) {
            @Override public List<Assumption> assumptions(Expr input) {
                return conditional ? List.of(Assumption.nonZero("x")) : List.of();
            }
        };
        var one = new PatternRewriteRule("one", PatternExpr.op(MUL, a, PatternExpr.num(1)), a);
        return new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("cleanup"), List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("zero-stage"),
                new PreparedAstRewriteTransformationEngine(List.of(zero), 64, 128)),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("one-stage"),
                new PreparedAstRewriteTransformationEngine(List.of(one), 64, 128)))), 128).compileAst();
    }

    private static CompiledAstRewriteProgram repeatedZeroProgram(int maximumCandidates) {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule("zero", PatternExpr.op(ADD, a, PatternExpr.num(0)), a);
        var engine = new PreparedAstRewriteTransformationEngine(List.of(rule), 64, 128);
        return new CompiledLinearRewriteEngine(new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named("two-zeros"), List.of(
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("first-zero"), engine),
            new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("second-zero"), engine))), maximumCandidates).compileAst();
    }

    private static TypedMoveSearch.TypedProvider proposals(SearchMove move) {
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return DESCRIPTOR; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                return new Batch(List.of(move), TransformationWorkMetrics.flatEngine(1), false);
            }
        };
    }

    private static TypedMoveSearch.Result run(Expr source, Expr target, TypedMoveSearch.TypedProvider provider,
            TypedMoveSearch.Verifier verifier, List<String> assumptions, int primitiveDepth, long work) {
        return new TypedMoveSearch().search(new TypedMoveSearch.Problem(source,
            new TypedMoveSearch.Context(target, assumptions, MoveContext.Phase.FROZEN_EVALUATION), List.of(provider),
            TypedMoveSearch.Policy.INVENTORY_ORDER, verifier, state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(primitiveDepth, 1, 0, 10, work)));
    }
}
