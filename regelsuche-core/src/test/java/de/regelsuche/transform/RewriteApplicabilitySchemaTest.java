package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import org.junit.jupiter.api.Test;

class RewriteApplicabilitySchemaTest {

    @Test
    void patternRulesExposeTheirExistingSourceWithoutChangingRuleIdentity() {
        PatternRewriteRule rule = new PatternRewriteRule(
            "x-to-one",
            PatternExpr.variable("x"),
            PatternExpr.num(1));

        RewriteApplicabilitySchema schema =
            RewriteApplicabilitySchema.fromPatternRule(rule);

        assertSame(rule, schema.executor());
        assertEquals(rule.source(), schema.pattern());
        assertEquals(rule.recognitionProfile(), schema.recognitionProfile());
        assertEquals("x-to-one", schema.ruleId());
        assertTrue(schema.contentHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void algorithmicSchemaHasNoInventedDeclarativeTarget() {
        RewriteRule executor = new AlgorithmicZRule();
        RewriteApplicabilitySchema schema =
            new RewriteApplicabilitySchema(
                "algorithmic-z-applicability/v1",
                executor,
                PatternExpr.variable("z"),
                RecognitionProfile.exact());

        assertSame(executor, schema.executor());
        assertFalse(schema.executor() instanceof PatternRewriteRule);
        assertEquals(PatternExpr.variable("z"), schema.pattern());
    }

    @Test
    void schemaPatternAndRevisionAreContentBound() {
        RewriteRule executor = new AlgorithmicZRule();
        RewriteApplicabilitySchema first =
            new RewriteApplicabilitySchema(
                "algorithmic-z-applicability/v1",
                executor,
                PatternExpr.variable("z"),
                RecognitionProfile.exact());
        RewriteApplicabilitySchema second =
            new RewriteApplicabilitySchema(
                "algorithmic-z-applicability/v2",
                executor,
                PatternExpr.variable("z"),
                RecognitionProfile.exact());
        RewriteApplicabilitySchema differentPattern =
            new RewriteApplicabilitySchema(
                "algorithmic-z-applicability/v1",
                executor,
                PatternExpr.variable("y"),
                RecognitionProfile.exact());

        assertNotEquals(first.contentHash(), second.contentHash());
        assertNotEquals(first.contentHash(), differentPattern.contentHash());
        assertEquals(first.contentHash(),
            new RewriteApplicabilitySchema(
                "algorithmic-z-applicability/v1",
                executor,
                PatternExpr.variable("z"),
                RecognitionProfile.exact()).contentHash());
    }

    @Test
    void invalidSchemasFailClosed() {
        RewriteRule executor = new AlgorithmicZRule();

        assertThrows(IllegalArgumentException.class,
            () -> new RewriteApplicabilitySchema(
                " ", executor, PatternExpr.variable("z"), null));
        assertThrows(NullPointerException.class,
            () -> new RewriteApplicabilitySchema(
                "z/v1", null, PatternExpr.variable("z"), null));
    }

    private static final class AlgorithmicZRule implements RewriteRule {
        @Override
        public String id() {
            return "algorithmic-z";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -1;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof VariableExpr variable
                && "z".equals(variable.name());
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException(
                    "rule does not match subtree");
            }
            return new NumberExpr(1);
        }
    }
}
