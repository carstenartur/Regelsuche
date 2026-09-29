package de.regelsuche.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalizerDispatchCompatibilityTest {
    @Test void recursiveCanonicalizationPreservesThePublicSubclassOverride(){
        var canonicalizer = new ExpressionCanonicalizer() {
            @Override public Expr canonicalize(Expr expression, AssumptionContext context) {
                if (expression.equals(new VariableExpr("x"))) return new VariableExpr("y");
                return super.canonicalize(expression, context);
            }
        };
        var source = new FunctionExpr("f", List.of(new VariableExpr("x")));
        var expected = new FunctionExpr("f", List.of(new VariableExpr("y")));
        assertEquals(expected, canonicalizer.canonicalize(source));
        assertEquals("f(y)", canonicalizer.canonicalize("f(x)"));
    }
}
