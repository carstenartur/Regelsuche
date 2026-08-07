package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FusedPolynomialReductionTest {
    @Test
    void subtractMultipleMatchesExplicitMultiplyThenSubtract() {
        Random random = new Random(2026080706L);
        for (int example = 0; example < 500; example++) {
            Polynomial current = randomPolynomial(random);
            Polynomial divisor = randomPolynomial(random);
            Monomial multiplier = randomMonomial(random);
            Rational coefficient = Rational.of(random.nextInt(7) - 3);

            Polynomial explicit = current.subtract(divisor.multiply(multiplier, coefficient));
            Polynomial fused = current.subtractMultiple(divisor, multiplier, coefficient);

            assertEquals(explicit, fused, "example " + example);
        }
    }

    @Test
    void fusedHelpersPreserveCanonicalOneIdentity() {
        Polynomial canonicalOne = Polynomial.constant(Rational.ONE);

        assertSame(canonicalOne, Polynomial.zero().addTerm(Monomial.constant(), Rational.ONE));
        assertSame(
            canonicalOne,
            Polynomial.constant(Rational.of(2)).addTerm(Monomial.constant(), Rational.of(-1))
        );
    }

    @Test
    void fusedReducerMatchesPreviousUnfusedDefinition() {
        Random random = new Random(2026080707L);
        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < 250; example++) {
                List<Polynomial> basis = new ArrayList<>();
                int basisSize = 1 + random.nextInt(5);
                for (int i = 0; i < basisSize; i++) {
                    basis.add(randomPolynomial(random));
                }
                Polynomial target = randomPolynomial(random).multiply(randomPolynomial(random));

                PolynomialReducer.ReductionResult fused = new PolynomialReducer().reduce(target, basis, order, 10_000);
                ReferenceReduction reference = referenceReduce(target, basis, order, 10_000);

                assertFalse(fused.budgetExhausted(), "fused " + example + " / " + order.name());
                assertFalse(reference.budgetExhausted(), "reference " + example + " / " + order.name());
                assertEquals(reference.steps(), fused.steps(), "steps " + example + " / " + order.name());
                assertEquals(reference.remainder(), fused.remainder(), "remainder " + example + " / " + order.name());
            }
        }
    }

    private ReferenceReduction referenceReduce(
        Polynomial polynomial,
        List<Polynomial> basis,
        MonomialOrder order,
        int maxSteps
    ) {
        List<Polynomial> orderedBasis = basis.stream()
            .filter(value -> !value.isZero())
            .sorted(Comparator
                .comparing((Polynomial value) -> value.leadingTerm(order).orElseThrow().monomial(), order)
                .thenComparing(value -> value.toCanonicalString(order)))
            .toList();
        Polynomial remainder = Polynomial.zero();
        Polynomial current = polynomial;
        int steps = 0;
        while (!current.isZero()) {
            if (steps >= maxSteps) {
                return new ReferenceReduction(remainder, steps, true);
            }
            Term leading = current.leadingTerm(order).orElseThrow();
            Polynomial divisor = null;
            Term divisorLeading = null;
            for (Polynomial candidate : orderedBasis) {
                Term candidateLeading = candidate.leadingTerm(order).orElseThrow();
                if (candidateLeading.monomial().divides(leading.monomial())) {
                    divisor = candidate;
                    divisorLeading = candidateLeading;
                    break;
                }
            }
            if (divisor != null) {
                current = current.subtract(divisor.multiply(
                    leading.monomial().divideBy(divisorLeading.monomial()),
                    leading.coefficient().divide(divisorLeading.coefficient())
                ));
            } else {
                Polynomial leadingPolynomial = Polynomial.term(leading.monomial(), leading.coefficient());
                remainder = remainder.add(leadingPolynomial);
                current = current.subtract(leadingPolynomial);
            }
            steps++;
        }
        return new ReferenceReduction(remainder, steps, false);
    }

    private Polynomial randomPolynomial(Random random) {
        Polynomial result = Polynomial.zero();
        int termCount = 1 + random.nextInt(5);
        for (int i = 0; i < termCount; i++) {
            int coefficient = random.nextInt(7) - 3;
            if (coefficient == 0) {
                coefficient = 1;
            }
            result = result.add(Polynomial.term(randomMonomial(random), Rational.of(coefficient)));
        }
        return result.isZero() ? Polynomial.variable("x") : result;
    }

    private Monomial randomMonomial(Random random) {
        Map<String, Integer> powers = new HashMap<>();
        for (String variable : new String[]{"a", "b", "x", "y"}) {
            int exponent = random.nextInt(4);
            if (exponent > 0) {
                powers.put(variable, exponent);
            }
        }
        return new Monomial(powers);
    }

    private record ReferenceReduction(Polynomial remainder, int steps, boolean budgetExhausted) {
    }
}
