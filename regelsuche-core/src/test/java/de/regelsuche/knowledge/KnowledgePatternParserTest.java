package de.regelsuche.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import org.junit.jupiter.api.Test;

final class KnowledgePatternParserTest {
    @Test
    void functionalOperatorAliasesEqualInfixPatterns() {
        assertEquals(
            KnowledgePatternParser.parse("?A + ?B"),
            KnowledgePatternParser.parse("add(?A, ?B)")
        );
        assertEquals(
            KnowledgePatternParser.parse("?A - ?B"),
            KnowledgePatternParser.parse("sub(?A, ?B)")
        );
        assertEquals(
            KnowledgePatternParser.parse("?A * ?B"),
            KnowledgePatternParser.parse("mul(?A, ?B)")
        );
        assertEquals(
            KnowledgePatternParser.parse("?A / ?B"),
            KnowledgePatternParser.parse("div(?A, ?B)")
        );
        assertEquals(
            KnowledgePatternParser.parse("?A ^ ?B"),
            KnowledgePatternParser.parse("pow(?A, ?B)")
        );
        assertInstanceOf(
            PatternExpr.Function.class,
            KnowledgePatternParser.parse("sin(?A)")
        );
    }

    @Test
    void functionalOperatorAliasesRequireBinaryArity() {
        assertThrows(
            IllegalArgumentException.class,
            () -> KnowledgePatternParser.parse("add(?A)")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> KnowledgePatternParser.parse("pow(?A, ?B, ?C)")
        );
    }

    @Test
    void functionalPackRuleExecutesAgainstOrdinaryExpressionAst() {
        var rule = new KnowledgePackLoader().loadClasspathPacks().stream()
            .flatMap(pack -> pack.rules().stream())
            .filter(candidate -> candidate.id().equals(
                "sympy.trig.pythagorean"))
            .findFirst()
            .orElseThrow();
        var expression = new ExpressionParser().parseTerm(
            "sin(x)^2 + cos(x)^2");

        assertTrue(rule.matches(expression));
        assertEquals("1", ExpressionFormatter.format(rule.apply(expression)));
    }
}
