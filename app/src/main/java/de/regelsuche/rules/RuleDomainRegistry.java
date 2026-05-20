package de.regelsuche.rules;

import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of available {@link RuleDomain rule domains}.
 *
 * <p>The {@code core} domain is always available and corresponds to
 * {@link AstRewriteTransformationEngine#defaultRules()}. Additional domains
 * (polynomial, rational, ...) are filtered/extended subsets sharing the same
 * atomic rule contract.</p>
 */
public final class RuleDomainRegistry {
    public static final String CORE = "core";
    public static final String POLYNOMIAL = "polynomial";
    public static final String RATIONAL = "rational";
    public static final String TRIGONOMETRIC = "trigonometric";
    public static final String LOGARITHMIC = "logarithmic";
    public static final String RADICAL = "radical";
    public static final String CALCULUS_BASIC = "calculus_basic";

    private final Map<String, RuleDomain> domains = new LinkedHashMap<>();

    public RuleDomainRegistry() {
        register(new SimpleRuleDomain(CORE, "Atomare Grundregeln",
            AstRewriteTransformationEngine.defaultRules()));
        register(new SimpleRuleDomain(POLYNOMIAL, "Polynomregeln (Ausmultiplizieren, Sammeln, Potenzen, Normalform)",
            PolynomialRules.rules()));
        register(new SimpleRuleDomain(RATIONAL, "Bruchregeln (Kürzen, gemeinsamer Nenner, Multiplikation, Division)",
            RationalRules.rules()));
        register(new SimpleRuleDomain(TRIGONOMETRIC, "Trigonometrische Identitäten (Pythagoras, Doppelwinkel)",
            TrigonometricRules.rules()));
        register(new SimpleRuleDomain(LOGARITHMIC, "Logarithmische Identitäten mit Positivitäts-Assumptions",
            LogarithmicRules.rules()));
        register(new SimpleRuleDomain(RADICAL, "Wurzelregeln (sqrt(a^2)=abs(a), Produkt-/Quotientenregel)",
            RadicalRules.rules()));
        register(new SimpleRuleDomain(CALCULUS_BASIC, "Basis Analysis (exp/log-Inversion)",
            CalculusBasicRules.rules()));
    }

    public RuleDomainRegistry register(RuleDomain domain) {
        Objects.requireNonNull(domain, "domain");
        domains.put(domain.name(), domain);
        return this;
    }

    public Optional<RuleDomain> get(String name) {
        return Optional.ofNullable(domains.get(name));
    }

    public List<RuleDomain> all() {
        return List.copyOf(domains.values());
    }

    /**
     * @return concatenated rule list of all selected domain names; unknown
     *         names are silently ignored. Duplicate rule IDs across domains are
     *         deduplicated.
     */
    public List<RewriteRule> rulesFor(List<String> domainNames) {
        List<RewriteRule> rules = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : domainNames) {
            RuleDomain domain = domains.get(name);
            if (domain == null) {
                continue;
            }
            for (RewriteRule rule : domain.rules()) {
                if (seen.add(rule.id())) {
                    rules.add(rule);
                }
            }
        }
        return rules;
    }

    private record SimpleRuleDomain(String name, String description, List<RewriteRule> rules) implements RuleDomain {
        private SimpleRuleDomain {
            rules = List.copyOf(rules);
        }
    }
}
