package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.array;
import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.number;
import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.object;
import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.management.ThreadMXBean;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Result;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.Status;
import de.regelsuche.math.algorithms.linalg.RecurrenceObservationKrylov.VerifiedPlan;
import de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class ExternalRecurrenceObservationStudyTest {
    private static final Path PROTOCOL = Path.of("config/benchmarks/external-recurrence-observations-v1.json");
    private static final String PROTOCOL_SHA256 = "768f9c3ae04c8a578db28d601ca943bc2a76459788c3e6ba942c669e26ccbe79";
    private static final Path PUBLISHED_WORK = Path.of("docs/research/external-recurrence-observations-v1/work.tsv");
    private static final String WORK_OUTPUT = "externalObservationWorkOutput";
    private static final String METRICS_OUTPUT = "externalObservationMetricsOutput";

    @Test
    void reproducesExactValuesAndCumulativeWork() throws IOException, NoSuchAlgorithmException {
        var panel = load();
        var receipt = new StringBuilder();
        row(receipt, "model", "observation", "stream", "arm", "starts", "indicesPerStart", "order", "budget",
            "setupOrAccess", "preparations", "deriveAndVerify", "bind", "answer", "firstRequest", "total", "valueSha256");
        for (var job : panel.jobs()) {
            var expected = oracle(job);
            var digest = digest(expected);
            for (var arm : Arm.values()) {
                var run = run(job, arm, panel.budget());
                assertEquals(expected, run.answers(), job.id() + " " + arm);
                var work = run.phases();
                assertTrue(work.total() <= panel.budget(), job.id() + " " + arm);
                row(receipt, job.model(), job.observation(), job.stream().id(), arm.label(), job.starts().size(),
                    job.stream().indices().size(), run.order(), panel.budget(), work.setupOrAccess(), run.preparations(),
                    work.deriveAndVerify(), work.bind(), work.answer(), run.firstRequest(), work.total(), digest);
            }
        }
        var output = System.getProperty(WORK_OUTPUT);
        if (output == null) {
            assertEquals(Files.readString(panel.checkout().resolve(PUBLISHED_WORK)), receipt.toString());
        } else {
            Files.writeString(Path.of(output), receipt);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = METRICS_OUTPUT, matches = ".+")
    void measuresStreamWallTimeAndThreadAllocation() throws IOException {
        var panel = load();
        var bean = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
        var receipt = new StringBuilder();
        row(receipt, "# java", System.getProperty("java.runtime.version"), System.getProperty("java.vm.name"));
        row(receipt, "# os", System.getProperty("os.name"), System.getProperty("os.arch"), System.getProperty("os.version"));
        row(receipt, "# runtime", Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory(),
            ManagementFactory.getGarbageCollectorMXBeans().stream().map(gc -> gc.getName()).toList());
        row(receipt, "# repetitions", panel.warmups(), panel.samples());
        row(receipt, "model", "observation", "stream", "arm", "medianNanos", "minNanos", "maxNanos",
            "medianAllocatedBytes", "minAllocatedBytes", "maxAllocatedBytes");
        var arms = Arm.values();
        for (var job : panel.jobs()) {
            var elapsed = new long[arms.length][panel.samples()];
            var allocated = new long[arms.length][panel.samples()];
            var expected = oracle(job);
            for (int repetition = 0; repetition < panel.warmups() + panel.samples(); repetition++) {
                for (int position = 0; position < arms.length; position++) {
                    var arm = arms[(repetition + position) % arms.length];
                    long beforeBytes = allocatedBytes(bean);
                    long start = System.nanoTime();
                    var run = run(job, arm, panel.budget());
                    long nanos = System.nanoTime() - start;
                    long afterBytes = allocatedBytes(bean);
                    if (repetition >= panel.warmups()) {
                        elapsed[arm.ordinal()][repetition - panel.warmups()] = nanos;
                        allocated[arm.ordinal()][repetition - panel.warmups()] = beforeBytes < 0 ? -1 : afterBytes - beforeBytes;
                    }
                    assertEquals(expected, run.answers(), job.id() + " " + arm);
                }
            }
            for (var arm : arms) {
                var nanos = sorted(elapsed[arm.ordinal()]);
                var bytes = sorted(allocated[arm.ordinal()]);
                row(receipt, job.model(), job.observation(), job.stream().id(), arm.label(), midpoint(nanos), nanos[0],
                    nanos[nanos.length - 1], metric(midpoint(bytes)), metric(bytes[0]), metric(bytes[bytes.length - 1]));
            }
        }
        Files.writeString(Path.of(System.getProperty(METRICS_OUTPUT)), receipt);
    }

    private static Run run(Job job, Arm arm, int budget) {
        var work = new Work(budget);
        var totals = new PhaseWork(0, 0, 0, 0);
        var answers = new ArrayList<Rational>();
        DirectLinearObservation nativePlan = null;
        VerifiedPlan verifiedPlan = null;
        int order = -1;
        int preparations = 0;
        int firstRequest = 0;
        for (var initial : job.starts()) {
            int beforeSetup = work.remaining();
            work.add(1);
            if (arm == Arm.DIRECT && nativePlan == null) {
                nativePlan = new DirectLinearObservation(job.transition(), job.coefficients(), work);
            }
            int beforeProof = work.remaining();
            if (arm == Arm.FRESH || (arm == Arm.REUSED && verifiedPlan == null)) {
                verifiedPlan = paid(RecurrenceObservationKrylov.prepare(job.transition(), job.polynomial(), currentBudget(work)), work);
                order = verifiedPlan.order();
                preparations++;
            }
            int beforeBind = work.remaining();
            int beforeQuery;
            List<Rational> values;
            if (arm == Arm.DIRECT) {
                var bound = nativePlan.bind(initial, work);
                beforeQuery = work.remaining();
                values = nativePlan.valuesAt(bound, job.stream().indices(), work);
            } else {
                var bound = paid(verifiedPlan.bind(initial, currentBudget(work)), work);
                beforeQuery = work.remaining();
                values = paid(bound.valuesAt(job.stream().indices(), currentBudget(work)), work);
            }
            work.add(1L + values.size());
            if (answers.isEmpty()) firstRequest = budget - work.remaining();
            answers.addAll(values);
            totals = totals.plus(new PhaseWork(beforeSetup - beforeProof, beforeProof - beforeBind,
                beforeBind - beforeQuery, beforeQuery - work.remaining()));
        }
        work.add(1L + answers.size());
        totals = totals.plus(new PhaseWork(0, 0, 0, 1 + answers.size()));
        if (totals.total() != work.ledger().consumedWorkUnits()) throw new IllegalStateException("unreconciled study ledger");
        return new Run(List.copyOf(answers), order, preparations, firstRequest, totals);
    }

    private static Budget currentBudget(Work work) {
        return new Budget(work.remaining());
    }

    private static <T> T paid(Result<T> result, Work work) {
        work.add(result.work().consumedWorkUnits());
        if (result.status() != Status.COMPLETE) throw new IllegalStateException("benchmark arm did not complete: " + result.status());
        return result.value().orElseThrow();
    }

    /** Independent evaluator path: step every state exactly, with no binary powers or scalar recurrence. */
    private static List<Rational> oracle(Job job) {
        int maxIndex = job.stream().indices().stream().mapToInt(BigInteger::intValueExact).max().orElseThrow();
        var matrix = job.transition().linear().rows().stream().map(ExternalRecurrenceObservationStudyTest::exact).toList();
        var coefficients = exact(job.coefficients());
        var answers = new ArrayList<Rational>();
        for (var initial : job.starts()) {
            var state = exact(initial);
            var trajectory = new ArrayList<ExactRational>();
            trajectory.add(dot(coefficients, state));
            for (int step = 1; step <= maxIndex; step++) {
                var previous = state;
                state = matrix.stream().map(row -> dot(row, previous)).toList();
                trajectory.add(dot(coefficients, state));
            }
            if (answers.isEmpty()) assertEquals(job.anchor(), trajectory.get(job.anchorAt()), job.id());
            for (var index : job.stream().indices()) answers.add(Rational.fromExact(trajectory.get(index.intValueExact())));
        }
        return List.copyOf(answers);
    }

    private static ExactRational dot(List<ExactRational> row, List<ExactRational> column) {
        var total = ExactRational.ZERO;
        for (int i = 0; i < row.size(); i++) total = total.add(row.get(i).multiply(column.get(i)));
        return total;
    }

    private static List<ExactRational> exact(List<Rational> values) {
        return values.stream().map(Rational::exactValue).toList();
    }

    private static long allocatedBytes(ThreadMXBean bean) {
        if (bean == null || !bean.isThreadAllocatedMemorySupported() || !bean.isThreadAllocatedMemoryEnabled()) return -1;
        return bean.getCurrentThreadAllocatedBytes();
    }

    private static long[] sorted(long[] samples) {
        Arrays.sort(samples);
        return samples;
    }

    private static long midpoint(long[] sorted) {
        return sorted[sorted.length / 2];
    }

    private static Object metric(long value) {
        return value < 0 ? "NA" : value;
    }

    private static String digest(List<Rational> values) throws NoSuchAlgorithmException {
        var canonical = String.join("\n", values.stream().map(Rational::toString).toList());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static Panel load() throws IOException {
        Path checkout = Path.of(System.getProperty("regelsuche.repositoryRoot", "")).toAbsolutePath();
        while (checkout != null && !Files.isRegularFile(checkout.resolve(PROTOCOL))) checkout = checkout.getParent();
        if (checkout == null) throw new IOException("frozen external observation protocol not found");
        var document = StateChoiceOraclePanel.read(checkout.resolve(PROTOCOL), PROTOCOL_SHA256);
        var streams = array(document.get("streams")).stream().map(value -> stream(object(value))).toList();
        var initialPanels = object(document.get("initialPanels"));
        var jobs = new ArrayList<Job>();
        for (var value : array(document.get("models"))) {
            var model = object(value);
            var initial = object(initialPanels.get(model.get("initialPanel")));
            var starts = new ArrayList<List<Rational>>();
            starts.add(vector(initial.get("published")));
            for (var challenge : array(initial.get("challenge"))) starts.add(vector(challenge));
            var rows = array(model.get("matrixRows")).stream().map(ExternalRecurrenceObservationStudyTest::vector).toList();
            var transition = new Transition(new ExactMatrix(rows), new ExactVector(java.util.Collections.nCopies(rows.size(), Rational.ZERO)));
            for (var observationValue : array(model.get("observations"))) {
                var observation = object(observationValue);
                var coefficients = vector(observation.get("coefficients"));
                for (var stream : streams) {
                    jobs.add(new Job((String) model.get("id"), (String) observation.get("id"), transition, coefficients,
                        polynomial(coefficients), List.copyOf(starts), stream, number(observation, "anchorAt"),
                        ExactRational.parse((String) observation.get("anchor"))));
                }
            }
        }
        var measurement = object(document.get("measurement"));
        return new Panel(checkout, List.copyOf(jobs), number(document, "workBudgetPerCompleteStreamAndArm"),
            number(measurement, "warmupRepetitions"), number(measurement, "measuredRepetitions"));
    }

    private static Stream stream(Map<?, ?> stream) {
        var indices = array(stream.get("indices")).stream().map(value -> BigInteger.valueOf(((Number) value).longValue())).toList();
        return new Stream((String) stream.get("id"), indices);
    }

    private static List<Rational> vector(Object value) {
        return Arrays.stream(((String) value).split(" ")).map(ExactRational::parse).map(Rational::fromExact).toList();
    }

    private static Polynomial polynomial(List<Rational> coefficients) {
        var observation = Polynomial.zero();
        for (int i = 0; i < coefficients.size(); i++) {
            observation = observation.add(Polynomial.term(Monomial.variable("x" + i), coefficients.get(i)));
        }
        return observation;
    }

    private enum Arm {
        DIRECT, FRESH, REUSED;

        private String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Panel(Path checkout, List<Job> jobs, int budget, int warmups, int samples) { }

    private record Stream(String id, List<BigInteger> indices) { }

    private record Job(String model, String observation, Transition transition, List<Rational> coefficients,
            Polynomial polynomial, List<List<Rational>> starts, Stream stream, int anchorAt, ExactRational anchor) {
        String id() {
            return model + "/" + observation + "/" + stream.id();
        }
    }

    private record Run(List<Rational> answers, int order, int preparations, int firstRequest, PhaseWork phases) { }

    private record PhaseWork(int setupOrAccess, int deriveAndVerify, int bind, int answer) {
        int total() {
            return setupOrAccess + deriveAndVerify + bind + answer;
        }

        PhaseWork plus(PhaseWork other) {
            return new PhaseWork(setupOrAccess + other.setupOrAccess, deriveAndVerify + other.deriveAndVerify,
                bind + other.bind, answer + other.answer);
        }
    }
}
