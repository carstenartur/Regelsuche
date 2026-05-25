package de.regelsuche.validation;

import java.util.List;
import java.util.Optional;

/**
 * Stable port for counterexample search over a candidate equivalence.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): downstream validation, mining and proof features
 * depend on this abstraction instead of a concrete SMT/random-search
 * backend so the mathematical core remains infrastructure-agnostic.
 *
 * <p>{@link RewriteRuleValidationService} provides the existing random-
 * sampling based counterexample search; future SMT/proof-based engines
 * implement the same port.
 */
public interface CounterexampleSearchService {

    /**
     * Try to find a counterexample to {@code leftExpression == rightExpression}.
     *
     * @return a counterexample assignment if one was found, or
     *     {@link Optional#empty()} if the search exhausted its budget without
     *     finding one. Returning {@link Optional#empty()} is <em>not</em> a
     *     proof of equivalence.
     */
    Optional<Counterexample> search(String leftExpression, String rightExpression);

    /**
     * A concrete counterexample: a variable assignment plus the resulting
     * inequality (e.g. {@code "x=2 ⇒ left=4, right=5"}).
     *
     * @param assignments variable bindings causing the inequality (e.g.
     *     {@code "x=2"}, {@code "y=-1"})
     * @param leftValue evaluated value of the left expression
     * @param rightValue evaluated value of the right expression
     */
    record Counterexample(List<String> assignments, String leftValue, String rightValue) {
        public Counterexample {
            assignments = List.copyOf(assignments);
        }
    }
}
