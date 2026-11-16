package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.MAX_RATIONAL_BITS;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.checked;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Observation;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Transition;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.ArithmeticLimit;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Compiles projected rows once using integer numerators and shared denominators. */
final class DirectIndexedObservation {
    private final List<Integer> coordinates;
    private final boolean hasConstant;
    private final List<Row> projections;
    private final List<Integer> requestOrder;

    private DirectIndexedObservation(List<Integer> coordinates, boolean hasConstant, List<Row> projections,
            List<Integer> requestOrder) {
        this.coordinates = coordinates;
        this.hasConstant = hasConstant;
        this.projections = projections;
        this.requestOrder = requestOrder;
    }

    static DirectIndexedObservation prepare(Transition transition, Observation observation, List<BigInteger> indices,
            List<Integer> coordinates, Work work) {
        boolean hasConstant = !observation.offset().isZero();
        for (int coordinate : coordinates) {
            work.add(1);
            hasConstant |= !transition.offset().get(coordinate).isZero();
        }
        var weights = project(observation.weights(), coordinates, hasConstant, observation.offset(), work);
        var scaled = tryScaleRow(weights, work);
        Row row = scaled != null ? scaled : new RationalRow(weights);
        work.add(1L + indices.size());
        var rows = new ArrayList<Row>();
        var requestOrder = new ArrayList<Integer>(indices.size());
        var positions = new IdentityHashMap<Row, Integer>();
        var projected = new TreeMap<BigInteger, Row>((left, right) -> compareIndices(left, right, work));
        projected.put(BigInteger.ZERO, row);
        var powers = new ArrayList<Matrix>();
        for (BigInteger index : indices) {
            work.add(1);
            if (!coordinates.isEmpty() && index.signum() > 0 && powers.isEmpty()) {
                powers.add(scaleMatrix(transition, coordinates, hasConstant, work));
            }
            var projection = coordinates.isEmpty() ? row : rowAt(index, projected, powers, work);
            var position = positions.get(projection);
            if (position == null) {
                work.add(1);
                position = rows.size();
                rows.add(projection);
                positions.put(projection, position);
            }
            requestOrder.add(position);
        }
        work.add(1L + rows.size() + requestOrder.size());
        return new DirectIndexedObservation(coordinates, hasConstant, List.copyOf(rows), List.copyOf(requestOrder));
    }

    List<Rational> valuesFor(ExactVector initialState, Work work) {
        if (requestOrder.isEmpty()) return List.of();
        var initial = project(initialState, coordinates, hasConstant, Rational.ONE, work);
        var scaled = tryScaleRow(initial, work);
        work.add(1L + projections.size());
        var distinct = new ArrayList<Rational>(projections.size());
        for (var row : projections) {
            work.add(1);
            distinct.add(scaled != null && row instanceof ScaledRow projection
                ? scaledDot(projection, scaled, work) : dot(fractions(row, work), initial, work));
        }
        if (requestOrder.size() == projections.size()) return List.copyOf(distinct);
        work.add(1L + requestOrder.size());
        var results = new ArrayList<Rational>(requestOrder.size());
        for (int position : requestOrder) results.add(distinct.get(position));
        return List.copyOf(results);
    }

    private static Rational scaledDot(ScaledRow row, ScaledRow initial, Work work) {
        var sum = BigInteger.ZERO;
        for (int i = 0; i < row.entries().length; i++) sum = addProduct(sum, row.entries()[i], initial.entries()[i], work);
        return sum.signum() == 0 ? Rational.ZERO
            : checked(new Rational(sum, multiply(row.denominator(), initial.denominator(), work)));
    }

    private static Rational dot(List<Rational> row, List<Rational> initial, Work work) {
        var sum = Rational.ZERO;
        for (int i = 0; i < row.size(); i++) {
            sum = RecurrenceObservationArithmetic.addProduct(sum, row.get(i), initial.get(i), work);
        }
        return sum;
    }

    private static ScaledRow tryScaleRow(List<Rational> values, Work work) {
        try {
            return scaleRow(values, work);
        } catch (ArithmeticLimit doesNotShareDenominator) {
            // Individual coordinates can fit even when a common denominator is too large.
            return null;
        }
    }

    private static ScaledRow scaleRow(List<Rational> values, Work work) {
        var denominator = commonDenominator(values, BigInteger.ONE, work);
        return new ScaledRow(scale(values, denominator, work), denominator);
    }

    static int compareIndices(BigInteger left, BigInteger right, Work work) {
        work.add(1L + Math.min(left.bitLength(), right.bitLength()) / Integer.SIZE);
        return left.compareTo(right);
    }

