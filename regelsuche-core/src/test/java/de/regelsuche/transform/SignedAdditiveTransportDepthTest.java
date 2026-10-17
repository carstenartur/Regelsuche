package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class SignedAdditiveTransportDepthTest {
    private final AstRewriteTransport transport = new AstRewriteTransport(
            List.of(new SignedAdditiveRewriteRule()), 12, 128);

    @Test void balancedWideSourceDoesNotAbortTheTransport() {
        Expr source = balanced(128, product());
        assertEquals(9, depth(source));
        assertEquals(129, depth(SignedAdditiveForm.normalize(source, ignored -> {}).expression()));
        assertDeclinedWithoutAborting(source);
    }

    @Test void opaqueTermDepthIsIncludedInTheReconstructionBound() {
        Expr source = balanced(128, surround(new VariableExpr("opaque"), 57));
        assertEquals(64, depth(source));
        assertEquals(184, depth(SignedAdditiveForm.normalize(source, ignored -> {}).expression()));
        assertDeclinedWithoutAborting(source);
    }

    @Test void aSmallLocalRewriteCannotOverflowItsEnclosingExpression() {
        Expr local = balanced(8, product());
        Expr source = surround(local, 120);
        assertEquals(125, depth(source));
        assertEquals(129, depth(surround(
                SignedAdditiveForm.normalize(local, ignored -> {}).expression(), 120)));
        assertDeclinedWithoutAborting(source);
    }

    @Test void usefulDepthReducingCancellationStillReplaysInADeepContext() {
        var parser = new ExpressionParser();
        Expr source = surround(parser.parseTerm("((a+b)-c)-a"), 120);
        Expr expected = surround(parser.parseTerm("b-c"), 120);
        var step = transport.generate(source).stream().filter(candidate -> candidate.target().equals(expected))
                .findFirst().orElseThrow();
        assertEquals(expected, transport.replay(source, List.of(step)));
    }

    private void assertDeclinedWithoutAborting(Expr source) {
        assertDoesNotThrow(() -> AstRewriteTransport.requireBounded(source));
        var candidates = assertDoesNotThrow(() -> transport.generate(source));
        assertTrue(candidates.isEmpty(), "Depth-expanding normalizations must be declined, not abort the provider");
    }

    private static Expr product() {
        return new BinaryExpr(new BinaryExpr(new VariableExpr("u"), BinaryOperator.MUL,
                new VariableExpr("v")), BinaryOperator.MUL, new VariableExpr("w"));
    }

    private static Expr balanced(int count, Expr first) {
        var terms = new ArrayList<Expr>();
        terms.add(first);
        for (int i = 1; i < count; i++) terms.add(new VariableExpr("x" + i));
        return balanced(terms, 0, terms.size());
    }

    private static Expr balanced(List<Expr> terms, int start, int end) {
        if (end - start == 1) return terms.get(start);
        int middle = (start + end) / 2;
        return new BinaryExpr(balanced(terms, start, middle), BinaryOperator.ADD, balanced(terms, middle, end));
    }

    private static Expr surround(Expr expression, int levels) {
        for (int i = 0; i < levels; i++) expression = new FunctionExpr("sin", List.of(expression));
        return expression;
    }

    private static int depth(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + Math.max(depth(binary.left()), depth(binary.right()));
        if (expression instanceof FunctionExpr function) return 1 + function.arguments().stream().mapToInt(
                SignedAdditiveTransportDepthTest::depth).max().orElse(-1);
        return 0;
    }
}
