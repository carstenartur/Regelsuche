package de.regelsuche.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RewriteRule.RewriteApplicabilitySchemaProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertEquals("1", ExpressionFormatter.format(rule.apply(expr)));
        assertTrue(rule.assumptions(expr).isEmpty());
    }

    @Test
    void tanRewriteEmitsCosineAssumption() {
        Expr expr = parse("tan(x)");
        RewriteRule rule = findRule(TrigonometricRules.rules(), "trig_tan_to_sin_over_cos");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals("sin(x) / cos(x)", ExpressionFormatter.format(rule.apply(expr)));
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
        assertEquals("log(a) + log(b)", ExpressionFormatter.format(rule.apply(expr)));
        assertEquals(2, rule.assumptions(expr).size());
        assertTrue(rule.assumptions(expr).stream().allMatch(
            assumption -> assumption.kind() == Assumption.Kind.POSITIVE));
    }

    @Test
    void radicalSqrtOfSquareGivesAbs() {
        Expr expr = parse("sqrt(a^2)");
        RewriteRule rule = findRule(RadicalRules.rules(), "radical_sqrt_of_square_to_abs");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals("abs(a)", ExpressionFormatter.format(rule.apply(expr)));
        assertTrue(rule.assumptions(expr).isEmpty());
    }

    @Test
    void radicalSqrtOfProductRequiresNonNegativity() {
        Expr expr = parse("sqrt(a*b)");
        RewriteRule rule = findRule(RadicalRules.rules(), "radical_sqrt_of_product");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals(2, rule.assumptions(expr).size());
        assertTrue(rule.assumptions(expr).stream().allMatch(
            assumption -> assumption.kind() == Assumption.Kind.NON_NEGATIVE));
    }

    @Test
    void expOfLnRequiresPositiveArgument() {
        Expr expr = parse("exp(ln(x))");
        RewriteRule rule = findRule(CalculusBasicRules.rules(), "calculus_exp_of_ln");
        assertNotNull(rule);
        assertTrue(rule.matches(expr));
        assertEquals("x", ExpressionFormatter.format(rule.apply(expr)));
        assertEquals(Assumption.Kind.POSITIVE, rule.assumptions(expr).getFirst().kind());
    }

    @Test
    void baseTenLogIsNotTreatedAsInverseOfExp() {
        List<RewriteRule> rules = CalculusBasicRules.rules();
        assertNull(findRule(rules, "calculus_exp_of_log"));
        assertNull(findRule(rules, "calculus_log_of_exp"));
        assertFalse(rules.stream().anyMatch(rule -> rule.matches(parse("exp(log(x))"))));
        assertFalse(rules.stream().anyMatch(rule -> rule.matches(parse("log(exp(x))"))));
    }

    @Test
    void domainRegistryExposesAllNewDomains() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        assertTrue(registry.get(RuleDomainRegistry.TRIGONOMETRIC).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.LOGARITHMIC).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.RADICAL).isPresent());
        assertTrue(registry.get(RuleDomainRegistry.CALCULUS_BASIC).isPresent());
        assertFalse(registry.get("nonexistent").isPresent());
    }

    @Test
    void safePreparationNeverInfersSchemaFromRuleMetadata() {
        RewriteApplicabilitySchema.CoverageEntry entry =
            RewriteApplicabilitySchema.coverageOf(new NoSchemaRule("log_product_split"));
        assertFalse(entry.safeProfileEligible());
        assertEquals(
            RewriteApplicabilitySchema.CoverageStatus.OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            entry.status());
        assertEquals("NO_EXPLICIT_APPLICABILITY_SCHEMA", entry.exclusionReason());
    }

    @Test
    void explicitSchemaMustBindExactExecutorObject() {
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RewriteApplicabilitySchema.coverageOf(new BadProviderRule()));
        assertTrue(error.getMessage().contains("exact executor object"));
    }

    @Test
    void customPatternAssumptionsRequireExplicitSchema() {
        RewriteApplicabilitySchema.CoverageEntry entry =
            RewriteApplicabilitySchema.coverageOf(new GuardedPatternSubclass());
        assertFalse(entry.safeProfileEligible());
        assertEquals(
            RewriteApplicabilitySchema.CoverageStatus
                .OUTSIDE_SAFE_PROFILE_UNDECLARED_ASSUMPTIONS,
            entry.status());
        assertEquals(
            "PATTERN_RULE_ASSUMPTIONS_REQUIRE_EXPLICIT_SCHEMA",
            entry.exclusionReason());
    }

    @Test
    void domainCoverageRetainsPositiveAndNegativeDecisions() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        List<RewriteApplicabilitySchema.CoverageEntry> coverage =
            registry.applicabilityCoverageFor(List.of(
                RuleDomainRegistry.POLYNOMIAL,
                RuleDomainRegistry.RATIONAL,
                RuleDomainRegistry.TRIGONOMETRIC,
                RuleDomainRegistry.LOGARITHMIC,
                RuleDomainRegistry.RADICAL,
                RuleDomainRegistry.CALCULUS_BASIC));
        Map<String, RewriteApplicabilitySchema.CoverageEntry> byId = new LinkedHashMap<>();
        coverage.forEach(entry -> byId.put(entry.rule().id(), entry));

        assertTrue(byId.get("trig_tan_to_sin_over_cos").safeProfileEligible());
        assertTrue(byId.get("calculus_exp_of_ln").safeProfileEligible());
        assertTrue(byId.get("log_product_split").safeProfileEligible());
        assertTrue(byId.get("radical_sqrt_of_product").safeProfileEligible());
        assertTrue(byId.get("rational_multiply_fractions").safeProfileEligible());
        assertFalse(byId.containsKey("calculus_exp_of_log"));
        assertFalse(byId.containsKey("calculus_log_of_exp"));
        assertEquals(
            RewriteApplicabilitySchema.CoverageStatus.OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            byId.get("rational_cancel_common_factor").status());
        assertEquals(
            RewriteApplicabilitySchema.CoverageStatus.OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            byId.get("polynomial_collect_like_terms").status());
    }

    @Test
    void explicitSchemasReproduceConcreteGuardRequirements() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        List<RewriteRule> rules = registry.rulesFor(List.of(
            RuleDomainRegistry.RATIONAL,
            RuleDomainRegistry.TRIGONOMETRIC,
            RuleDomainRegistry.LOGARITHMIC,
            RuleDomainRegistry.RADICAL,
            RuleDomainRegistry.CALCULUS_BASIC));

        for (Map.Entry<String, String> example : explicitExamples().entrySet()) {
            RewriteRule rule = rules.stream()
                .filter(candidate -> candidate.id().equals(example.getKey()))
                .findFirst().orElseThrow();
            RewriteApplicabilitySchema.CoverageEntry entry =
                RewriteApplicabilitySchema.coverageOf(rule);
            assertTrue(entry.safeProfileEligible(), rule.id());
            assertSame(rule, entry.schema().executor(), rule.id());

            Expr expression = parser.parseTerm(example.getValue());
            PatternMatchAnalyzer.Analysis analysis = new PatternMatchAnalyzer().analyze(
                entry.schema().pattern(), expression, entry.schema().recognitionProfile());
            assertTrue(analysis.matched(), rule.id());
            List<Assumption> declared = entry.schema().requiredAssumptions().stream()
                .map(template -> template.instantiate(analysis.bindings()))
                .toList();
            assertEquals(
                AssumptionSignature.ofAssumptions(rule.assumptions(expression)).normalizedAssumptions(),
                AssumptionSignature.ofAssumptions(declared).normalizedAssumptions(),
                rule.id());
        }
    }

    private static Map<String, String> explicitExamples() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("trig_tan_to_sin_over_cos", "tan(x)");
        values.put("calculus_exp_of_ln", "exp(ln(x))");
        values.put("calculus_ln_of_exp", "ln(exp(x))");
        values.put("calculus_exp_of_zero", "exp(0)");
        values.put("log_product_split", "log(x*y)");
        values.put("ln_product_split", "ln(x*y)");
        values.put("log_quotient_split", "log(x/y)");
        values.put("ln_quotient_split", "ln(x/y)");
        values.put("log_power_to_factor", "log(x^k)");
        values.put("ln_power_to_factor", "ln(x^k)");
        values.put("log_of_one_is_zero", "log(1)");
        values.put("ln_of_one_is_zero", "ln(1)");
        values.put("radical_sqrt_of_square_to_abs", "sqrt(x^2)");
        values.put("radical_sqrt_of_product", "sqrt(x*y)");
        values.put("radical_sqrt_of_zero", "sqrt(0)");
        values.put("radical_sqrt_of_one", "sqrt(1)");
        values.put("rational_multiply_fractions", "(a/b)*(c/d)");
        values.put("rational_divide_by_fraction", "a/(b/c)");
        return Map.copyOf(values);
    }

    private static class NoSchemaRule implements RewriteRule {
        private final String id;
        NoSchemaRule(String id) { this.id = id; }
        @Override
        public String id() { return id; }
        @Override
        public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return 0; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return false; }
        @Override
        public Expr apply(Expr subtree) { throw new IllegalArgumentException("Rule does not match subtree"); }
    }

    private static final class BadProviderRule extends NoSchemaRule
            implements RewriteApplicabilitySchemaProvider {
        BadProviderRule() { super("bad_provider_rule"); }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternRewriteRule other = new PatternRewriteRule(
                "other_rule", PatternExpr.var("A"), PatternExpr.var("A"),
                RewriteKind.SIMPLIFY, false, 0, true);
            return new RewriteApplicabilitySchema(
                "bad/v1", other, PatternExpr.var("A"), RecognitionProfile.exact(), List.of());
        }
    }

    private static final class GuardedPatternSubclass extends PatternRewriteRule {
        GuardedPatternSubclass() {
            super("guarded_pattern_without_schema",
                PatternExpr.op(BinaryOperator.DIV, PatternExpr.var("A"), PatternExpr.var("B")),
                PatternExpr.var("A"), RewriteKind.SIMPLIFY, false, -1, true);
        }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return List.of(Assumption.positive("B"));
        }
    }
}