    private static Row rowAt(BigInteger index, Map<BigInteger, Row> projected, List<Matrix> powers,
            Work work) {
        // Prefer an already prepared adjacent prefix. Otherwise clear set bits until a known row is reached.
        work.add(1);
        var row = projected.get(index);
        if (row != null) return row;
        var missing = new ArrayList<ProjectionStep>();
        var prefix = index;
        while (row == null) {
            work.add(1L + prefix.bitLength() / Long.SIZE);
            var previous = prefix.subtract(BigInteger.ONE);
            int power = projected.containsKey(previous) ? 0 : prefix.bitLength() - 1;
            missing.add(new ProjectionStep(prefix, power));
            prefix = power == 0 ? previous : prefix.clearBit(power);
            row = projected.get(prefix);
        }
        for (var step : missing.reversed()) {
            var power = powerAt(step.power(), powers, work);
            row = project(row, power, work);
            work.add(1);
            projected.put(step.index(), row);
        }
        return row;
    }

    private static Matrix powerAt(int bit, List<Matrix> powers, Work work) {
        while (powers.size() <= bit) {
            work.add(1);
            powers.add(square(powers.getLast(), work));
        }
        work.add(1);
        return powers.get(bit);
    }

    private static Row project(Row row, Matrix power, Work work) {
        if (row instanceof ScaledRow scaledRow && power instanceof ScaledMatrix scaledPower) {
            var product = multiplyRow(scaledRow.entries(), scaledPower.entries(), work);
            var denominator = multiply(scaledRow.denominator(), scaledPower.denominator(), work);
            if (!product.nearLimit() && denominator.bitLength() <= MAX_RATIONAL_BITS) {
                return new ScaledRow(product.entries(), denominator);
            }
            try {
                var normalized = normalize(denominator, new BigInteger[][] {product.entries()}, work);
                return new ScaledRow(product.entries(), normalized);
            } catch (ArithmeticLimit sharedScaleTooLarge) {
                // The matrix and input row are still available in their original representation.
            }
        }
        return new RationalRow(multiplyFractions(fractions(row, work), fractions(power, work), work));
    }

    private static Matrix square(Matrix power, Work work) {
        if (power instanceof ScaledMatrix scaled) {
            work.add(scaled.entries().length);
            var squared = new BigInteger[scaled.entries().length][];
            boolean nearLimit = false;
            for (int i = 0; i < squared.length; i++) {
                var product = multiplyRow(scaled.entries()[i], scaled.entries(), work);
                squared[i] = product.entries();
                nearLimit |= product.nearLimit();
            }
            var denominator = multiply(scaled.denominator(), scaled.denominator(), work);
            if (!nearLimit && denominator.bitLength() <= MAX_RATIONAL_BITS) {
                return new ScaledMatrix(squared, denominator);
            }
            try {
                var normalized = normalize(denominator, squared, work);
                return new ScaledMatrix(squared, normalized);
            } catch (ArithmeticLimit sharedScaleTooLarge) {
                // Retry using individually normalized entries under the remaining work.
            }
        }
        var previous = fractions(power, work);
        work.add(previous.size());
        var squared = new ArrayList<List<Rational>>();
        for (var row : previous) squared.add(multiplyFractions(row, previous, work));
        return new RationalMatrix(squared);
    }

    private static List<Rational> multiplyFractions(List<Rational> row, List<List<Rational>> matrix, Work work) {
        work.add(row.size());
        var result = new ArrayList<Rational>(row.size());
        for (int column = 0; column < row.size(); column++) {
            var total = Rational.ZERO;
            for (int i = 0; i < row.size(); i++) {
                total = RecurrenceObservationArithmetic.addProduct(total, row.get(i), matrix.get(i).get(column), work);
            }
            result.add(total);
        }
        return result;
    }

    private static List<Rational> fractions(Row row, Work work) {
        return switch (row) {
            case RationalRow exact -> exact.entries();
            case ScaledRow scaled -> fractions(scaled.entries(), scaled.denominator(), work);
        };
    }

    private static List<List<Rational>> fractions(Matrix matrix, Work work) {
        if (matrix instanceof RationalMatrix exact) return exact.rows();
        var scaled = (ScaledMatrix) matrix;
        work.add(scaled.entries().length);
        var rows = new ArrayList<List<Rational>>();
        for (var row : scaled.entries()) rows.add(fractions(row, scaled.denominator(), work));
        return rows;
    }

    private static List<Rational> fractions(BigInteger[] entries, BigInteger denominator, Work work) {
        work.add(entries.length);
        var row = new ArrayList<Rational>(entries.length);
        for (var entry : entries) row.add(new Rational(entry, denominator));
        return row;
    }

