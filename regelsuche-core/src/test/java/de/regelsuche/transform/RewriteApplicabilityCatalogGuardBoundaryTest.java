package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteApplicabilityCatalogGuardBoundaryTest {
    @Test
    void customPatternRuleWithUndeclaredAssumptionsStaysOutsideSafeProfile() {
        PatternRewriteRule rule = new GuardedPatternSubclass();

        RewriteApplicabilityCatalog.Entry entry =
            RewriteApplicabilityCatalog.inspect(rule);

        assertFalse(entry.safeProfileEligible());
        assertEquals(
            RewriteApplicabilityCatalog.Status
                .OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
            entry.status());
        assertEquals(
            "PATTERN_RULE_ASSUMPTIONS_REQUIRE_EXPLICIT_SCHEMA",
            entry.exclusionReason());
    }

    private static final class GuardedPatternSubclass extends PatternRewriteRule {
        private GuardedPatternSubclass() {
            super(
                "guarded_pattern_without_schema",
                PatternExpr.op(
                    BinaryOperator.DIV,
                    PatternExpr.var("A"),
                    PatternExpr.var("B")),
                PatternExpr.var("A"),
                RewriteKind.SIMPLIFY,
                false,
                -1,
                true);
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return List.of(Assumption.positive("B"));
        }
    }
}
