package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PreparedReducerIndexTest {
    @Test
    void preparedBasisCachesOrderPreservingDegreeViews() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        Polynomial w = Polynomial.variable("w");
        Polynomial v = Polynomial.variable("v");
        PolynomialReducer reducer = new PolynomialReducer();
        PolynomialReducer.PreparedBasis prepared = reducer.prepare(
            List.of(x.pow(5), y.pow(4), z.pow(3), w.pow(2), v),
            new LexOrder()
        );

        assertEquals(5, prepared.reducerCount());
        assertEquals(2, prepared.eligibleReducerCount(2));
        assertEquals(1, prepared.cachedDegreeViewCount());
        assertEquals(2, prepared.eligibleReducerCount(2));
        assertEquals(1, prepared.cachedDegreeViewCount());
        assertEquals(3, prepared.eligibleReducerCount(3));
        assertEquals(2, prepared.cachedDegreeViewCount());
        assertEquals(5, prepared.eligibleReducerCount(5));
        assertEquals(2, prepared.cachedDegreeViewCount());
    }

    @Test
    void indexedReductionMatchesPreviousLinearReducerDefinition() {
        Random random = new Random(2026080705L);
        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < 250; example++) {
                List<Polynomial> basis = new ArrayList<>();
                int basisSize = 1 + random.nextInt(6);
                for (int i = 0; i < basisSize; i++) {
                    basis.add(randomPolynomial(random));
                }
                Polynomial target = randomPolynomial(random).multiply(randomPolynomial(random));

                PolynomialReducer.ReductionResult optimized = new PolynomialReducer().reduce(
                    target,
                    basis,
                    order,
                    10_000
                );
                ReferenceReduction reference = referenceReduce(target, basis, order, 10_000);

                assertFalse(optimized.budgetExhausted(), "optimized " + example + " / " + order.name());
                assertFalse(reference.budgetExhausted(), "reference " + example + " / " + order.name());
                assertEquals(reference.steps(), optimized.steps(), "steps " + example + " / " + order.name());
                assertEquals(
                    reference.remainder().toCanonicalString(order),
                    optimized.remainder().toCanonicalString(order),
                    "remainder " + example + " / " + order.name()
                );
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
        Map<Monomial, Rational> terms = new HashMap<>();
        int termCount = 1 + random.nextInt(5);
        for (int i = 0; i < termCount; i++) {
            Map<String, Integer> powers = new HashMap<>();
            for (String variable : new String[]{"a", "b", "x", "y", "z"}) {
                int exponent = random.nextInt(4);
                if (exponent > 0) {
                    powers.put(variable, exponent);
                }
            }
            int coefficient = random.nextInt(7) - 3;
            if (coefficient == 0) {
                coefficient = 1;
            }
            terms.merge(new Monomial(powers), Rational.of(coefficient), Rational::add);
        }
        Polynomial result = Polynomial.zero();
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            result = result.add(Polynomial.term(entry.getKey(), entry.getValue()));
        }
        return result.isZero() ? Polynomial.variable("x") : result;
    }

    private record ReferenceReduction(Polynomial remainder, int steps, boolean budgetExhausted) {
    }
}
