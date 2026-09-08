package de.regelsuche.egraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class EqualitySaturationSoundnessTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void symbolicCancellationCannotMergeAcrossAnUndischargedAssumption() {
        var rule = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(candidate -> candidate.id().equals("ast_cancel_division_factor")).findFirst().orElseThrow();
        assertUnchanged("(x * y) / x", rule);
    }

    @Test
    void aConcreteDischargedCancellationStillWorks() {
        var rule = AstRewriteTransformationEngine.defaultRules().stream()
            .filter(candidate -> candidate.id().equals("ast_cancel_division_factor")).findFirst().orElseThrow();
        var graph = new EGraph();
        var root = graph.addExpression(parser.parseTerm("(2 * y) / 2"));
        var result = new EqualitySaturation(List.of(rule)).saturate(graph, root, node -> 1);
        assertEquals(parser.parseTerm("y"), result.expression());
    }

    @Test
    void nonEquivalentRulesCannotCreateAnEqualityClass() {
        var rule = new PatternRewriteRule("not-an-equality", new PatternExpr.Placeholder("a"),
            new PatternExpr.LiteralNumber(de.regelsuche.scalar.ExactRational.ZERO), RewriteKind.NORMALIZE, false, 0, false);
        assertUnchanged("x", rule);
    }

    @Test
    void patternSubclassMatchingGuardsAreNotBypassed() {
        var rule = new PatternRewriteRule("guarded", new PatternExpr.Placeholder("a"),
                new PatternExpr.LiteralNumber(de.regelsuche.scalar.ExactRational.ZERO)) {
            @Override
            public boolean matches(Expr expression) {
                return false;
            }
        };
        assertUnchanged("x", rule);
    }

    private void assertUnchanged(String input, RewriteRule rule) {
        var graph = new EGraph();
        Expr original = parser.parseTerm(input);
        var root = graph.addExpression(original);
        var result = new EqualitySaturation(List.of(rule), new EqualitySaturation.Config(2, 100))
            .saturate(graph, root, node -> 1);
        assertEquals(original, result.expression());
        assertFalse(result.stats().appliedRules().containsKey(rule.id()));
    }
}
