package de.regelsuche.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainRulesTest {
    private final ExpressionParser parser = new ExpressionParser();

    private Expr parse(String text) {
        return parser.parse(new InputRequest(InputType.TERM, text)).terms().get(0);
    }

    private RewriteRule findRule(List<RewriteRule> rules, String id) {
        return rules.stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void rewritesTrigonometricIdentityWithAssumptions() {
        Expr expr = parse("sin(x)^2 + cos(x)^2");
        RewriteRule rule = findRule(TrigonometricRules.rules(), "trig_pythagorean_sin_cos");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        Expr rewritten = rule.apply(expr);
        assertEquals("1", ExpressionFormatter.format(rewritten));
        // Pythagoras is unconditional.
        assertTrue(rule.assumptions(expr).isEmpty());
    }

    @Test
    void tanRewriteEmitsCosineAssumption() {
        Expr expr = parse("tan(x)");
        RewriteRule rule = findRule(TrigonometricRules.rules(), "trig_tan_to_sin_over_cos");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        Expr rewritten = rule.apply(expr);
        assertEquals("sin(x) / cos(x)", ExpressionFormatter.format(rewritten));
        List<Assumption> assumptions = rule.assumptions(expr);
        assertEquals(1, assumptions.size());
        assertEquals(Assumption.Kind.NON_ZERO, assumptions.get(0).kind());
        assertTrue(assumptions.get(0).expression().contains("cos(x)"));
    }

    @Test
    void rewritesLogarithmOnlyWithPositiveAssumptions() {
        Expr expr = parse("log(a*b)");
        RewriteRule rule = findRule(LogarithmicRules.rules(), "log_product_split");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        Expr rewritten = rule.apply(expr);
        assertEquals("log(a) + log(b)", ExpressionFormatter.format(rewritten));
        List<Assumption> assumptions = rule.assumptions(expr);
        assertEquals(2, assumptions.size());
        assertTrue(assumptions.stream().allMatch(a -> a.kind() == Assumption.Kind.POSITIVE));
    }

    @Test
    void radicalSqrtOfSquareGivesAbs() {
        Expr expr = parse("sqrt(a^2)");
        RewriteRule rule = findRule(RadicalRules.rules(), "radical_sqrt_of_square_to_abs");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        Expr rewritten = rule.apply(expr);
        assertEquals("abs(a)", ExpressionFormatter.format(rewritten));
        assertTrue(rule.assumptions(expr).isEmpty());
    }

    @Test
    void radicalSqrtOfProductRequiresNonNegativity() {
        Expr expr = parse("sqrt(a*b)");
        RewriteRule rule = findRule(RadicalRules.rules(), "radical_sqrt_of_product");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals(2, rule.assumptions(expr).size());
        assertTrue(rule.assumptions(expr).stream().allMatch(a -> a.kind() == Assumption.Kind.NON_NEGATIVE));
    }

    @Test
    void expOfLogRequiresPositiveArgument() {
        Expr expr = parse("exp(log(x))");
        RewriteRule rule = findRule(CalculusBasicRules.rules(), "calculus_exp_of_log");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals("x", ExpressionFormatter.format(rule.apply(expr)));
        List<Assumption> assumptions = rule.assumptions(expr);
        assertEquals(1, assumptions.size());
        assertEquals(Assumption.Kind.POSITIVE, assumptions.get(0).kind());
    }

    @Test
    void domainRegistryExposesAllNewDomains() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        assertTrue(registry.get(RuleDomainRegistry.TRIGONOMETRIC).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.LOGARITHMIC).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.RADICAL).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.CALCULUS_BASIC).isPresent());
        // Unknown domain stays absent.
        assertFalse(registry.get("nonexistent").isPresent());
    }
}
