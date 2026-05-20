package de.regelsuche.equation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class EquationRewriteEngineTest {
    private final ExpressionParser parser = new ExpressionParser();

    private Expr parse(String text) {
        return parser.parse(new InputRequest(InputType.TERM, text)).terms().get(0);
    }

    @Test
    void equationRuleAppliesBothSides() {
        Equation eq = new Equation(parse("2*x"), parse("4"));
        EquationRewriteContext ctx = new EquationRewriteContext(
            List.of(new NumberExpr(3)),
            List.of()
        );
        EquationRewriteEngine engine = new EquationRewriteEngine(
            List.of(new EquationRewriteEngine.AddBothSidesRule())
        );
        List<EquationStep> steps = engine.step(eq, ctx);
        assertEquals(1, steps.size());
        EquationStep step = steps.get(0);
        assertEquals("equation_add_both_sides", step.ruleId());
        assertEquals("2 * x + 3", ExpressionFormatter.format(step.equation().left()));
        assertEquals("4 + 3", ExpressionFormatter.format(step.equation().right()));
        assertTrue(step.assumptions().isEmpty());
    }

    @Test
    void multiplyEmitsNonZeroAssumptionAndSkipsZero() {
        Equation eq = new Equation(parse("x"), parse("y"));
        EquationRewriteContext ctx = new EquationRewriteContext(
            List.of(new NumberExpr(0), new NumberExpr(2)),
            List.of()
        );
        EquationRewriteEngine engine = new EquationRewriteEngine(
            List.of(new EquationRewriteEngine.MultiplyBothSidesRule())
        );
        List<EquationStep> steps = engine.step(eq, ctx);
        // Multiplying by 0 is unsound and must be skipped; only one step remains.
        assertEquals(1, steps.size());
        EquationStep step = steps.get(0);
        assertEquals(1, step.assumptions().size());
        Assumption assumption = step.assumptions().get(0);
        assertEquals(Assumption.Kind.NON_ZERO, assumption.kind());
        assertTrue(assumption.expression().contains("2"));
    }

    @Test
    void applyInjectiveFunctionWrapsBothSides() {
        Equation eq = new Equation(parse("x"), parse("y"));
        EquationRewriteContext ctx = new EquationRewriteContext(List.of(), List.of("exp"));
        EquationRewriteEngine engine = new EquationRewriteEngine(
            List.of(new EquationRewriteEngine.ApplyInjectiveFunctionRule())
        );
        List<EquationStep> steps = engine.step(eq, ctx);
        assertEquals(1, steps.size());
        assertEquals("exp(x)", ExpressionFormatter.format(steps.get(0).equation().left()));
        assertEquals("exp(y)", ExpressionFormatter.format(steps.get(0).equation().right()));
    }

    @Test
    void defaultEngineExposesAllThreeRules() {
        EquationRewriteEngine engine = new EquationRewriteEngine();
        assertEquals(3, engine.rules().size());
        assertNotNull(engine.rules().get(0).description());
    }
}
