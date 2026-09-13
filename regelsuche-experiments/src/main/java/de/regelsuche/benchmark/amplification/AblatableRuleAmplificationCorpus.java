package de.regelsuche.benchmark.amplification;

import de.regelsuche.search.reachability.AblatableRulePreparationRunner.Source;
import java.util.ArrayList;
import java.util.List;
import static de.regelsuche.benchmark.amplification.AblatableRuleAmplificationExperiment.*;

/** Public development controls, separately versioned from the historical 11-case pilot. */
public final class AblatableRuleAmplificationCorpus {
    public static final String ID = "regelsuche.amplification-public-three-family/v1";
    public record Corpus(List<Source> sources, List<Qualification> qualification) {
        public Corpus { sources = List.copyOf(sources); qualification = List.copyOf(qualification); }
    }
    private AblatableRuleAmplificationCorpus() { }

    public static Corpus publicCorpus() {
        var sources = new ArrayList<Source>();
        var labels = new ArrayList<Qualification>();
        add(sources, labels, "trig-direct", "trigonometry", "sympy.trig.pythagorean", "sin(x)^2 + cos(x)^2", List.of(), "1", "DIRECT_POSITIVE");
        add(sources, labels, "trig-ac", "trigonometry", "sympy.trig.pythagorean", "cos(x)^2 + sin(x)^2", List.of(), "1", "OBSCURED_POSITIVE");
        add(sources, labels, "trig-hidden", "trigonometry", "sympy.trig.pythagorean", "((sin(x)*a)/a)^2 + ((cos(x)*b)/b)^2", List.of("a != 0", "b != 0"), "1", "OBSCURED_POSITIVE");
        add(sources, labels, "trig-exact-quotient", "trigonometry", "sympy.trig.pythagorean", "sin((x^2-1)/(x-1))^2 + cos(x+1)^2", List.of("x - 1 != 0"), "1", "EXACT_PREPARATION_POSITIVE");
        add(sources, labels, "trig-near-miss", "trigonometry", "sympy.trig.pythagorean", "((sin(x)*a)/a)^2 + ((cos(y)*b)/b)^2", List.of("a != 0", "b != 0"), "", "NEAR_MISS");
        add(sources, labels, "trig-unknown-guard", "trigonometry", "sympy.trig.pythagorean", "((sin(x)*a)/a)^2 + cos(x)^2", List.of(), "", "GUARD_NEGATIVE");

        add(sources, labels, "poly-direct", "polynomial", "sympy.poly.factor.diff_squares", "x^2-y^2", List.of(), "(x-y)*(x+y)", "DIRECT_POSITIVE");
        add(sources, labels, "poly-hidden", "polynomial", "sympy.poly.factor.diff_squares", "(x^2*a)/a - (y^2*b)/b", List.of("a != 0", "b != 0"), "(x-y)*(x+y)", "OBSCURED_POSITIVE");
        add(sources, labels, "poly-exact-quotient", "polynomial", "sympy.poly.factor.diff_squares", "(x^3+x^2)/(x+1)-y^2", List.of("x + 1 != 0"), "(x-y)*(x+y)", "EXACT_PREPARATION_POSITIVE");
        add(sources, labels, "poly-near-miss", "polynomial", "sympy.poly.factor.diff_squares", "x^2+y^2", List.of(), "", "NEAR_MISS");
        add(sources, labels, "poly-false-guard", "polynomial", "sympy.poly.factor.diff_squares", "(x^2*a)/a - (y^2*b)/b", List.of("a = 0", "b != 0"), "", "GUARD_NEGATIVE");

        List<String> rational = List.of("n != 0", "n + 1 != 0", "n*(n+1) != 0");
        add(sources, labels, "rational-direct", "rational", "sympy.rational.partial_fraction.telescoping", "1/(n*(n+1))", rational, "1/n - 1/(n+1)", "DIRECT_POSITIVE");
        add(sources, labels, "rational-hidden", "rational", "sympy.rational.partial_fraction.telescoping", "1/(((n*a)/a)*(((n+1)*b)/b))", List.of("n != 0", "n + 1 != 0", "n*(n+1) != 0", "a != 0", "b != 0", "((n*a)/a)*(((n+1)*b)/b) != 0"), "1/n - 1/(n+1)", "OBSCURED_POSITIVE");
        add(sources, labels, "rational-exact-quotient", "rational", "sympy.rational.partial_fraction.telescoping", "1/(((n^2+n)/(n+1))*(n+1))", List.of("n != 0", "n + 1 != 0", "n*(n+1) != 0", "((n^2+n)/(n+1))*(n+1) != 0"), "1/n - 1/(n+1)", "EXACT_PREPARATION_POSITIVE");
        add(sources, labels, "rational-near-miss", "rational", "sympy.rational.partial_fraction.telescoping", "1/(n*(n+2))", List.of("n != 0", "n + 2 != 0", "n*(n+2) != 0"), "", "NEAR_MISS");
        add(sources, labels, "rational-false-guard", "rational", "sympy.rational.partial_fraction.telescoping", "1/(n*(n+1))", List.of("n = 0", "n + 1 != 0"), "", "GUARD_NEGATIVE");
        return new Corpus(sources, labels);
    }

    private static void add(List<Source> sources, List<Qualification> labels, String id, String family, String principal,
                            String expression, List<String> assumptions, String reference, String kind) {
        sources.add(new Source(expression, assumptions));
        labels.add(new Qualification(id, family, principal, reference, kind));
    }
}
