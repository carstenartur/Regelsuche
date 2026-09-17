package de.regelsuche.search.program;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
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
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class CompiledLinearRewriteEngineTest {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final RewriteRule TYPED_ZERO = new PatternRewriteRule("zero",
        PatternExpr.op(ADD, A, PatternExpr.num(0)), A);
    private static final RewriteRule TYPED_SQUARE = new PatternRewriteRule("square",
        PatternExpr.op(MUL, A, A), PatternExpr.op(POW, A, PatternExpr.num(2)));
    private final ExpressionParser parser = new ExpressionParser();

    private static RewriteProgram program() {
        var rules = AstRewriteTransformationEngine.defaultRules();
        var zero = rules.stream().filter(rule -> rule.id().equals("ast_add_zero_right")).toList();
        var one = rules.stream().filter(rule -> rule.id().equals("ast_multiply_one_right")).toList();
        return RewritePrograms.sequence("linear", RewritePrograms.source("zero", new PreparedAstRewriteTransformationEngine(zero)),
            RewritePrograms.source("one", new PreparedAstRewriteTransformationEngine(one)));
    }

    private static RewriteProgram astSource(String id, int growth, int limit, RewriteRule... rules) {
        return RewritePrograms.source(id, new PreparedAstRewriteTransformationEngine(List.of(rules), growth, limit));
    }

    private static CompiledAstRewriteProgram astProgram(int limit, RewriteProgram... stages) {
        return new CompiledLinearRewriteEngine(RewritePrograms.sequence("pipeline", stages), limit).compileAst();
    }

    private static CompiledLinearRewriteEngine astCompiled() {
        return new CompiledLinearRewriteEngine(RewritePrograms.sequence("zero-square",
            astSource("remove-zero", 64, 128, TYPED_ZERO), astSource("recognize-square", 64, 128, TYPED_SQUARE)), 128);
    }

    @Test
    void sourceOrderingMatchesInterpreterEvenWhenPrimitiveEmissionIsUnsorted() {
        var rules = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> List.of("ast_add_zero_right", "ast_multiply_one_right").contains(rule.id())).toList();
        List<TransformationEngine> engines = List.of(new AstRewriteTransformationEngine(rules),
            new PreparedAstRewriteTransformationEngine(rules));
        for (var engine : engines) {
            var source = RewritePrograms.source("unsorted", engine);
            String expression = "(x+0)*1";
            var reference = new ProgrammedTransformationEngine(source).transform(expression);
            assertNotEquals(engine.transform(expression), reference, "fixture must exercise source sorting");
            assertEquals("ast_add_zero_right", reference.getFirst().rule());
            assertParity(source, expression);
            assertParity(RewritePrograms.sequence("ordered-sequence", source, source), expression);
        }
    }

    @Test
    void kindTiesAndDuplicatePrefixIdentitiesRetainInterpreterPathsAndWork() {
        var zero = (PatternRewriteRule) AstRewriteTransformationEngine.defaultRules().stream()
            .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();
        List<RewriteRule> rules = List.of(
            new PatternRewriteRule("same", zero.source(), zero.target(), RewriteKind.SIMPLIFY, false, -1, true),
            new PatternRewriteRule("same", zero.source(), zero.target(), RewriteKind.NORMALIZE, false, -1, true));
        List<TransformationEngine> engines = List.of(new AstRewriteTransformationEngine(rules),
            new PreparedAstRewriteTransformationEngine(rules));
        var tail = ((RewriteProgram.Sequence) program()).steps().getLast();
        for (var engine : engines) {
            var source = RewritePrograms.source("kind-tie", engine);
            String expression = "(x+0)*1";
            var reference = new ProgrammedTransformationEngine(source).transform(expression);
            assertEquals(1, reference.size());
            assertEquals(RewriteKind.NORMALIZE, reference.getFirst().kind());
            assertParity(source, expression);
            var sequence = RewritePrograms.sequence("duplicate-prefixes", source, tail);
            assertEquals(2, new ProgrammedTransformationEngine(sequence).transform(expression).size());
            assertParity(sequence, expression);
        }
    }

    private static void assertParity(RewriteProgram program, String expression) {
        var reference = new ProgrammedTransformationEngine(program).transformMeasured(expression);
        var actual = new CompiledLinearRewriteEngine(program, 32).transformMeasured(expression);
        assertEquals(reference.transformations(), actual.transformations());
        assertEquals(reference.workMetrics().sourceInvocations(), actual.workMetrics().sourceInvocations());
        assertEquals(reference.workMetrics().sourceCandidates(), actual.workMetrics().sourceCandidates());
        assertEquals(reference.workMetrics().composedCandidates(), actual.workMetrics().composedCandidates());
        assertEquals(reference.workMetrics().duplicateCandidatesDropped(), actual.workMetrics().duplicateCandidatesDropped());
        assertEquals(reference.workMetrics().totalWorkUnits() - reference.workMetrics().programNodeVisits(),
            actual.workMetrics().totalWorkUnits());
    }

    @Test
    void compiledPipelinePreservesEveryInterpretedPrimitivePath() {
        for (var program : List.of(program(), ((RewriteProgram.Sequence) program()).steps().getFirst())) {
            var interpreted = new ProgrammedTransformationEngine(program);
            var compiled = new CompiledLinearRewriteEngine(program, 32);
            for (var expression : List.of("(x+0)*1+(y+0)*1", " (x+0)*1 ", "x+0", "x")) {
                var reference = interpreted.transformMeasured(expression);
                var actual = compiled.transformMeasured(expression);
                assertEquals(reference.transformations(), actual.transformations());
                assertEquals(reference.workMetrics().sourceInvocations(), actual.workMetrics().sourceInvocations());
                assertEquals(reference.workMetrics().sourceCandidates(), actual.workMetrics().sourceCandidates());
                assertEquals(reference.workMetrics().composedCandidates(), actual.workMetrics().composedCandidates());
                assertEquals(reference.workMetrics().totalWorkUnits() - reference.workMetrics().programNodeVisits(),
                    actual.workMetrics().totalWorkUnits());
                assertEquals(0, actual.workMetrics().programNodeVisits());
            }
        }
    }

    @Test
    void unsupportedTopologyAndOversizedIntermediateBatchesFailExplicitly() {
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(
            RewritePrograms.choice("choice", program(), program()), 32));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 1)
            .transformMeasured("(x+0)*1+(y+0)*1"));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(program(), 32).transformMeasured(" "));
        assertThrows(IllegalArgumentException.class, () -> new CompiledLinearRewriteEngine(RewritePrograms.source("opaque",
            de.regelsuche.transform.MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine())), 32));
    }

    private void assertAstTransfer(Expr compound) {
        Expr input = new BinaryExpr(new BinaryExpr(compound, ADD, new NumberExpr(0)), MUL, compound);
        var result = astCompiled().compileAst().transformMeasured(input);
        assertEquals(1, result.candidates().size());
        var candidate = result.candidates().getFirst();
        assertEquals(input, candidate.source());
        assertEquals(new BinaryExpr(compound, POW, new NumberExpr(2)), candidate.target());
        assertEquals(List.of(input, new BinaryExpr(compound, MUL, compound), candidate.target()), candidate.states());
        assertEquals(List.of("remove-zero", "recognize-square"), candidate.sourceIds());
        assertEquals(List.of("zero", "square"), candidate.steps().stream().map(step -> step.rule()).toList());
    }

    @Test
    void typedExecutionPreservesGroupedRationalAndFunctionProducerAsts() {
        assertAstTransfer(parser.parseTerm("a+(b+c)"));
        assertAstTransfer(NumberExpr.exact("1/3"));
        assertAstTransfer(new FunctionExpr("f", List.of(parser.parseTerm("a*(b*c)"), NumberExpr.exact("2/7"))));
    }

    @Test
    void typedConvergentHistoriesRemainDistinctAndReplayWithExactWork() {
        var engine = astProgram(128, astSource("first", 64, 128, TYPED_ZERO), astSource("second", 64, 128, TYPED_ZERO));
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

    @Test
    void typedBoundsFailedTailsAndUnsupportedSourcesFailClosed() {
        var failedTail = astProgram(128, astSource("first", 64, 128, TYPED_ZERO),
            astSource("square", 64, 128, TYPED_SQUARE)).transformMeasured(parser.parseTerm("x+0"));
        assertTrue(failedTail.candidates().isEmpty());
        assertEquals(2, failedTail.workMetrics().sourceInvocations());
        assertEquals(1, failedTail.workMetrics().sourceCandidates());
        assertEquals(4, failedTail.workMetrics().totalWorkUnits());

        var grow = new PatternRewriteRule("grow", A, PatternExpr.op(MUL, A, A));
        assertTrue(astProgram(128, astSource("grow", 0, 128, grow), astSource("square", 64, 128, TYPED_SQUARE))
            .transformMeasured(parser.parseTerm("x")).candidates().isEmpty());
        assertEquals(List.of(parser.parseTerm("f(x,y+0)")),
            astProgram(128, astSource("one-only", 64, 1, TYPED_ZERO))
                .transformMeasured(parser.parseTerm("f(x+0,y+0)")).candidates().stream()
                .map(CompiledAstRewriteProgram.Candidate::target).toList());
        var first = astSource("first", 64, 128, TYPED_ZERO);
        var second = astSource("second", 64, 128, TYPED_ZERO);
        assertThrows(IllegalArgumentException.class, () -> astProgram(1, first, second)
            .transformMeasured(parser.parseTerm("f(x+0,y+0)")));
        assertThrows(IllegalArgumentException.class, () -> astProgram(0, first));
        assertThrows(IllegalArgumentException.class, () -> astProgram(129, first));

        var duplicateIds = new CompiledLinearRewriteEngine(RewritePrograms.sequence("duplicate-source-ids",
            astSource("same", 64, 128, TYPED_ZERO), astSource("same", 64, 128, TYPED_SQUARE)), 128);
        assertThrows(IllegalArgumentException.class, duplicateIds::compileAst);

        var calls = new AtomicInteger();
        var observed = new PatternRewriteRule("observed", A, PatternExpr.num(1)) {
            @Override public Expr apply(Expr input) { calls.incrementAndGet(); return super.apply(input); }
        };
        var prepared = astSource("prepared", 64, 128, observed);
        var reference = RewritePrograms.source("reference", new AstRewriteTransformationEngine(List.of(TYPED_ZERO)));
        var mixed = new CompiledLinearRewriteEngine(RewritePrograms.sequence("mixed", prepared, reference), 128);
        assertThrows(IllegalArgumentException.class, mixed::compileAst);
        assertEquals(0, calls.get());
        assertDoesNotThrow(() -> new CompiledLinearRewriteEngine(reference, 128).transformMeasured("x+0"));
    }

    @Test
    void typedReplayChecksProgramSourceTargetMetadataAndPerStepConditions() {
        var divide = new PatternRewriteRule("divide", PatternExpr.op(DIV, A, A), PatternExpr.num(1)) {
            @Override public List<Assumption> assumptions(Expr input) {
                return List.of(Assumption.nonZero(ExpressionFormatter.format(((BinaryExpr) input).right())));
            }
        };
        var engine = astProgram(128, astSource("first", 64, 128, divide), astSource("second", 64, 128, divide));
        Expr input = parser.parseTerm("f(x/x,y/y)");
        var original = engine.transformMeasured(input).candidates().getFirst();
        assertEquals(List.of("x != 0", "y != 0"), original.assumptions());
        assertEquals(original.target(), engine.replay(input, original).target());
        var last = original.steps().getLast();
        var altered = new AstRewriteTransport.Step(last.source(), last.target(), last.rule(), last.kind(),
            last.mayIncreaseComplexity(), last.estimatedCostDelta(), last.equivalencePreservingByConstruction(),
            List.of("x != 0"), last.packId(), last.license());
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate(original.programId(), original.sourceIds(),
                List.of(original.steps().getFirst(), altered))));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate("another-program", original.sourceIds(), original.steps())));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(input,
            new CompiledAstRewriteProgram.Candidate(original.programId(), List.of("second", "first"), original.steps())));
        assertThrows(IllegalArgumentException.class, () -> engine.replay(parser.parseTerm("f(y/y,x/x)"), original));

        var one = astProgram(128, astSource("square", 64, 128, TYPED_SQUARE));
        Expr squareInput = parser.parseTerm("x*x");
        var candidate = one.transformMeasured(squareInput).candidates().getFirst();
        var step = candidate.steps().getFirst();
        var wrongCost = new AstRewriteTransport.Step(step.source(), step.target(), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta() + 1, step.equivalencePreservingByConstruction(),
            step.assumptions(), step.packId(), step.license());
        assertThrows(IllegalArgumentException.class, () -> one.replay(squareInput,
            new CompiledAstRewriteProgram.Candidate(candidate.programId(), candidate.sourceIds(), List.of(wrongCost))));
        assertThrows(IllegalArgumentException.class, () -> astProgram(128, astSource("square", 64, 128, TYPED_ZERO))
            .replay(squareInput, candidate));
    }

    @Test
    void typedHistoriesAreImmutableConnectedScopedAndDoNotChangeLegacyExecution() {
        var engine = astProgram(128, astSource("first", 64, 128, TYPED_ZERO), astSource("second", 64, 128, TYPED_ZERO));
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

        var scope = new SymbolScope(new UUID(0, 53));
        scope.alias("alias", scope.resolve("x"));
        var scopedEngine = astProgram(128, astSource("zero", 64, 128, TYPED_ZERO),
            astSource("square", 64, 128, TYPED_SQUARE));
        Expr scoped = SymbolicExpression.parse("(x+0)*alias", scope).expression();
        var scopedCandidate = scopedEngine.transformMeasured(scoped).candidates().getFirst();
        assertEquals(SymbolicExpression.parse("x^2", scope).expression(), scopedCandidate.target());
        var other = new SymbolScope(new UUID(0, 54));
        Expr mixed = new BinaryExpr(SymbolicExpression.parse("x+0", scope).expression(), MUL,
            SymbolicExpression.parse("x", other).expression());
        assertTrue(scopedEngine.transformMeasured(mixed).candidates().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> scopedEngine.replay(mixed, scopedCandidate));

        var plan = RewritePrograms.sequence("old-control", astSource("zero", 64, 128, TYPED_ZERO),
            astSource("square", 64, 128, TYPED_SQUARE));
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
