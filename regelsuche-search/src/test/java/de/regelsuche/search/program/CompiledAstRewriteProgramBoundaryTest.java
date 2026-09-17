package de.regelsuche.search.program;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class CompiledAstRewriteProgramBoundaryTest {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final RewriteRule ZERO = new PatternRewriteRule("zero", PatternExpr.op(ADD, A, PatternExpr.num(0)), A);
    private static final RewriteRule SQUARE = new PatternRewriteRule("square", PatternExpr.op(MUL, A, A),
        PatternExpr.op(POW, A, PatternExpr.num(2)));
    private final ExpressionParser parser = new ExpressionParser();

    private static RewriteProgram source(String id, int growth, int limit, RewriteRule... rules) {
        return RewritePrograms.source(id, new PreparedAstRewriteTransformationEngine(List.of(rules), growth, limit));
    }
    private static CompiledAstRewriteProgram compile(int limit, RewriteProgram... stages) {
        return new CompiledLinearRewriteEngine(RewritePrograms.sequence("pipeline", stages), limit).compileAst();
    }

    @Test void convergentEndpointsRetainTheirDifferentPrimitiveHistories() {
        var engine = compile(128, source("first", 64, 128, ZERO), source("second", 64, 128, ZERO));
        Expr input = parser.parseTerm("f(x+0,y+0)");
        var batch = engine.transformMeasured(input);
        assertEquals(2, batch.candidates().size());
        assertEquals(List.of(parser.parseTerm("f(x,y)"), parser.parseTerm("f(x,y)")),
            batch.candidates().stream().map(CompiledAstRewriteProgram.Candidate::target).toList());
        assertNotEquals(batch.candidates().get(0).states().get(1), batch.candidates().get(1).states().get(1));
        assertEquals(3, batch.workMetrics().sourceInvocations());
        assertEquals(4, batch.workMetrics().sourceCandidates());
        assertEquals(2, batch.workMetrics().composedCandidates());
        assertEquals(0, batch.workMetrics().duplicateCandidatesDropped());
        assertEquals(10, batch.workMetrics().totalWorkUnits());
        assertEquals(4, batch.workMetrics().candidateWork().primitiveRewrites());
        for (var candidate : batch.candidates()) {
            var replay = engine.replay(input, candidate);
            assertEquals(candidate.target(), replay.target());
            assertEquals(batch.workMetrics(), replay.workMetrics());
        }
    }

    @Test void aFailedTailReturnsNoPartialContinuationButKeepsItsWork() {
        var engine = compile(128, source("first", 64, 128, ZERO), source("square", 64, 128, SQUARE));
        var result = engine.transformMeasured(parser.parseTerm("x+0"));
        assertTrue(result.candidates().isEmpty());
        assertEquals(2, result.workMetrics().sourceInvocations());
        assertEquals(1, result.workMetrics().sourceCandidates());
        assertEquals(0, result.workMetrics().composedCandidates());
        assertEquals(4, result.workMetrics().totalWorkUnits());
    }

    @Test void sourceGrowthAndCandidateLimitsAreNotReplacedWithDefaults() {
        var grow = new PatternRewriteRule("grow", A, PatternExpr.op(MUL, A, A));
        var boundedGrowth = compile(128, source("grow", 0, 128, grow), source("square", 64, 128, SQUARE));
        var result = boundedGrowth.transformMeasured(parser.parseTerm("x"));
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, result.workMetrics().sourceInvocations());
        var boundedSource = compile(128, source("one-only", 64, 1, ZERO));
        assertEquals(List.of(parser.parseTerm("f(x,y+0)")), boundedSource.transformMeasured(parser.parseTerm("f(x+0,y+0)"))
            .candidates().stream().map(CompiledAstRewriteProgram.Candidate::target).toList());
    }

    @Test void compiledCandidateOverflowThrowsInsteadOfReturningAPartialBatch() {
        var first = source("first", 64, 128, ZERO);
        var second = source("second", 64, 128, ZERO);
        assertThrows(IllegalArgumentException.class, () -> compile(1, first, second).transformMeasured(parser.parseTerm("f(x+0,y+0)")));
        assertThrows(IllegalArgumentException.class, () -> compile(0, first));
        assertThrows(IllegalArgumentException.class, () -> compile(129, first));
    }

    @Test void unsupportedSourceIsRejectedBeforeEarlierSourcesCanExecute() {
        var calls = new AtomicInteger();
        var observed = new PatternRewriteRule("observed", A, PatternExpr.num(1)) {
            @Override public Expr apply(Expr input) { calls.incrementAndGet(); return super.apply(input); }
        };
        var first = source("first", 64, 128, observed);
        var reference = RewritePrograms.source("reference", new AstRewriteTransformationEngine(List.of(ZERO)));
        var legacy = new CompiledLinearRewriteEngine(RewritePrograms.sequence("mixed", first, reference), 128);
        assertThrows(IllegalArgumentException.class, legacy::compileAst);
        assertEquals(0, calls.get());
        assertDoesNotThrow(() -> new CompiledLinearRewriteEngine(reference, 128).transformMeasured("x+0"));
    }

    @Test void replayChecksEveryConditionAndAllSourceIdentities() {
        var divide = new PatternRewriteRule("divide", PatternExpr.op(DIV, A, A), PatternExpr.num(1)) {
            @Override public List<Assumption> assumptions(Expr input) {
                return List.of(Assumption.nonZero(ExpressionFormatter.format(((BinaryExpr) input).right())));
            }
        };
        var engine = compile(128, source("first", 64, 128, divide), source("second", 64, 128, divide));
        Expr input = parser.parseTerm("f(x/x,y/y)");
        var original = engine.transformMeasured(input).candidates().getFirst();
        assertEquals(List.of("x != 0", "y != 0"), original.assumptions());
        assertEquals(original.target(), engine.replay(input, original).target());
        var last = original.steps().getLast();
        var altered = new AstRewriteTransport.Step(last.source(), last.target(), last.rule(), last.kind(),
            last.mayIncreaseComplexity(), last.estimatedCostDelta(), last.equivalencePreservingByConstruction(),
            List.of("x != 0"), last.packId(), last.license());
        var steps = List.of(original.steps().getFirst(), altered);
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate(original.programId(), original.sourceIds(), steps)));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate("another-program", original.sourceIds(), original.steps())));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate(original.programId(), List.of("second", "first"), original.steps())));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(parser.parseTerm("f(y/y,x/x)"), original));
    }

    @Test void replayRechecksTargetAndMetadataUnderReceivingRules() {
        var engine = compile(128, source("square", 64, 128, SQUARE));
        Expr input = parser.parseTerm("x*x");
        var candidate = engine.transformMeasured(input).candidates().getFirst();
        var step = candidate.steps().getFirst();
        var wrongCost = new AstRewriteTransport.Step(step.source(), step.target(), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta()+1, step.equivalencePreservingByConstruction(),
            step.assumptions(), step.packId(), step.license());
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate(candidate.programId(), candidate.sourceIds(), List.of(wrongCost))));
        var other = compile(128, source("square", 64, 128, ZERO));
        assertThrows(IllegalArgumentException.class, () -> other.replay(input, candidate));
    }

    @Test void candidateHistoriesAreImmutableAndMustBeConnected() {
        var engine = compile(128, source("first", 64, 128, ZERO), source("second", 64, 128, ZERO));
        var batch = engine.transformMeasured(parser.parseTerm("(x+0)+0"));
        var candidate = batch.candidates().getFirst();
        var steps = new ArrayList<>(candidate.steps());
        var ids = new ArrayList<>(candidate.sourceIds());
        var copied = new CompiledAstRewriteProgram.Candidate(candidate.programId(), ids, steps);
        steps.clear(); ids.clear();
        assertEquals(candidate, copied);
        assertThrows(UnsupportedOperationException.class, () -> batch.candidates().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.steps().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.states().clear());
        assertThrows(IllegalArgumentException.class, () -> new CompiledAstRewriteProgram.Candidate("p", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CompiledAstRewriteProgram.Candidate("p", candidate.sourceIds(),
            List.of(candidate.steps().getLast(), candidate.steps().getFirst())));
    }

    @Test void scopedAliasesSurviveWithoutEquatingDifferentSymbols() {
        var scope = new SymbolScope(new UUID(0, 53));
        scope.alias("alias", scope.resolve("x"));
        var engine = compile(128, source("zero", 64, 128, ZERO), source("square", 64, 128, SQUARE));
        Expr input = SymbolicExpression.parse("(x+0)*alias", scope).expression();
        var candidate = engine.transformMeasured(input).candidates().getFirst();
        assertEquals(SymbolicExpression.parse("x^2", scope).expression(), candidate.target());
        var other = new SymbolScope(new UUID(0, 54));
        Expr mixed = new BinaryExpr(SymbolicExpression.parse("x+0", scope).expression(), MUL,
            SymbolicExpression.parse("x", other).expression());
        assertTrue(engine.transformMeasured(mixed).candidates().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> engine.replay(mixed, candidate));
    }

    @Test void theLegacyCompilerStillProducesExactlyTheInterpreterResult() {
        var plan = RewritePrograms.sequence("old-control", source("zero", 64, 128, ZERO), source("square", 64, 128, SQUARE));
        var legacy = new CompiledLinearRewriteEngine(plan, 128);
        var interpreter = new ProgrammedTransformationEngine(plan);
        var before = legacy.transformMeasured("(x+0)*x");
        assertEquals(interpreter.transform("(x+0)*x"), before.transformations());
        legacy.compileAst().transformMeasured(parser.parseTerm("(x+0)*x"));
        assertEquals(before, legacy.transformMeasured("(x+0)*x"));
        assertEquals("regelsuche.compiled-linear-rewrite/v1", CompiledLinearRewriteEngine.REVISION);
        assertEquals("regelsuche.compiled-linear-rewrite/ast-v1", CompiledAstRewriteProgram.REVISION);
    }
}
