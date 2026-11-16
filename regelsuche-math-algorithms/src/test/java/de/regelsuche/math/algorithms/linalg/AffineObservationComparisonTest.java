package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.add;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.checked;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;
import static de.regelsuche.math.algorithms.linalg.SparseAffineForm.evaluateAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.json.JsonReader;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Observation;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Result;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Status;
import de.regelsuche.math.algorithms.linalg.AffineObservationReduction.Transition;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.SparseAffineForm.Update;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class AffineObservationComparisonTest {
    @Test void comparesCompleteOnlineTrajectoriesAtEqualBudget() throws IOException, NoSuchAlgorithmException {
        var checkout = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."));
        var spec = new JsonReader(Files.readString(checkout.resolve("config/benchmarks/switched-observation-v1.json")))
            .readObject();
        assertEquals("regelsuche.switched-observation/v1", field(spec, "schema", String.class));
        int budget = field(spec, "maximumWorkPerRun", Integer.class);
        var evaluation = field(spec, "evaluation", Map.class);
        int limit = field(evaluation, "qualificationLength", Integer.class);
        int threshold = field(evaluation, "minimumSavingPercent", Integer.class);
        var receipt = new StringBuilder();
        row(receipt, "case", "seed", "events", "sourceDimension", "derivedDimension", "approach", "prepareAndProof",
            "bind", "evaluate", "total", "directDimension", "directPrepare", "directBind", "directEvaluate",
            "directTotal", "factoredPrepare", "factoredBind", "factoredEvaluate", "factoredTotal", "bestDirectTotal",
            "qualification", "valueSha256");
        for (var source : elements(spec, "cases")) {
            var fixture = Fixture.read((Map<?, ?>) source);
            for (var seed : elements(evaluation, "seeds")) {
                for (var length : elements(evaluation, "lengths")) {
                    var events = events((Integer) seed, (Integer) length);
                    for (int label : events) assertTrue(label < fixture.transitions().size());
                    measure(receipt, fixture, (Integer) seed, events, budget, limit, threshold);
                }
            }
        }
        var output = System.getProperty("affineObservationOutput");
        if (output == null) {
            assertEquals(Files.readString(checkout.resolve("docs/research/switched-observation/work.tsv")), receipt.toString());
        } else {
            Files.writeString(Path.of(output), receipt.toString());
        }
    }

    private static void measure(StringBuilder receipt, Fixture fixture, int seed, List<Integer> events, int budget,
            int limit, int threshold) throws NoSuchAlgorithmException {
        var direct = directRun(fixture, events, budget);
        var factored = fixture.spring() == null ? null : factoredRun(fixture, events, budget);
        if (factored != null) assertEquals(direct.values(), factored.values(), fixture.id());
        int best = factored == null ? direct.total() : Math.min(direct.total(), factored.total());

        var prepared = AffineObservationReduction.prepare(fixture.transitions(), fixture.observations(), new Budget(budget));
        var plan = complete(prepared);
        int prepare = prepared.work().consumedWorkUnits();
        int dimension = plan.reducedDimension();
        String approach = "reduced";
        Run measured;
        if (dimension == plan.sourceDimension()) {
            approach = "direct-after-full-rank";
            var fallback = directRun(fixture, events, prepared.work().remainingWorkUnits());
            measured = new Run(fallback.dimension(), prepare + fallback.prepare(), fallback.bind(), fallback.evaluate(),
                fallback.values());
        } else {
            var bound = plan.bind(fixture.initial(), remaining(prepared));
            var answers = complete(bound).trace(events, remaining(bound));
            measured = new Run(dimension, prepare, bound.work().consumedWorkUnits(), answers.work().consumedWorkUnits(),
                complete(answers).observations());
        }
        assertEquals(direct.values(), measured.values(), fixture.id());
        assertTrue(measured.total() <= budget);
        // Every target has one nonconstant observation in the frozen input.
        String qualification = "-";
        if (fixture.target() && events.size() == limit) {
            assertEquals(1, fixture.observations().size());
            boolean qualifies = dimension > 1 && dimension < plan.sourceDimension()
                && 100L * (best - measured.total()) >= (long) threshold * best;
            qualification = qualifies ? "pass" : "miss";
        }
        row(receipt, fixture.id(), seed, events.size(), plan.sourceDimension(), dimension, approach, measured.prepare(),
            measured.bind(), measured.evaluate(), measured.total(), direct.dimension(), direct.prepare(), direct.bind(),
            direct.evaluate(), direct.total(), factored == null ? "-" : factored.prepare(),
            factored == null ? "-" : factored.bind(), factored == null ? "-" : factored.evaluate(),
            factored == null ? "-" : factored.total(), best, qualification, digest(direct.values()));
    }

    private static Run directRun(Fixture fixture, List<Integer> events, int budget) {
        var work = new Work(budget);
        var plan = Direct.prepare(fixture, work);
        int prepare = used(work);
        var state = plan.bind(fixture.initial(), work);
        int bind = used(work) - prepare;
        var values = new ArrayList<List<Rational>>();
        List<Rational> lastOutput = null;
        for (int label : events) {
            work.add(2);
            var update = plan.updates().get(label);
            update.apply(state, work);
            if (lastOutput == null || update.affectsOutputs()) lastOutput = evaluateAll(plan.outputs(), state, work);
            values.add(lastOutput);
        }
        work.add(1L + values.size());
        return new Run(plan.coordinates().size(), prepare, bind, used(work) - prepare - bind, List.copyOf(values));
    }

    /** A source-specific direct control maintains all four physical coordinates and shares the impulse. */
    private static Run factoredRun(Fixture fixture, List<Integer> events, int budget) {
        var work = new Work(budget);
        var spring = fixture.spring();
        work.add(3);
        var stiffness = checked(spring.stiffness());
        var external = checked(spring.external());
        var half = rational("1/2");
        int prepare = used(work);
        work.add(1L + fixture.initial().dimension());
        var state = new ArrayList<>(fixture.initial().values());
        state.forEach(RecurrenceObservationArithmetic::checked);
        int bind = used(work) - prepare;

        var values = new ArrayList<List<Rational>>();
        List<Rational> lastOutput = null;
        for (int label : events) {
            work.add(2); // Consume the event and select its prepared update.
            work.add(6); // Read the four inputs and write the two changed coordinates.
            if (label == 0) {
                var first = add(state.get(0), state.get(2), work);
                var second = add(state.get(1), state.get(3), work);
                state.set(0, first);
                state.set(1, second);
                lastOutput = center(first, second, half, work);
            } else {
                var impulse = multiply(stiffness, subtract(state.get(1), state.get(0), work), work);
                state.set(2, add(state.get(2), add(external, impulse, work), work));
                state.set(3, add(state.get(3), subtract(external, impulse, work), work));
            }
            if (lastOutput == null) {
                work.add(2);
                lastOutput = center(state.get(0), state.get(1), half, work);
            }
            values.add(lastOutput);
        }
        work.add(1L + values.size());
        return new Run(state.size(), prepare, bind, used(work) - prepare - bind, List.copyOf(values));
    }

    private static List<Rational> center(Rational first, Rational second, Rational half, Work work) {
        work.add(3); // One output form and a one-entry result.
        return List.of(multiply(half, add(first, second, work), work));
    }

    private static Rational subtract(Rational left, Rational right, Work work) {
        work.add(1);
        return checked(left.subtract(right));
    }

    private static List<Integer> events(int seed, int length) {
        var events = new ArrayList<Integer>(length);
        int state = seed;
        for (int i = 0; i < length; i++) {
            state = 1664525 * state + 1013904223;
            events.add(state >>> 31);
        }
        return events;
    }

    private static int used(Work work) {
        return work.ledger().consumedWorkUnits();
    }

    private static <T> T complete(Result<T> result) {
        assertEquals(Status.COMPLETE, result.status());
        return result.value().orElseThrow();
    }

    private static Budget remaining(Result<?> result) {
        return new Budget(result.work().remainingWorkUnits());
    }

    private static void row(StringBuilder target, Object... values) {
        target.append(String.join("\t", Arrays.stream(values).map(Object::toString).toList())).append('\n');
    }

    private static String digest(List<List<Rational>> values) throws NoSuchAlgorithmException {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var row : values) {
            for (var value : row) {
                digest.update(value.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\t');
            }
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<Rational> vector(String text) {
        return Arrays.stream(text.split(" ")).map(AffineObservationComparisonTest::rational).toList();
    }

    private static Rational rational(String text) {
        return Rational.fromExact(ExactRational.parse(text));
    }

    private static <T> T field(Map<?, ?> object, String name, Class<T> type) {
        return type.cast(Objects.requireNonNull(object.get(name), name));
    }

    private static List<?> elements(Map<?, ?> object, String name) {
        return field(object, name, List.class);
    }

    private record SpringReference(Rational stiffness, Rational external) { }

    private record Fixture(String id, boolean target, List<Transition> transitions, List<Observation> observations,
            ExactVector initial, SpringReference spring) {
        private static Fixture read(Map<?, ?> node) {
            var transitions = elements(node, "transitions").stream().map(source -> {
                var transition = (Map<?, ?>) source;
                var divisor = (Integer) transition.get("matrixDenominator");
                var denominator = Rational.of(divisor == null ? 1 : divisor);
                var rows = elements(transition, "rows").stream()
                    .map(row -> vector((String) row).stream().map(value -> value.divide(denominator)).toList()).toList();
                return new Transition(new ExactMatrix(rows), new ExactVector(vector(field(transition, "offset", String.class))));
            }).toList();
            var observations = elements(node, "observations").stream().map(source -> {
                var observation = (Map<?, ?>) source;
                return new Observation(new ExactVector(vector(field(observation, "weights", String.class))),
                    rational(field(observation, "offset", String.class)));
            }).toList();
            var spring = (Map<?, ?>) node.get("springReference");
            return new Fixture(field(node, "id", String.class), field(node, "kind", String.class).equals("target"),
                transitions, observations, new ExactVector(vector(field(node, "initial", String.class))),
                spring == null ? null : new SpringReference(rational(field(spring, "stiffness", String.class)),
                    rational(field(spring, "externalImpulse", String.class))));
        }
    }

    private record Run(int dimension, int prepare, int bind, int evaluate, List<List<Rational>> values) {
        int total() {
            return prepare + bind + evaluate;
        }
    }

    private record Direct(List<Integer> coordinates, List<Update> updates, List<SparseAffineForm> outputs) {
        private static Direct prepare(Fixture fixture, Work work) {
            var coordinates = dependencyClosure(fixture, work);
            var outputs = new ArrayList<SparseAffineForm>();
            for (var observation : fixture.observations()) {
                outputs.add(SparseAffineForm.compile(project(observation.weights().values(), coordinates, work),
                    checked(observation.offset()), work));
            }
            var updates = new ArrayList<Update>();
            for (var transition : fixture.transitions()) {
                var rows = new ArrayList<SparseAffineForm>();
                for (int coordinate : coordinates) {
                    rows.add(SparseAffineForm.compile(project(transition.linear().rows().get(coordinate), coordinates, work),
                        checked(transition.offset().get(coordinate)), work));
                }
                updates.add(Update.compile(rows, outputs, work));
            }
            work.add(1L + updates.size() + outputs.size());
            return new Direct(coordinates, List.copyOf(updates), List.copyOf(outputs));
        }

        private static List<Integer> dependencyClosure(Fixture fixture, Work work) {
            int dimension = fixture.initial().dimension();
            work.add(dimension);
            var required = new boolean[dimension];
            var queue = new ArrayList<Integer>();
            for (var observation : fixture.observations()) collect(observation.weights().values(), required, queue, work);
            for (int i = 0; i < queue.size(); i++) {
                for (var transition : fixture.transitions()) {
                    collect(transition.linear().rows().get(queue.get(i)), required, queue, work);
                }
            }
            return List.copyOf(queue);
        }

        private static void collect(List<Rational> weights, boolean[] required, List<Integer> queue, Work work) {
            work.add(1L + weights.size());
            for (int i = 0; i < weights.size(); i++) {
                if (!required[i] && !weights.get(i).isZero()) {
                    required[i] = true;
                    queue.add(i);
                }
            }
        }

        private static List<Rational> project(List<Rational> full, List<Integer> coordinates, Work work) {
            work.add(1L + coordinates.size());
            return coordinates.stream().map(full::get).map(RecurrenceObservationArithmetic::checked).toList();
        }

        private List<Rational> bind(ExactVector initial, Work work) {
            return new ArrayList<>(project(initial.values(), coordinates, work));
        }
    }
}
