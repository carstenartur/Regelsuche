package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaBackedRewriteRuleTest {

    @Test
    void schemaGuidesAnalysisButConcreteDelegateStillExecutes() {
        RewriteRule delegate = new AlgorithmicZRule();
        SchemaBackedRewriteRule rule = new SchemaBackedRewriteRule(
            "algorithmic-z-applicability/v1",
            delegate,
            PatternExpr.variable("z"));

        PatternMatchAnalyzer.Analysis analysis = new PatternMatchAnalyzer()
            .analyze(
                rule.source(),
                new VariableExpr("z"),
                rule.recognitionProfile());
        List<Transformation> transformations =
            new AstRewriteTransformationEngine(List.of(rule))
                .transform("z");

        assertEquals(PatternMatchAnalyzer.Status.EXACT_MATCH,
            analysis.status());
        assertEquals(1, transformations.size());
        assertEquals("1", transformations.getFirst()
            .transformedExpression());
        assertEquals(List.of("p != 0"),
            transformations.getFirst().assumptions());
        assertTrue(rule.mayEmitAssumptions());
        assertEquals(AlgorithmicZRule.class.getName(),
            rule.delegateClassName());
    }

    @Test
    void schemaAndDelegateIdentityAreContentBound() {
        RewriteRule delegate = new AlgorithmicZRule();
        SchemaBackedRewriteRule first = new SchemaBackedRewriteRule(
            "algorithmic-z-applicability/v1",
            delegate,
            PatternExpr.variable("z"));
        SchemaBackedRewriteRule second = new SchemaBackedRewriteRule(
            "algorithmic-z-applicability/v2",
            delegate,
            PatternExpr.variable("z"));
        SchemaBackedRewriteRule differentPattern =
            new SchemaBackedRewriteRule(
                "algorithmic-z-applicability/v1",
                delegate,
                PatternExpr.variable("y"));

        assertNotEquals(
            RuleInventoryFingerprint.ruleContentHash(first),
            RuleInventoryFingerprint.ruleContentHash(second));
        assertNotEquals(
            RuleInventoryFingerprint.ruleContentHash(first),
            RuleInventoryFingerprint.ruleContentHash(differentPattern));
        assertTrue(first.descriptor().sourceReference()
            .contains("delegateClass=" + AlgorithmicZRule.class.getName()));
    }

    @Test
    void invalidOrNestedSchemaAdaptersFailClosed() {
        RewriteRule delegate = new AlgorithmicZRule();
        SchemaBackedRewriteRule wrapped = new SchemaBackedRewriteRule(
            "algorithmic-z-applicability/v1",
            delegate,
            PatternExpr.variable("z"));

        assertThrows(IllegalArgumentException.class,
            () -> new SchemaBackedRewriteRule(
                " ", delegate, PatternExpr.variable("z")));
        assertThrows(IllegalArgumentException.class,
            () -> new SchemaBackedRewriteRule(
                "nested/v1", wrapped, PatternExpr.variable("z")));
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

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return matches(subtree)
                ? List.of(Assumption.nonZero("p"))
                : List.of();
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }
    }
}
