package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class AstRewriteTransportTest {
    private final ExpressionParser parser = new ExpressionParser();
    private static final PatternExpr A = PatternExpr.var("A");
    private static final RewriteRule SQUARE = new PatternRewriteRule("square",
        PatternExpr.op(MUL, A, A), PatternExpr.op(POW, A, PatternExpr.num(2)));
    private static final RewriteRule ZERO = new PatternRewriteRule("zero",
        PatternExpr.op(ADD, A, PatternExpr.num(0)), A);

    private AstRewriteTransport engine(RewriteRule... rules) {
        return new AstRewriteTransport(List.of(rules), 64, 128);
    }

    @Test void preservesRightAssociatedAdditionBoundInsideAPrimitiveRewrite() {
        Expr compound = parser.parseTerm("a+(b+c)");
        var result = engine(SQUARE).generate(new BinaryExpr(compound, MUL, compound));
        assertEquals(List.of(new BinaryExpr(compound, POW, new NumberExpr(2))), result.stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void preservesRightAssociatedMultiplicationBoundInsideAPrimitiveRewrite() {
        Expr compound = parser.parseTerm("a*(b*c)");
        var result = engine(SQUARE).generate(new BinaryExpr(compound, MUL, compound));
        assertEquals(List.of(new BinaryExpr(compound, POW, new NumberExpr(2))), result.stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void rationalInputRemainsOneNumericLeafRatherThanADivisionTree() {
        Expr literal = NumberExpr.exact("1/3");
        var result = engine(ZERO).generate(new BinaryExpr(literal, ADD, new NumberExpr(0)));
        assertEquals(List.of(literal), result.stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void aRuleProducedRationalRemainsExactAcrossTheFollowingPrimitive() {
        RewriteRule fold = new PatternRewriteRule("third", A, A) {
            @Override public boolean matches(Expr input) { return input.equals(new VariableExpr("x")); }
            @Override public Expr apply(Expr input) { return NumberExpr.exact("1/3"); }
        };
        var transport = engine(fold, ZERO);
        Expr source = parser.parseTerm("x+0");
        var first = transport.generate(source).stream().filter(step -> step.rule().equals("third")).findFirst().orElseThrow();
        assertEquals(new BinaryExpr(NumberExpr.exact("1/3"), ADD, new NumberExpr(0)), first.target());
        var second = transport.generate(first.target()).stream().filter(step -> step.rule().equals("zero")).findFirst().orElseThrow();
        assertEquals(NumberExpr.exact("1/3"), second.target());
        assertEquals(second.target(), transport.replay(source, List.of(first, second)));
    }

    @Test void functionsKeepOrderedArgumentsAndExactNumericNodes() {
        Expr compound = new FunctionExpr("f", List.of(parser.parseTerm("a+(b+c)"), NumberExpr.exact("2/7")));
        var result = engine(ZERO).generate(new BinaryExpr(compound, ADD, new NumberExpr(0)));
        assertEquals(List.of(compound), result.stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void sameLabelDifferentScopesDoNotBecomeTheSameInputBinding() {
        var firstScope = new SymbolScope(new UUID(0, 31));
        var secondScope = new SymbolScope(new UUID(0, 32));
        Expr first = SymbolicExpression.parse("x", firstScope).expression();
        Expr second = SymbolicExpression.parse("x", secondScope).expression();
        assertTrue(engine(SQUARE).generate(new BinaryExpr(first, MUL, second)).isEmpty());
        var result = engine(SQUARE).generate(new BinaryExpr(first, MUL, first));
        assertEquals(List.of(new BinaryExpr(first, POW, new NumberExpr(2))), result.stream().map(AstRewriteTransport.Step::target).toList());
    }

    @Test void replayRejectsSubstitutedSourceAndTargetEvenWhenMathematicallyEquivalent() {
        var transport = engine(SQUARE);
        Expr source = parser.parseTerm("(a+b)*(a+b)");
        var step = transport.generate(source).getFirst();
        assertEquals(step.target(), transport.replay(source, List.of(step)));
        Expr otherSource = parser.parseTerm("(b+a)*(b+a)");
        assertThrows(IllegalArgumentException.class, () -> transport.replay(otherSource, List.of(step)));
        var forged = new AstRewriteTransport.Step(step.source(), parser.parseTerm("(b+a)^2"), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta(), step.equivalencePreservingByConstruction(),
            step.assumptions(), step.packId(), step.license());
        assertThrows(IllegalArgumentException.class, () -> transport.replay(source, List.of(forged)));
    }

    @Test void replayChecksMetadataAndRulesInsteadOfTrustingConstructibleRecords() {
        var transport = engine(SQUARE);
        Expr source = parser.parseTerm("x*x");
        var step = transport.generate(source).getFirst();
        var forged = new AstRewriteTransport.Step(source, step.target(), step.rule(), step.kind(),
            step.mayIncreaseComplexity(), step.estimatedCostDelta() + 1, step.equivalencePreservingByConstruction(),
            step.assumptions(), step.packId(), step.license());
        assertThrows(IllegalArgumentException.class, () -> transport.replay(source, List.of(forged)));
        assertThrows(IllegalArgumentException.class, () -> engine(ZERO).replay(source, List.of(step)));
    }

    @Test void oldStringEngineStillHasItsReferenceBehavior() {
        var reference = new AstRewriteTransformationEngine(List.of(SQUARE, ZERO));
        var prepared = new PreparedAstRewriteTransformationEngine(List.of(SQUARE, ZERO));
        for (String source : List.of("x*x", "a+(b+c)+0", "f(x*x,y+0)", "1/3+0")) {
            assertEquals(reference.transform(source), prepared.transform(source));
        }
        Expr grouped = parser.parseTerm("a+(b+c)");
        assertNotEquals(grouped, parser.parseTerm(ExpressionFormatter.format(grouped)), "control: legacy display is not lossless AST transport");
    }

    @Test void returnedStepsAndAssumptionsAreImmutableAndLimitsAreExplicit() {
        var transport = engine(SQUARE, ZERO);
        var steps = transport.generate(parser.parseTerm("x*x+0"));
        assertFalse(steps.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> steps.clear());
        assertThrows(UnsupportedOperationException.class, () -> steps.getFirst().assumptions().clear());
        assertThrows(IllegalArgumentException.class, () -> new AstRewriteTransport(List.of(SQUARE), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> transport.replay(parser.parseTerm("x"), List.of()));
        assertThrows(NullPointerException.class, () -> transport.generate(null));
        Expr deep = new VariableExpr("x");
        for (int i = 0; i < 130; i++) deep = new BinaryExpr(deep, ADD, new NumberExpr(0));
        Expr finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> transport.generate(finalDeep));
        var many = new ArrayList<Expr>();
        for (int i = 0; i <= AstRewriteTransport.MAXIMUM_NODES; i++) many.add(new VariableExpr("x"));
        assertThrows(IllegalArgumentException.class, () -> transport.generate(new FunctionExpr("f", many)));
    }
}
