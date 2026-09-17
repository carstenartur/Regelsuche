package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class AstRewriteTransportBoundaryTest {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final RewriteRule SQUARE = new PatternRewriteRule("square",
        PatternExpr.op(MUL, A, A), PatternExpr.op(POW, A, PatternExpr.num(2)));
    private final ExpressionParser parser = new ExpressionParser();

    @Test void candidateLimitRetainsTheFirstStructuralResultsInEngineOrder() {
        Expr source = parser.parseTerm("f(x*x,y*y,z*z)");
        var complete = new AstRewriteTransport(List.of(SQUARE), 64, 128).generate(source);
        assertEquals(3, complete.size());
        assertEquals(complete.subList(0, 2), new AstRewriteTransport(List.of(SQUARE), 64, 2).generate(source));
        assertEquals(parser.parseTerm("f(x^2,y*y,z*z)"), complete.getFirst().target());
    }

    @Test void canonicalGrowthBoundIsStillEnforced() {
        RewriteRule grow = new PatternRewriteRule("grow", A, PatternExpr.op(MUL, A, A));
        Expr source = new VariableExpr("x");
        assertTrue(new AstRewriteTransport(List.of(grow), 0, 10).generate(source).isEmpty());
        assertEquals(List.of(new BinaryExpr(source, MUL, source)),
            new AstRewriteTransport(List.of(grow), 64, 10).generate(source).stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void anOverriddenPatternRuleIsNotReplacedWithBasePatternInstantiation() {
        RewriteRule custom = new PatternRewriteRule("custom", A, A) {
            @Override public boolean matches(Expr source) { return source.equals(new VariableExpr("x")); }
            @Override public Expr apply(Expr source) { return NumberExpr.exact("4/9"); }
            @Override public int estimatedCostDelta() { return 17; }
            @Override public boolean isEquivalencePreservingByConstruction() { return false; }
        };
        var transport = new AstRewriteTransport(List.of(custom), 64, 10);
        var result = transport.generate(new VariableExpr("x")).getFirst();
        assertEquals(NumberExpr.exact("4/9"), result.target());
        assertEquals(17, result.estimatedCostDelta());
        assertFalse(result.equivalencePreservingByConstruction());
        assertEquals(result.target(), transport.replay(result.source(), List.of(result)), "regeneration is not an arbitrary-rule proof");
    }

    @Test void anOversizedRuleResultFailsBeforeItCanBecomeARecordedStep() {
        Expr deep = new VariableExpr("y");
        for (int i = 0; i < 130; i++) deep = new BinaryExpr(deep, ADD, new NumberExpr(0));
        Expr output = deep;
        RewriteRule custom = new PatternRewriteRule("oversized", A, A) {
            @Override public boolean matches(Expr source) { return source.equals(new VariableExpr("x")); }
            @Override public Expr apply(Expr source) { return output; }
        };
        assertThrows(IllegalArgumentException.class,
            () -> new AstRewriteTransport(List.of(custom), 1_000, 10).generate(new VariableExpr("x")));
    }

    @Test void structuralChangeIsNotFilteredAsANoopByItsDisplayText() {
        Expr source = parser.parseTerm("a+(b+c)");
        Expr target = parser.parseTerm("(a+b)+c");
        RewriteRule associate = new PatternRewriteRule("associate", A, A) {
            @Override public boolean matches(Expr input) { return input.equals(source); }
            @Override public Expr apply(Expr input) { return target; }
        };
        var transport = new AstRewriteTransport(List.of(associate), 64, 10);
        var results = transport.generate(source);
        assertEquals(List.of(target), results.stream().map(AstRewriteTransport.Step::target).toList());
        assertEquals(target, transport.replay(source, results));
        assertTrue(new PreparedAstRewriteTransformationEngine(List.of(associate)).transform("a+(b+c)").isEmpty());
    }
}
