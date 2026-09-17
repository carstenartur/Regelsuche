package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.program.CompiledAstRewriteProgram;
import de.regelsuche.search.program.CompiledLinearRewriteEngine;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real typed generation into the existing model; legacy string dispatch remains a separate control. */
@Timeout(30)
class TypedPrimitiveBindingTransportTest {
    private static final List<String> SEQUENCE = List.of("difference", "square", "cancel");
    private static final List<String> COMPILED_GENES = List.of("difference-product", "square-product", "cancel-addend");
    private final ExpressionParser parser = new ExpressionParser();
    private static final PatternExpr A = PatternExpr.var("A");
    private static final PatternExpr B = PatternExpr.var("B");
    private static final PatternExpr A2 = PatternExpr.op(POW, A, PatternExpr.num(2));
    private static final PatternExpr B2 = PatternExpr.op(POW, B, PatternExpr.num(2));
    private static final List<RewriteRule> RULES = List.of(
        new PatternRewriteRule("difference", PatternExpr.op(MUL, PatternExpr.op(ADD, A, B), PatternExpr.op(SUB, A, B)),
            PatternExpr.op(SUB, A2, B2)),
        new PatternRewriteRule("square", PatternExpr.op(MUL, A, A), A2),
        new PatternRewriteRule("cancel", PatternExpr.op(ADD, PatternExpr.op(SUB, A, B), B), A));
    private final AstRewriteTransport transport = new AstRewriteTransport(RULES, 64, 128);

    private record Run(TraceBindingModel.Trace trace, List<AstRewriteTransport.Step> steps) {}

    private Run run(String id, Expr source) {
        var states = new ArrayList<Expr>();
        var steps = new ArrayList<AstRewriteTransport.Step>();
        states.add(source);
        Expr current = source;
        for (String rule : SEQUENCE) {
            var step = transport.generate(current).stream().filter(value -> value.rule().equals(rule)).findFirst().orElseThrow();
            steps.add(step);
            current = step.target();
            states.add(current);
        }
        assertEquals(current, transport.replay(source, steps));
        return new Run(new TraceBindingModel.Trace(id, SEQUENCE, states), List.copyOf(steps));
    }

    private TraceBindingModel model() {
        return TraceBindingModel.learn(List.of(
            run("one", parser.parseTerm("(x+y)*(x-y)+y*y+101")).trace(),
            run("two", parser.parseTerm("(u+v)*(u-v)+v*v+103")).trace()), Set.of(SEQUENCE), 4, 100_000, 100_000);
    }

    private CompiledAstRewriteProgram compiledProgram() {
        var genome = TraceStrategyTransferExample.inventory();
        List<EvolutionRewriteProgramPlan.Node> nodes = new ArrayList<>();
        for (int i = 0; i < COMPILED_GENES.size(); i++) {
            nodes.add(new EvolutionRewriteProgramPlan.Source("step-" + i, List.of(COMPILED_GENES.get(i))));
        }
        var plan = EvolutionRewriteProgramPlan.create(genome,
            new EvolutionRewriteProgramPlan.Sequence("trajectory", nodes), 16, 16);
        return new CompiledLinearRewriteEngine(new EvolutionRewriteProgramCompiler().compile(genome, plan).program(), 128).compileAst();
    }

    private TraceBindingModel compiledModel(CompiledAstRewriteProgram program) {
        var one = program.transformMeasured(parser.parseTerm("(x+y)*(x-y)+y*y+101")).candidates().getFirst();
        var two = program.transformMeasured(parser.parseTerm("(u+v)*(u-v)+v*v+103")).candidates().getFirst();
        return TraceBindingModel.learn(List.of(
            new TraceBindingModel.Trace("train-one", COMPILED_GENES, one.states()),
            new TraceBindingModel.Trace("train-two", COMPILED_GENES, two.states())),
            Set.of(COMPILED_GENES), 4, 100_000, 100_000);
    }

    @Test void compoundGroupingTransfersThroughAllThreePrimitivesAndBindingChecks() {
        var model = model();
        assertEquals(1, model.templates().size());
        String input = "((a+(b+c))+y)*((a+(b+c))-y)+y*y+107";
        var result = run("grouped", parser.parseTerm(input));
        assertEquals(parser.parseTerm("(a+(b+c))^2+107"), result.trace().states().getLast());
        assertEquals(1, model.session().matching(SEQUENCE, result.trace().states()).size());
        new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(result.trace().states().getLast()));

