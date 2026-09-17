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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Exercises the existing evolution compiler, not a manually substituted tactic implementation. */
@Timeout(30)
class CompiledAstBindingTransportTest {
    private static final List<String> GENES = List.of("difference-product", "square-product", "cancel-addend");
    private final ExpressionParser parser = new ExpressionParser();

    private CompiledAstRewriteProgram compiled() {
        var genome = TraceStrategyTransferExample.inventory();
        List<EvolutionRewriteProgramPlan.Node> nodes = new ArrayList<>();
        for (int i = 0; i < GENES.size(); i++) nodes.add(new EvolutionRewriteProgramPlan.Source("step-"+i, List.of(GENES.get(i))));
        var plan = EvolutionRewriteProgramPlan.create(genome, new EvolutionRewriteProgramPlan.Sequence("trajectory", nodes), 16, 16);
        var actualProgram = new EvolutionRewriteProgramCompiler().compile(genome, plan).program();
        return new CompiledLinearRewriteEngine(actualProgram, 128).compileAst();
    }

    private TraceBindingModel model(CompiledAstRewriteProgram program) {
        var one = program.transformMeasured(parser.parseTerm("(x+y)*(x-y)+y*y+101")).candidates().getFirst();
        var two = program.transformMeasured(parser.parseTerm("(u+v)*(u-v)+v*v+103")).candidates().getFirst();
        return TraceBindingModel.learn(List.of(new TraceBindingModel.Trace("train-one", GENES, one.states()),
            new TraceBindingModel.Trace("train-two", GENES, two.states())), Set.of(GENES), 4, 100_000, 100_000);
    }

    @Test void compiledInventoriedTrajectoryTransfersGroupedBindingsWithoutManualStepping() {
        var program = compiled();
        var model = model(program);
        assertEquals(1, model.templates().size());
        String input = "((a+(b+c))+y)*((a+(b+c))-y)+y*y+107";
        var batch = program.transformMeasured(parser.parseTerm(input));
        assertEquals(1, batch.candidates().size());
        var path = batch.candidates().getFirst();
        assertEquals(3, path.steps().size());
        assertEquals(parser.parseTerm("(a+(b+c))^2+107"), path.target());
        assertEquals(1, model.session().matching(GENES, path.states()).size());
        assertEquals(path.target(), program.replay(path.source(), path).target());
        new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(path.target()));
        assertEquals(3, batch.workMetrics().sourceInvocations());
        assertEquals(3, batch.workMetrics().sourceCandidates());
        assertEquals(2, batch.workMetrics().composedCandidates());
        assertEquals(9, batch.workMetrics().totalWorkUnits());
    }

    @Test void exactRationalLeafRemainsAtomicThroughCompiledGenerationAndModelApplication() {
        var program = compiled();
        Expr compound = new BinaryExpr(parser.parseTerm("a"), ADD, NumberExpr.exact("1/3"));
        Expr y = parser.parseTerm("y");
        Expr input = new BinaryExpr(new BinaryExpr(new BinaryExpr(new BinaryExpr(compound, ADD, y), MUL,
            new BinaryExpr(compound, SUB, y)), ADD, new BinaryExpr(y, MUL, y)), ADD, new NumberExpr(109));
        var path = program.transformMeasured(input).candidates().getFirst();
        assertEquals(new BinaryExpr(new BinaryExpr(compound, POW, new NumberExpr(2)), ADD, new NumberExpr(109)), path.target());
        assertEquals(1, model(program).session().matching(GENES, path.states()).size());
        assertEquals(path.target(), program.replay(input, path).target());
    }

    @Test void scopeIdentityRemainsSharedAcrossEveryCompiledState() {
        var scope = new SymbolScope(new UUID(0, 63));
        scope.alias("alias", scope.resolve("y"));
        var program = compiled();
        var model = model(program);
        Expr input = SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope).expression();
        var path = program.transformMeasured(input).candidates().getFirst();
        assertEquals(SymbolicExpression.parse("x^2+113", scope).expression(), path.target());
        assertEquals(1, model.session().matching(GENES, path.states()).size());
        var substituted = new ArrayList<>(path.states());
        substituted.set(3, SymbolicExpression.parse("x^2+113", new SymbolScope(new UUID(0, 64))).expression());
        assertTrue(model.session().matching(GENES, substituted).isEmpty());
    }

    @Test void modelStillRejectsValidCompiledRewritesThatChangeTheWrongBoundResidual() {
        var program = compiled();
        var model = model(program);
        String input = "(x+y)*(x-y)+y*y+(w-z^2+z*z)^2";
        var batch = program.transformMeasured(parser.parseTerm(input));
        int accepted = 0, rejected = 0;
        for (var path : batch.candidates()) {
            assertEquals(path.target(), program.replay(path.source(), path).target());
            new ExactPolynomialAnalysis().requireEquivalent(input, ExpressionFormatter.format(path.target()));
            if (model.session().matching(GENES, path.states()).isEmpty()) rejected++;
            else {
                accepted++;
                assertEquals(parser.parseTerm("x^2+(w-z^2+z*z)^2"), path.target());
            }
        }
        assertTrue(accepted > 0, "the intended three-step compiled path is retained");
        assertTrue(rejected > 0, "valid wrong-occurrence paths must not satisfy the shared learned binding");
    }
}
