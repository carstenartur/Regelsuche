package de.regelsuche.math.algorithms.equivalence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded exact identities for polynomial trace auditing and split exclusions.
 * Reuses the source-exact residual projection and existing polynomial model.
 * Alpha identity minimizes over all variable permutations, not lexical renaming.
 * These identities are exclusion keys, never evidence of a new theorem.
 */
public final class ExactPolynomialAnalysis {
    public static final String REVISION = "regelsuche.exact-polynomial-analysis/v1";
    public static final int MAX_VARIABLES = 4;
    private final ExactResidualPolynomialArithmetic arithmetic = new ExactResidualPolynomialArithmetic();

    public void requireEquivalent(String source, String candidate) {
        if (!arithmetic.parse(source).equals(arithmetic.parse(candidate))) {
            throw new IllegalArgumentException("polynomial normal forms differ");
        }
    }

    /** Unsupported or excessive inputs throw; they never establish disjointness. */
    public String alphaIdentity(String expression) {
        Polynomial polynomial = arithmetic.parse(expression);
        List<String> variables = polynomial.variables().stream().sorted().toList();
        if (variables.size() > MAX_VARIABLES) {
            throw new IllegalArgumentException("alpha identity supports at most four polynomial variables");
        }
        List<String> alternatives = new ArrayList<>();
        permute(polynomial, variables, new ArrayList<>(), alternatives);
        return REVISION + ":" + alternatives.stream().min(String::compareTo).orElseThrow();
    }

    private static void permute(Polynomial polynomial, List<String> variables,
            List<String> order, List<String> results) {
        if (order.size() == variables.size()) {
            Map<String, String> names = new HashMap<>();
            for (int i = 0; i < order.size(); i++) names.put(order.get(i), "v" + i);
            Polynomial renamed = Polynomial.zero();
            for (var term : polynomial.terms().entrySet()) {
                Map<String, Integer> powers = new HashMap<>();
                term.getKey().powers().forEach((name, exponent) -> powers.put(names.get(name), exponent));
                renamed = renamed.addTerm(new Monomial(powers), term.getValue());
            }
            results.add(renamed.toCanonicalString());
            return;
        }
        for (String variable : variables) {
            if (!order.contains(variable)) {
                order.add(variable);
                permute(polynomial, variables, order, results);
                order.removeLast();
            }
        }
    }
}
