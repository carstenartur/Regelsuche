package de.regelsuche.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class RationalRulesAssumptionsTest {
    private final ExpressionParser parser = new ExpressionParser();

    private Expr parse(String text) {
        return parser.parse(new InputRequest(InputType.TERM, text)).terms().get(0);
    }

    private RewriteRule rule(String id) {
        return RationalRules.rules().stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    @Test
    void cancelCommonFactorReportsDenominatorNonZero() {
        RewriteRule cancel = rule("rational_cancel_common_factor");
        Expr expression = parse("(a * b) / (b * c)");
        if (cancel.matches(expression)) {
            List<Assumption> assumptions = cancel.assumptions(expression);
            assertFalse(assumptions.isEmpty(), "expected an assumption to be surfaced");
            assertTrue(assumptions.stream().anyMatch(a -> a.kind() == Assumption.Kind.NON_ZERO),
                "expected a NON_ZERO assumption, got " + assumptions);
        }
    }

    @Test
    void divideByFractionReportsBothInnerDenominators() {
        RewriteRule rule = rule("rational_divide_by_fraction");
        Expr expression = parse("(a / b) / (c / d)");
        assertTrue(rule.matches(expression));
        List<Assumption> assumptions = rule.assumptions(expression);
        assertTrue(assumptions.size() >= 2,
            "expected at least two assumptions (b != 0, c != 0/d != 0), got " + assumptions);
        assertTrue(assumptions.stream().allMatch(a -> a.kind() == Assumption.Kind.NON_ZERO));
    }
}