        var legacy = new PreparedAstRewriteTransformationEngine(RULES, 64, 128);
        var legacyStates = new ArrayList<Expr>();
        legacyStates.add(parser.parseTerm(input));
        String current = input;
        for (String rule : SEQUENCE) {
            current = legacy.transform(current).stream().filter(step -> step.rule().equals(rule)).findFirst().orElseThrow().transformedExpression();
            legacyStates.add(parser.parseTerm(current));
        }
        assertTrue(model.session().matching(SEQUENCE, legacyStates).isEmpty(), "the unchanged lossy control still fails shared bindings");
    }

    @Test void rationalLeavesRemainAtomicInsideTransferredCompoundBindings() {
        Expr compound = new BinaryExpr(parser.parseTerm("a"), ADD, NumberExpr.exact("1/3"));
        Expr y = parser.parseTerm("y");
        Expr source = new BinaryExpr(new BinaryExpr(new BinaryExpr(new BinaryExpr(compound, ADD, y), MUL,
            new BinaryExpr(compound, SUB, y)), ADD, new BinaryExpr(y, MUL, y)), ADD, new NumberExpr(109));
        var result = run("rational", source);
        assertEquals(new BinaryExpr(new BinaryExpr(compound, POW, new NumberExpr(2)), ADD, new NumberExpr(109)),
            result.trace().states().getLast());
        assertEquals(1, model().session().matching(SEQUENCE, result.trace().states()).size());
    }

    @Test void scopedAliasesTransferWithoutConfusingSymbolsFromAnotherScope() {
        var scope = new SymbolScope(new UUID(0, 91));
        scope.alias("alias", scope.resolve("y"));
        Expr source = SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope).expression();
        var result = run("scoped", source);
        assertEquals(SymbolicExpression.parse("x^2+113", scope).expression(), result.trace().states().getLast());
        var model = model();
        assertEquals(1, model.session().matching(SEQUENCE, result.trace().states()).size());
        var forgedStates = new ArrayList<>(result.trace().states());
        forgedStates.set(forgedStates.size() - 1,
            SymbolicExpression.parse("x^2+113", new SymbolScope(new UUID(0, 92))).expression());
        assertTrue(model.session().matching(SEQUENCE, forgedStates).isEmpty());
    }

    @Test void compiledTrajectoryRetainsGroupedBindingsReplayAndMechanicalWork() {
        var program = compiledProgram();
        var model = compiledModel(program);
        assertEquals(1, model.templates().size());
        String input = "((a+(b+c))+y)*((a+(b+c))-y)+y*y+107";
        var batch = program.transformMeasured(parser.parseTerm(input));
        assertEquals(1, batch.candidates().size());
        var path = batch.candidates().getFirst();
        assertEquals(3, path.steps().size());
        assertEquals(parser.parseTerm("(a+(b+c))^2+107"), path.target());
        assertEquals(1, model.session().matching(COMPILED_GENES, path.states()).size());
        assertEquals(path.target(), program.replay(path.source(), path).target());
        new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(path.target()));
        assertEquals(3, batch.workMetrics().sourceInvocations());
        assertEquals(3, batch.workMetrics().sourceCandidates());
        assertEquals(2, batch.workMetrics().composedCandidates());
        assertEquals(9, batch.workMetrics().totalWorkUnits());
    }

    @Test void compiledRationalAndScopedStatesRemainExactAcrossModelApplication() {
        var program = compiledProgram();
        var model = compiledModel(program);
        Expr compound = new BinaryExpr(parser.parseTerm("a"), ADD, NumberExpr.exact("1/3"));
        Expr y = parser.parseTerm("y");
        Expr rational = new BinaryExpr(new BinaryExpr(new BinaryExpr(new BinaryExpr(compound, ADD, y), MUL,
            new BinaryExpr(compound, SUB, y)), ADD, new BinaryExpr(y, MUL, y)), ADD, new NumberExpr(109));
        var rationalPath = program.transformMeasured(rational).candidates().getFirst();
        assertEquals(new BinaryExpr(new BinaryExpr(compound, POW, new NumberExpr(2)), ADD, new NumberExpr(109)),
            rationalPath.target());
        assertEquals(1, model.session().matching(COMPILED_GENES, rationalPath.states()).size());
        assertEquals(rationalPath.target(), program.replay(rational, rationalPath).target());

        var scope = new SymbolScope(new UUID(0, 63));
        scope.alias("alias", scope.resolve("y"));
        Expr scoped = SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope).expression();
        var scopedPath = program.transformMeasured(scoped).candidates().getFirst();
        assertEquals(SymbolicExpression.parse("x^2+113", scope).expression(), scopedPath.target());
        assertEquals(1, model.session().matching(COMPILED_GENES, scopedPath.states()).size());
        var substituted = new ArrayList<>(scopedPath.states());
        substituted.set(3, SymbolicExpression.parse("x^2+113", new SymbolScope(new UUID(0, 64))).expression());
        assertTrue(model.session().matching(COMPILED_GENES, substituted).isEmpty());
    }

    @Test void compiledModelRejectsValidRewritesAtTheWrongBoundResidual() {
        var program = compiledProgram();
        var model = compiledModel(program);
        String input = "(x+y)*(x-y)+y*y+(w-z^2+z*z)^2";
        var batch = program.transformMeasured(parser.parseTerm(input));
        int accepted = 0, rejected = 0;
        for (var path : batch.candidates()) {
            assertEquals(path.target(), program.replay(path.source(), path).target());
            new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(path.target()));
            if (model.session().matching(COMPILED_GENES, path.states()).isEmpty()) rejected++;
            else {
                accepted++;
                assertEquals(parser.parseTerm("x^2+(w-z^2+z*z)^2"), path.target());
            }
        }
        assertTrue(accepted > 0, "the intended three-step compiled path is retained");
        assertTrue(rejected > 0, "valid wrong-occurrence paths must not satisfy the shared learned binding");
    }
}