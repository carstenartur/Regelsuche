package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MonomialOrderMergedIterationTest {
    @Test
    void lexOrderMatchesPreviousDefinitionForDeterministicMonomials() {
        Random random = new Random(2026080701L);
        LexOrder optimized = new LexOrder();
        for (int i = 0; i < 5_000; i++) {
            Monomial left = randomMonomial(random);
            Monomial right = randomMonomial(random);
            assertEquals(
                referenceLex(left, right),
                optimized.compare(left, right),
                () -> left.key() + " / " + right.key()
            );
        }
    }

    @Test
    void gradedReverseLexOrderMatchesPreviousDefinitionForDeterministicMonomials() {
        Random random = new Random(2026080702L);
        GradedReverseLexOrder optimized = new GradedReverseLexOrder();
        for (int i = 0; i < 5_000; i++) {
            Monomial left = randomMonomial(random);
            Monomial right = randomMonomial(random);
            assertEquals(
                referenceGradedReverseLex(left, right),
                optimized.compare(left, right),
                () -> left.key() + " / " + right.key()
            );
        }
    }

    @Test
    void optimizedOrdersRemainAntisymmetric() {
        Random random = new Random(2026080703L);
        MonomialOrder[] orders = {new LexOrder(), new GradedReverseLexOrder()};
        for (MonomialOrder order : orders) {
            for (int i = 0; i < 2_000; i++) {
                Monomial left = randomMonomial(random);
                Monomial right = randomMonomial(random);
                int leftToRight = order.compare(left, right);
                int rightToLeft = order.compare(right, left);
                assertEquals(-Integer.signum(leftToRight), Integer.signum(rightToLeft));
            }
        }
    }

    private Monomial randomMonomial(Random random) {
        Map<String, Integer> powers = new HashMap<>();
        for (String variable : new String[]{"a", "b", "c", "d", "x", "y", "z"}) {
            int exponent = random.nextInt(5);
            if (exponent > 0) {
                powers.put(variable, exponent);
            }
        }
        return new Monomial(powers);
    }

    private int referenceLex(Monomial left, Monomial right) {
        TreeSet<String> variables = new TreeSet<>();
        variables.addAll(left.powers().keySet());
        variables.addAll(right.powers().keySet());
        for (String variable : variables) {
            int comparison = Integer.compare(right.exponentOf(variable), left.exponentOf(variable));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private int referenceGradedReverseLex(Monomial left, Monomial right) {
        int degreeComparison = Integer.compare(right.totalDegree(), left.totalDegree());
        if (degreeComparison != 0) {
            return degreeComparison;
        }
        TreeSet<String> sorted = new TreeSet<>();
        sorted.addAll(left.powers().keySet());
        sorted.addAll(right.powers().keySet());
        ArrayList<String> variables = new ArrayList<>(sorted);
        Collections.reverse(variables);
        for (String variable : variables) {
            int comparison = Integer.compare(left.exponentOf(variable), right.exponentOf(variable));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }
}
