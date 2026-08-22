package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.ast.BinaryOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteApplicabilitySchemaGuardInferenceTest {

    @Test
    void patternRuleFactoryInfersAtomicNonZeroDenominatorConditions() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr one = PatternExpr.num(1);
        PatternExpr successor = PatternExpr.op(BinaryOperator.ADD, a, one);
        PatternRewriteRule rule = new PatternRewriteRule(
            "test.telescoping",
            PatternExpr.op(
                BinaryOperator.DIV,
                one,
                PatternExpr.op(BinaryOperator.MUL, a, successor)),
            PatternExpr.op(
                BinaryOperator.SUB,
                PatternExpr.op(BinaryOperator.DIV, one, a),
                PatternExpr.op(BinaryOperator.DIV, one, successor)));

        RewriteApplicabilitySchema inferred =
            RewriteApplicabilitySchema.fromPatternRule(rule);
        RewriteApplicabilitySchema explicitlyUnguarded =
            new RewriteApplicabilitySchema(
                "explicitly-unguarded/v1",
                rule,
                rule.source(),
                rule.recognitionProfile(),
                List.of());

        assertEquals(
            List.of(
                RequiredAssumptionTemplate.nonZero(a),
                RequiredAssumptionTemplate.nonZero(successor)),
            inferred.requiredAssumptions());
        assertNotEquals(
            inferred.contentHash(), explicitlyUnguarded.contentHash());
    }

    @Test
    void literalZeroDenominatorFailsSchemaConstruction() {
        PatternRewriteRule invalid = new PatternRewriteRule(
            "test.zero-denominator",
            PatternExpr.op(
                BinaryOperator.DIV,
                PatternExpr.var("A"),
                PatternExpr.num(0)),
            PatternExpr.var("A"));

        assertThrows(IllegalArgumentException.class,
            () -> RewriteApplicabilitySchema.fromPatternRule(invalid));
    }
}
