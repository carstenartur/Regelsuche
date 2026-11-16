package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Direct exact evaluation of c A^n x. Projected rows are independent of the starting state. */
final class DirectLinearObservation {
    private final List<ScaledMatrix> powers = new ArrayList<>();
    private final Map<Integer, ScaledVector> projected = new HashMap<>();

    DirectLinearObservation(Transition transition, List<Rational> observation, Work work) {
        for (var offset : transition.offset().values()) {
            work.add(1);
            if (!offset.isZero()) throw new IllegalArgumentException("direct control requires a linear transition");
        }
        if (transition.linear().columns() != observation.size()) throw new IllegalArgumentException("observation width mismatch");
        work.add(2);
        powers.add(scaleMatrix(transition.linear().rows(), work));
        projected.put(0, scaleVector(observation, work));
    }

    ScaledVector bind(List<Rational> initial, Work work) {
        work.add(1);
        if (initial.size() != powers.getFirst().entries().length) throw new IllegalArgumentException("initial state width mismatch");
        return scaleVector(initial, work);
    }

    List<Rational> valuesAt(ScaledVector initial, List<BigInteger> indices, Work work) {
        work.add(1);
        var answers = new ArrayList<Rational>(indices.size());
        for (var index : indices) {
            work.add(1);
            var row = rowAt(index.intValueExact(), work);
            var total = BigInteger.ZERO;
            for (int i = 0; i < row.entries().length; i++) {
                total = addProduct(total, row.entries()[i], initial.entries()[i], work);
            }
            work.add(1);
            answers.add(total.signum() == 0 ? Rational.ZERO : normalize(total, row.denominator(), initial.denominator(), work));
        }
        work.add(answers.size());
        return List.copyOf(answers);
    }

    private ScaledVector rowAt(int index, Work work) {
        work.add(1);
        if (index < 0) throw new IllegalArgumentException("negative observation index");
        var row = projected.get(index);
        if (row != null) return row;

        work.add(1);
        var prefix = projected.get(index - 1);
        int bit = 0;
        if (prefix == null) {
            work.add(1);
            bit = Integer.numberOfTrailingZeros(Integer.highestOneBit(index));
            prefix = rowAt(index - (1 << bit), work);
        }
        var power = powerAt(bit, work);
        var result = new ScaledVector(multiplyRow(prefix.entries(), power.entries(), work),
            multiply(prefix.denominator(), power.denominator(), work));
        work.add(1);
        projected.put(index, result);
        return result;
    }

    private ScaledMatrix powerAt(int bit, Work work) {
        while (powers.size() <= bit) {
            work.add(1);
            var previous = powers.getLast();
            var squared = new BigInteger[previous.entries().length][];
            for (int i = 0; i < squared.length; i++) {
                work.add(1);
                squared[i] = multiplyRow(previous.entries()[i], previous.entries(), work);
            }
            powers.add(new ScaledMatrix(squared, multiply(previous.denominator(), previous.denominator(), work)));
        }
        work.add(1);
        return powers.get(bit);
    }

    private static BigInteger[] multiplyRow(BigInteger[] row, BigInteger[][] matrix, Work work) {
        int dimension = row.length;
        var result = new BigInteger[dimension];
        for (int column = 0; column < dimension; column++) {
            work.add(1);
            var total = BigInteger.ZERO;
            for (int i = 0; i < dimension; i++) total = addProduct(total, row[i], matrix[i][column], work);
            result[column] = total;
        }
        return result;
    }

    private static ScaledMatrix scaleMatrix(List<List<Rational>> rows, Work work) {
        var denominator = BigInteger.ONE;
        for (var row : rows) denominator = commonDenominator(row, denominator, work);
        var entries = new BigInteger[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            work.add(1);
            entries[i] = scaleEntries(rows.get(i), denominator, work);
        }
        return new ScaledMatrix(entries, denominator);
    }

    private static ScaledVector scaleVector(List<Rational> values, Work work) {
        var denominator = commonDenominator(values, BigInteger.ONE, work);
        return new ScaledVector(scaleEntries(values, denominator, work), denominator);
    }

    private static BigInteger commonDenominator(List<Rational> entries, BigInteger denominator, Work work) {
        for (var entry : entries) {
            work.add(1);
            var divisor = entry.denominator();
            if (divisor.equals(BigInteger.ONE) || divisor.equals(denominator)) continue;
            work.add(2);
            denominator = multiply(denominator, divisor.divide(denominator.gcd(divisor)), work);
        }
        return denominator;
    }

    private static BigInteger[] scaleEntries(List<Rational> values, BigInteger denominator, Work work) {
        var entries = new BigInteger[values.size()];
        for (int i = 0; i < entries.length; i++) {
            work.add(1);
            var value = values.get(i);
            var numerator = value.numerator();
            if (!value.isZero() && !denominator.equals(value.denominator())) {
                work.add(1);
                numerator = multiply(numerator, denominator.divide(value.denominator()), work);
            }
            entries[i] = numerator;
        }
        return entries;
    }

    private static BigInteger addProduct(BigInteger total, BigInteger left, BigInteger right, Work work) {
        work.add(1);
        if (left.signum() == 0 || right.signum() == 0) return total;
        var product = multiply(left, right, work);
        work.add(1);
        return total.signum() == 0 ? product : total.add(product);
    }

    private static BigInteger multiply(BigInteger left, BigInteger right, Work work) {
        work.add(1);
        if (left.equals(BigInteger.ONE)) return right;
        if (right.equals(BigInteger.ONE)) return left;
        return left.multiply(right);
    }

    private static Rational normalize(BigInteger numerator, BigInteger leftDenominator, BigInteger rightDenominator, Work work) {
        work.add(1);
        return RecurrenceObservationArithmetic.checked(new Rational(numerator, multiply(leftDenominator, rightDenominator, work)));
    }

    record ScaledVector(BigInteger[] entries, BigInteger denominator) { }

    private record ScaledMatrix(BigInteger[][] entries, BigInteger denominator) { }
}
