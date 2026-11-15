package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.add;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.addProduct;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Result;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Status;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecurrenceObservationComparisonTest {
    private static final int BUDGET = 1_000_000;
    private static final List<BigInteger> INDICES = IntStream.range(0, 24)
        .mapToObj(i -> BigInteger.valueOf(128 + (i * 73 + 31) % 384)).toList();
    private static final String AXIS = "(x0+2*x1+3*x2)";
    private static final String[] ROTATION = {"-11/15 2/3 2/15", "-2/15 -1/3 14/15", "2/3 2/3 1/3"};

    @Test void comparesTotalWorkAndValuesAgainstPreparedAffinePowering() throws IOException, NoSuchAlgorithmException {
        var panel = List.of(
            new Fixture("rotoreflection-axis", transition("0 0 0",
                "11/15 -2/3 -2/15", "2/15 1/3 -14/15", "-2/3 -2/3 -1/3"), AXIS, 1),
            new Fixture("axial-drift-quadratic", transition("1 2 3", ROTATION), AXIS + "^2+" + AXIS + "/2", 3),
            new Fixture("one-coordinate-translation", transition("1 0 0", "1 0 0", "0 1 0", "2 1 0"),
                "x0^2+x0+1", 3),
            new Fixture("ten-modes", transition("0 0 0", "2 0 0", "0 3 0", "0 0 5"),
                "1+x0+x1+x2+x0^2+x0*x1+x0*x2+x1^2+x1*x2+x2^2", 10));
        var receipt = new StringBuilder();
        row(receipt, "case", "queries", "order", "krylovPrepareAndProof", "krylovBind", "krylovQuery", "krylovTotal",
            "directPrepare", "directBind", "directQuery", "directTotal", "valueSha256");
        for (var fixture : panel) {
            measure(receipt, fixture, INDICES.subList(0, 1));
            measure(receipt, fixture, INDICES);
        }
        var output = System.getProperty("krylovObservationOutput");
        if (output == null) {
            var checkout = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."));
            assertEquals(Files.readString(checkout.resolve("docs/research/recurrence-observation-krylov/work.tsv")), receipt.toString());
        } else {
            Files.writeString(Path.of(output), receipt.toString());
        }
    }

    private static void measure(StringBuilder receipt, Fixture fixture, List<BigInteger> indices) throws NoSuchAlgorithmException {
        var initial = vector("2 -3 4");
        var observation = new ExactPolynomialAnalysis().normalForm(fixture.observation());
        var prepared = RecurrenceObservationKrylov.prepare(fixture.transition(), observation, new Budget(BUDGET));
        var plan = complete(prepared);
        assertEquals(fixture.expectedOrder(), plan.order(), fixture.id());
        var bound = plan.bind(initial, remaining(prepared));
        var answers = complete(bound).valuesAt(indices, remaining(bound));
        var actual = complete(answers);

        var directWork = new Work(BUDGET);
        var direct = Direct.prepare(fixture.transition(), observation, directWork);
        int directPrepare = directWork.ledger().consumedWorkUnits();
        var base = direct.bind(initial, directWork);
        int directBind = directWork.ledger().consumedWorkUnits() - directPrepare;
        var expected = direct.valuesAt(base, indices, directWork);
        int directTotal = directWork.ledger().consumedWorkUnits();
        assertEquals(expected, actual, fixture.id());

        String values = String.join("\n", actual.stream().map(Rational::toString).toList());
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(values.getBytes(StandardCharsets.UTF_8)));
        int setup = prepared.work().consumedWorkUnits();
        int bind = bound.work().consumedWorkUnits();
        int query = answers.work().consumedWorkUnits();
        row(receipt, fixture.id(), indices.size(), plan.order(), setup, bind, query, setup + bind + query,
            directPrepare, directBind, directTotal - directPrepare - directBind, directTotal, digest);
    }

    private static void row(StringBuilder target, Object... values) {
        target.append(String.join("\t", Arrays.stream(values).map(Object::toString).toList())).append('\n');
    }

    private static <T> T complete(Result<T> result) {
        assertEquals(Status.COMPLETE, result.status());
        return result.value().orElseThrow();
    }

    private static Budget remaining(Result<?> result) {
        return new Budget(result.work().remainingWorkUnits());
    }

    private static Transition transition(String offset, String... rows) {
        return new Transition(new ExactMatrix(Arrays.stream(rows).map(RecurrenceObservationComparisonTest::vector).toList()),
            new ExactVector(vector(offset)));
    }

    private static List<Rational> vector(String values) {
        return Arrays.stream(values.split(" ")).map(ExactRational::parse).map(Rational::fromExact).toList();
    }

    private record Fixture(String id, Transition transition, String observation, int expectedOrder) { }

    /** Exact powers of the affine coordinate map, restricted to the observation's dependency closure. */
    private record Direct(int[] coordinates, List<Term> terms, Affine initialPower) {
        private static Direct prepare(Transition transition, Polynomial observation, Work work) {
            int dimension = transition.linear().columns();
            work.add(dimension);
            var required = new boolean[dimension];
            for (String name : observation.variables()) {
                work.add(1);
                required[name.charAt(1) - '0'] = true;
            }
            boolean changed;
            do {
                changed = false;
                for (int row = 0; row < dimension; row++) {
                    work.add(1);
                    if (!required[row]) continue;
                    for (int column = 0; column < dimension; column++) {
                        work.add(1);
                        if (!required[column] && !transition.linear().get(row, column).isZero()) {
                            required[column] = true;
                            changed = true;
                        }
                    }
                }
            } while (changed);
            work.add(dimension);
            int[] coordinates = IntStream.range(0, dimension).filter(i -> required[i]).toArray();
            var terms = new ArrayList<Term>();
            for (var entry : observation.terms().entrySet()) {
                work.add(1);
                var factors = new ArrayList<Integer>();
                for (int i = 0; i < coordinates.length; i++) {
                    work.add(1);
                    int power = entry.getKey().exponentOf("x" + coordinates[i]);
                    for (int j = 0; j < power; j++) {
                        work.add(1);
                        factors.add(i);
                    }
                }
                terms.add(new Term(entry.getValue(), factors));
            }
            int size = coordinates.length;
            work.add(1L + size + (long) size * size);
            var linear = new Rational[size][size];
            var offset = new Rational[size];
            for (int row = 0; row < size; row++) {
                offset[row] = transition.offset().get(coordinates[row]);
                for (int column = 0; column < size; column++) {
                    linear[row][column] = transition.linear().get(coordinates[row], coordinates[column]);
                }
            }
            return new Direct(coordinates, terms, Affine.checked(linear, offset, work));
        }

        private Rational[] bind(List<Rational> initial, Work work) {
            work.add(1L + coordinates.length);
            return Arrays.stream(coordinates).mapToObj(initial::get).toArray(Rational[]::new);
        }

        private List<Rational> valuesAt(Rational[] base, List<BigInteger> indices, Work work) {
            work.add(1);
            var result = new ArrayList<Rational>();
            var powers = new ArrayList<Affine>();
            powers.add(initialPower);
            for (BigInteger index : indices) {
                work.add(1);
                var state = base;
                for (int bit = 0; bit < index.bitLength(); bit++) {
                    work.add(1);
                    if (bit == powers.size()) powers.add(powers.getLast().square(work));
                    var power = powers.get(bit);
                    if (index.testBit(bit) && !power.identity()) state = power.apply(state, work);
                }
                var value = Rational.ZERO;
                for (var term : terms) {
                    work.add(1);
                    var termValue = term.coefficient();
                    for (int factor : term.factors()) termValue = multiply(termValue, state[factor], work);
                    value = add(value, termValue, work);
                }
                result.add(value);
            }
            work.add(result.size());
            return List.copyOf(result);
        }
    }

    private record Term(Rational coefficient, List<Integer> factors) { }

    private record Affine(Rational[][] linear, Rational[] offset, boolean identity) {
        private static Affine checked(Rational[][] linear, Rational[] offset, Work work) {
            for (int row = 0; row < offset.length; row++) {
                work.add(1);
                if (!offset[row].isZero()) return new Affine(linear, offset, false);
                for (int column = 0; column < offset.length; column++) {
                    work.add(1);
                    if (row == column ? !linear[row][column].isOne() : !linear[row][column].isZero()) {
                        return new Affine(linear, offset, false);
                    }
                }
            }
            return new Affine(linear, offset, true);
        }

        private Affine square(Work work) {
            work.add(1);
            if (identity) return this;
            int dimension = offset.length;
            var squared = new Rational[dimension][dimension];
            var shifted = new Rational[dimension];
            for (int row = 0; row < dimension; row++) {
                work.add(1L + dimension);
                Arrays.fill(squared[row], Rational.ZERO);
                shifted[row] = offset[row];
                for (int k = 0; k < dimension; k++) {
                    work.add(1);
                    var coefficient = linear[row][k];
                    if (coefficient.isZero()) continue;
                    shifted[row] = addProduct(shifted[row], coefficient, offset[k], work);
                    for (int column = 0; column < dimension; column++) {
                        squared[row][column] = addProduct(squared[row][column], coefficient, linear[k][column], work);
                    }
                }
            }
            return checked(squared, shifted, work);
        }

        private Rational[] apply(Rational[] state, Work work) {
            var result = new Rational[state.length];
            for (int row = 0; row < state.length; row++) {
                work.add(1);
                result[row] = offset[row];
                for (int column = 0; column < state.length; column++) {
                    result[row] = addProduct(result[row], linear[row][column], state[column], work);
                }
            }
            return result;
        }
    }
}
