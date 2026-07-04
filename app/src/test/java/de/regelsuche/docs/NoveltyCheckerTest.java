package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NoveltyCheckerTest {
    private final NoveltyChecker checker = new NoveltyChecker();

    @Test
    void whitespaceVariantsAreDuplicates() {
        NoveltyChecker.Candidate first = candidate("first", "factorization", "x + y", "y + x", "commute", List.of());
        NoveltyChecker.Candidate second = candidate("second", "factorization", "x+y", "y+x", "commute", List.of());

        NoveltyChecker.NoveltyResult result = checker.classify(second, List.of(first));

        assertEquals(NoveltyStatus.DUPLICATE, result.status());
        assertEquals("first", result.matchedCandidateId());
    }

    @Test
    void alphaEquivalentVariantsAreDetected() {
        NoveltyChecker.Candidate first = candidate(
            "sin-x",
            "trig-power-reduction",
            "1 - sin(x)^2",
            "cos(x)^2",
            "trig_power_reduction",
            List.of()
        );
        NoveltyChecker.Candidate second = candidate(
            "sin-y",
            "trig-power-reduction",
            "1 - sin(y)^2",
            "cos(y)^2",
            "trig_power_reduction",
            List.of()
        );

        NoveltyChecker.NoveltyResult result = checker.classify(second, List.of(first));

        assertEquals(NoveltyStatus.ALPHA_EQUIVALENT, result.status());
        assertEquals("sin-x", result.matchedCandidateId());
    }

    @Test
    void sameFamilyAndOperatorWithDifferentPatternIsVariant() {
        NoveltyChecker.Candidate first = candidate(
            "qf-small",
            "quadratic-factorization",
            "x^2 + 3*x + 2",
            "(x + 1) * (x + 2)",
            "quadratic_factorization",
            List.of()
        );
        NoveltyChecker.Candidate second = candidate(
            "qf-negative",
            "quadratic-factorization",
            "y^2 - y - 6",
            "(y - 3) * (y + 2)",
            "quadratic_factorization",
            List.of()
        );

        NoveltyChecker.NoveltyResult result = checker.classify(second, List.of(first));

        assertEquals(NoveltyStatus.VARIANT, result.status());
        assertEquals("qf-small", result.matchedCandidateId());
    }

    @Test
    void genuinelyNewOperatorFamilyCandidateIsNew() {
        NoveltyChecker.Candidate previous = candidate(
            "trig",
            "trig-power-reduction",
            "1 - sin(x)^2",
            "cos(x)^2",
            "trig_power_reduction",
            List.of()
        );
        NoveltyChecker.Candidate fresh = candidate(
            "qf",
            "quadratic-factorization",
            "x^2 + 3*x + 2",
            "(x + 1) * (x + 2)",
            "quadratic_factorization",
            List.of()
        );

        NoveltyChecker.NoveltyResult result = checker.classify(fresh, List.of(previous));

        assertEquals(NoveltyStatus.NEW, result.status());
    }

    @Test
    void explicitlyKnownRuleIdsBlockNovelty() {
        NoveltyChecker knownRuleChecker = new NoveltyChecker(Set.of("known.factor"), List.of());
        NoveltyChecker.Candidate candidate = candidate(
            "known",
            "factorization",
            "x^2 - 1",
            "(x - 1) * (x + 1)",
            "difference_of_squares",
            List.of("known.factor")
        );

        NoveltyChecker.NoveltyResult result = knownRuleChecker.classify(candidate, List.of());

        assertEquals(NoveltyStatus.KNOWN_RULE, result.status());
    }

    @Test
    void differentFunctionSymbolsAreNotAlphaEquivalent() {
        NoveltyChecker.Candidate lnCandidate = candidate(
            "ln-x",
            "log-rule",
            "ln(x)",
            "log(x)",
            "log_identity",
            List.of()
        );
        NoveltyChecker.Candidate fooCandidate = candidate(
            "foo-y",
            "log-rule",
            "foo(y)",
            "bar(y)",
            "log_identity",
            List.of()
        );

        NoveltyChecker.NoveltyResult result = checker.classify(fooCandidate, List.of(lnCandidate));

        assertEquals(NoveltyStatus.VARIANT, result.status());
    }

    private NoveltyChecker.Candidate candidate(
        String id,
        String family,
        String input,
        String target,
        String operator,
        List<String> rulePath
    ) {
        return new NoveltyChecker.Candidate(id, family, input, target, operator, rulePath);
    }
}
