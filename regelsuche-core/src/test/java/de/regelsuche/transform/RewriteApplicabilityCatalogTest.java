package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.rules.RuleDomainRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RewriteApplicabilityCatalogTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void neverInfersSchemaFromAlgorithmicRuleMetadata() {
        RewriteRule rule = new NoSchemaRule("log_product_split");
        RewriteApplicabilityCatalog.Entry entry =
            RewriteApplicabilityCatalog.inspect(rule);

        assertFalse(entry.safeProfileEligible());
        assertEquals(
            RewriteApplicabilityCatalog.Status
                .OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            entry.status());
        assertEquals("NO_EXPLICIT_APPLICABILITY_SCHEMA", entry.exclusionReason());
    }

    @Test
    void explicitProviderMustBindTheExactExecutorObject() {
        BadProviderRule rule = new BadProviderRule();
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> RewriteApplicabilityCatalog.inspect(rule));
        assertTrue(error.getMessage().contains("exact executor object"));
    }

    @Test
    void domainCoverageRetainsPositiveAndNegativeEligibilityDecisions() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        List<RewriteApplicabilityCatalog.Entry> coverage =
            registry.applicabilityCoverageFor(List.of(
                RuleDomainRegistry.POLYNOMIAL,
                RuleDomainRegistry.RATIONAL,
                RuleDomainRegistry.TRIGONOMETRIC,
                RuleDomainRegistry.LOGARITHMIC,
                RuleDomainRegistry.RADICAL,
                RuleDomainRegistry.CALCULUS_BASIC));
        Map<String, RewriteApplicabilityCatalog.Entry> byId = new LinkedHashMap<>();
        coverage.forEach(entry -> byId.put(entry.rule().id(), entry));

        assertTrue(byId.get("trig_tan_to_sin_over_cos").safeProfileEligible());
        assertTrue(byId.get("calculus_exp_of_ln").safeProfileEligible());
        assertTrue(byId.get("log_product_split").safeProfileEligible());
        assertTrue(byId.get("radical_sqrt_of_product").safeProfileEligible());
        assertTrue(byId.get("rational_multiply_fractions").safeProfileEligible());
        assertTrue(byId.get("rational_divide_by_fraction").safeProfileEligible());
        assertFalse(byId.containsKey("calculus_exp_of_log"));
        assertFalse(byId.containsKey("calculus_log_of_exp"));

        assertEquals(
            RewriteApplicabilityCatalog.Status
                .OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            byId.get("rational_cancel_common_factor").status());
        assertEquals(
            RewriteApplicabilityCatalog.Status
                .OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            byId.get("polynomial_collect_like_terms").status());
        assertEquals(
            RewriteApplicabilityCatalog.Status
                .OUTSIDE_SAFE_PROFILE_NO_EXPLICIT_SCHEMA,
            byId.get("polynomial_combine_like_terms").status());
    }

    @Test
    void explicitAlgorithmicSchemasReproduceConcreteGuardRequirements() {
        RuleDomainRegistry registry = new RuleDomainRegistry();
        List<RewriteRule> rules = registry.rulesFor(List.of(
            RuleDomainRegistry.RATIONAL,
            RuleDomainRegistry.TRIGONOMETRIC,
            RuleDomainRegistry.LOGARITHMIC,
            RuleDomainRegistry.RADICAL,
            RuleDomainRegistry.CALCULUS_BASIC));

        for (Map.Entry<String, String> example
                : explicitAlgorithmicExamples().entrySet()) {
            RewriteRule rule = rules.stream()
                .filter(candidate -> candidate.id().equals(example.getKey()))
                .findFirst()
                .orElseThrow();
            RewriteApplicabilityCatalog.Entry entry =
                RewriteApplicabilityCatalog.inspect(rule);
            assertTrue(entry.safeProfileEligible(), rule.id());
            assertSame(rule, entry.schema().executor(), rule.id());

            Expr expression = parser.parseTerm(example.getValue());
            PatternMatchAnalyzer.Analysis analysis = new PatternMatchAnalyzer()
                .analyze(
                    entry.schema().pattern(),
                    expression,
                    entry.schema().recognitionProfile());
            assertTrue(analysis.matched(), rule.id());
            List<Assumption> declared = entry.schema().requiredAssumptions().stream()
                .map(template -> template.instantiate(analysis.bindings()))
                .toList();
            assertEquals(
                AssumptionSignature.ofAssumptions(rule.assumptions(expression))
                    .normalizedAssumptions(),
                AssumptionSignature.ofAssumptions(declared)
                    .normalizedAssumptions(),
                rule.id());
        }
    }

    private static Map<String, String> explicitAlgorithmicExamples() {
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

        private NoSchemaRule(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
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
            return 0;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return false;
        }

        @Override
        public Expr apply(Expr subtree) {
            throw new IllegalArgumentException("Rule does not match subtree");
        }
    }

    private static final class BadProviderRule extends NoSchemaRule
            implements RewriteApplicabilitySchemaProvider {
        private BadProviderRule() {
            super("bad_provider_rule");
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternRewriteRule other = new PatternRewriteRule(
                "other_rule",
                PatternExpr.var("A"),
                PatternExpr.var("A"),
                RewriteKind.SIMPLIFY,
                false,
                0,
                true);
            return new RewriteApplicabilitySchema(
                "bad/v1",
                other,
                PatternExpr.var("A"),
                RecognitionProfile.exact(),
                List.of());
        }
    }
}