    /**
     * Normalize a fresh product before checking representation size. The temporary
     * entries are sums of at most Integer.MAX_VALUE products of two bounded integers.
     */
    private static BigInteger normalize(BigInteger denominator, BigInteger[][] entries, Work work) {
        var divisor = denominator;
        for (var row : entries) {
            for (var value : row) {
                if (divisor.equals(BigInteger.ONE)) break;
                work.add(1);
                divisor = divisor.gcd(value);
            }
        }
        boolean reduce = !divisor.equals(BigInteger.ONE);
        for (var row : entries) {
            work.add(row.length);
            for (int i = 0; i < row.length; i++) {
                row[i] = checked(reduce ? row[i].divide(divisor) : row[i]);
            }
        }
        return checked(reduce ? denominator.divide(divisor) : denominator);
    }

    private static RowProduct multiplyRow(BigInteger[] row, BigInteger[][] matrix, Work work) {
        work.add(row.length);
        var result = new BigInteger[row.length];
        boolean nearLimit = false;
        for (int column = 0; column < row.length; column++) {
            var sum = BigInteger.ZERO;
            for (int i = 0; i < row.length; i++) sum = addProduct(sum, row[i], matrix[i][column], work);
            result[column] = sum;
            // At the boundary check the absolute magnitude, including negative powers of two.
            nearLimit |= sum.bitLength() >= MAX_RATIONAL_BITS;
        }
        return new RowProduct(result, nearLimit);
    }

    private static Matrix scaleMatrix(Transition transition, List<Integer> coordinates, boolean hasConstant,
            Work work) {
        int width = coordinates.size() + (hasConstant ? 1 : 0);
        work.add(width);
        var rows = new ArrayList<List<Rational>>(width);
        for (int coordinate : coordinates) {
            work.add(width);
            var row = new ArrayList<Rational>(width);
            for (int column : coordinates) row.add(transition.linear().get(coordinate, column));
            if (hasConstant) row.add(transition.offset().get(coordinate));
            rows.add(row);
        }
        if (hasConstant) {
            work.add(width);
            var row = new ArrayList<>(Collections.nCopies(width - 1, Rational.ZERO));
            row.add(Rational.ONE);
            rows.add(row);
        }
        try {
            var denominator = BigInteger.ONE;
            for (var row : rows) denominator = commonDenominator(row, denominator, work);
            work.add(width);
            var entries = new BigInteger[width][];
            for (int i = 0; i < width; i++) entries[i] = scale(rows.get(i), denominator, work);
            return new ScaledMatrix(entries, denominator);
        } catch (ArithmeticLimit sharedScaleTooLarge) {
            return new RationalMatrix(rows);
        }
    }

    private static List<Rational> project(ExactVector vector, List<Integer> coordinates, boolean hasConstant,
            Rational constant, Work work) {
        work.add(1L + coordinates.size());
        var values = new ArrayList<Rational>();
        for (int coordinate : coordinates) values.add(vector.get(coordinate));
        if (hasConstant) values.add(constant);
        return values;
    }

    private static BigInteger commonDenominator(List<Rational> values, BigInteger denominator, Work work) {
        for (var value : values) {
            work.add(1);
            var divisor = value.denominator();
            if (divisor.equals(BigInteger.ONE) || divisor.equals(denominator)) continue;
            work.add(2);
            denominator = checked(multiply(denominator, divisor.divide(denominator.gcd(divisor)), work));
        }
        return denominator;
    }

    private static BigInteger[] scale(List<Rational> values, BigInteger denominator, Work work) {
        work.add(values.size());
        var entries = new BigInteger[values.size()];
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            var numerator = value.numerator();
            if (numerator.signum() != 0 && !denominator.equals(value.denominator())) {
                work.add(1);
                numerator = checked(multiply(numerator, denominator.divide(value.denominator()), work));
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

    private record RowProduct(BigInteger[] entries, boolean nearLimit) { }
    private record ProjectionStep(BigInteger index, int power) { }
    private sealed interface Row permits ScaledRow, RationalRow { }
    private record RationalRow(List<Rational> entries) implements Row { }
    private record ScaledRow(BigInteger[] entries, BigInteger denominator) implements Row { }
    private sealed interface Matrix permits ScaledMatrix, RationalMatrix { }
    private record RationalMatrix(List<List<Rational>> rows) implements Matrix { }
    private record ScaledMatrix(BigInteger[][] entries, BigInteger denominator) implements Matrix { }
}
