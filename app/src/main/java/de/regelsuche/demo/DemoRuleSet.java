package de.regelsuche.demo;

import de.regelsuche.rules.RationalRules;
import de.regelsuche.rules.PolynomialRules;
import de.regelsuche.rules.TrigonometricRules;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule set used by the killer-demo flow.
 *
 * <p>Bundles {@link AstRewriteTransformationEngine#defaultRules()} (atomic
 * core rewrites: distributivity, power-to-product, factoring, canonical
 * normalisation, ...) together with the curated domain rules required to
 * reach the four signature results of the demo:</p>
 *
 * <ul>
 *   <li>{@link TrigonometricRules#rules()} — supplies
 *       {@code sin(A)^2 + cos(A)^2 -> 1} and the symmetric variant.</li>
 *   <li>{@link RationalRules.CancelCommonFactorRule} — supplies
 *       {@code (A*B)/(A*C) -> B/C} with a {@code C != 0} assumption.</li>
 *   <li>{@link RationalRules.MultiplyFractionsRule},
 *       {@link RationalRules.DivideByFractionRule} — basic fraction handling
 *       needed when the canonicalizer rearranges intermediate states.</li>
 * </ul>
 *
 * <p>The combination is deduplicated by rule id so callers can pass a single
 * list to {@link AstRewriteTransformationEngine}.</p>
 */
public final class DemoRuleSet {

    private DemoRuleSet() {
    }

    public static List<RewriteRule> rules() {
        Map<String, RewriteRule> byId = new LinkedHashMap<>();
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules()) {
            byId.put(rule.id(), rule);
        }
        for (RewriteRule rule : PolynomialRules.rules()) {
            byId.putIfAbsent(rule.id(), rule);
        }
        for (RewriteRule rule : TrigonometricRules.rules()) {
            byId.putIfAbsent(rule.id(), rule);
        }
        byId.putIfAbsent("rational_cancel_common_factor", new RationalRules.CancelCommonFactorRule());
        return new ArrayList<>(byId.values());
    }
}
