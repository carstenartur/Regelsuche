package de.regelsuche.evolution;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
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

/** Real primitive generation into the existing model; not a migration of the string dispatcher. */
@Timeout(20)
class TypedPrimitiveBindingTransportTest {
    private static final List<String> SEQUENCE = List.of("difference", "square", "cancel");
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
        forgedStates.set(forgedStates.size()-1,
            SymbolicExpression.parse("x^2+113", new SymbolScope(new UUID(0, 92))).expression());
        assertTrue(model.session().matching(SEQUENCE, forgedStates).isEmpty());
    }
}
