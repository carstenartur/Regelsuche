package de.regelsuche.benchmark.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning.Example;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/** Executes the separately frozen graph-family protocol; production planners receive no family labels. */
public final class ConstraintLifecycleComparison {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ARMS = List.of("FIXED_MIN_FILL", "FIXED_MIN_DEGREE", "INDEXED_MIN_FILL", "COSTED_LEARNED", "FRESH_SEARCH");
    private ConstraintLifecycleComparison() {}

    record Graph(String family, int a, int b, Problem problem, List<List<Integer>> edges) {
        String id() { return family + ":" + a + ":" + b; }
    }
    private record Outcome(Optional<BigInteger> count, long work, String reason, String selected, int failedTrials) {}
    private record Observation<T>(T value, long wallNanos, long threadCpuNanos, long allocatedBytes) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[2].matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("expected protocol JSON, output JSON, exact source revision");
        byte[] protocolBytes = Files.readAllBytes(Path.of(args[0]));
        var protocol = JSON.readTree(protocolBytes);
        if (!"regelsuche.constraint-lifecycle-protocol/v1".equals(protocol.path("schema").asText()))
            throw new IllegalArgumentException("unsupported lifecycle protocol");
        var trainGraphs = graphs(protocol.required("training"));
        var validation = graphs(protocol.required("validation"));
        var finals = graphs(protocol.required("final"));
        requireDisjointFamilies(trainGraphs, validation, finals);
        int states = protocol.required("maximumStates").asInt(), timeout = protocol.required("timeoutMillis").asInt();
        long trial = protocol.required("perTrainingTrialWork").asLong();
        var trainingLimits = new IterationLimits(protocol.required("trainingMaximumWork").asLong(), states, timeout);
        var queryLimits = new IterationLimits(protocol.required("queryMaximumWork").asLong(), states, timeout);
        var examples = new ArrayList<Example>();
        for (Graph graph : trainGraphs) examples.add(new Example(hash(graph.problem().toString().getBytes(StandardCharsets.UTF_8)), graph.problem()));
        var acquisition = observe(() -> FiniteConstraintPlanLearning.train(examples, trainingLimits, trial, () -> false));
        var training = acquisition.value();
        var modelRows = observationRows(training.observations());
        String modelHash = modelFingerprint(training.model());
        var rows = new ArrayList<Map<String, Object>>();
        execute("validation", validation, 1, training.model(), queryLimits, trial,
            protocol.required("maximumProbeWork").asLong(), rows);
        execute("final", finals, protocol.required("finalRounds").asInt(), training.model(), queryLimits, trial,
            protocol.required("maximumProbeWork").asLong(), rows);
        String afterFinal = modelFingerprint(training.model());
        if (!modelHash.equals(afterFinal)) throw new IllegalStateException("model changed after freeze");
        var totals = new LinkedHashMap<String, Object>();
        for (String arm : ARMS) {
            long validationWork = sum(rows, arm, "validation", "work"), finalWork = sum(rows, arm, "final", "work");
            totals.put(arm, Map.of("validationWork", validationWork, "finalWork", finalWork,
                "lifecycleWork", validationWork + finalWork + (arm.equals("COSTED_LEARNED") ? training.work() : 0),
                "successfulQueries", rows.stream().filter(r -> arm.equals(r.get("arm")) && Boolean.TRUE.equals(r.get("independentCountMatches"))).count()));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", "regelsuche.constraint-lifecycle-result/v1");
        result.put("sourceRevision", args[2]);
        result.put("protocolSha256", hash(protocolBytes));
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("training", modelRows);
        result.put("modelSha256BeforeValidation", modelHash);
        result.put("modelSha256AfterFinal", afterFinal);
        result.put("trainingWork", training.work());
        result.put("trainingReason", training.reason());
        result.put("trainingWallNanos", acquisition.wallNanos());
        result.put("trainingThreadCpuNanos", acquisition.threadCpuNanos());
        result.put("trainingAllocatedBytes", acquisition.allocatedBytes());
        result.put("totals", totals);
        result.put("rows", rows);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        System.out.println("constraint-lifecycle/v1 rows=" + rows.size() + " training=" + training.work() + " model=" + modelHash);
    }

    /** Read the model actually supplied to queries, including the paid acquisition; normalize map order. */
    static String modelFingerprint(Optional<FiniteConstraintPlanLearning.Model> model) throws Exception {
        Object state = model.<Object>map(value -> Map.of("trainingWork", value.trainingWork(),
            "observations", observationRows(value.observations()))).orElse("NO_MODEL");
        return hash(JSON.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(state));
    }

    private static List<Map<String, Object>> observationRows(List<FiniteConstraintPlanLearning.Observation> observations) {
        var rows = new ArrayList<Map<String, Object>>();
        for (var item : observations) {
            var trials = item.trials().stream().map(t -> Map.of("strategy", t.strategy().name(), "status", t.status().name(),
                "reason", t.reason(), "work", t.work())).toList();
            rows.add(Map.of("sourceSha256", item.source(), "features", item.features(), "trials", trials,
                "best", item.best().map(Enum::name).orElse("NONE")));
        }
        return rows;
    }

    private static void execute(String split, List<Graph> graphs, int rounds,
            Optional<FiniteConstraintPlanLearning.Model> model, IterationLimits limits, long trial, long probe,
            List<Map<String, Object>> rows) {
        if (rounds < 1 || rounds > 100) throw new IllegalArgumentException("invalid repeat count");
        var truth = graphs.stream().map(ConstraintLifecycleComparison::enumerate).toList();
        for (int round = 0; round < rounds; round++) for (int index = 0; index < graphs.size(); index++) {
            Graph graph = graphs.get(index);
            var order = new ArrayList<>(ARMS);
            Collections.rotate(order, (index + round) % order.size());
            for (String arm : order) {
                var measurement = observe(() -> run(arm, graph.problem(), model, (long) rounds * graphs.size(), limits, trial, probe));
                var outcome = measurement.value();
                if (outcome.count().isPresent() && !outcome.count().orElseThrow().equals(truth.get(index)))
                    throw new IllegalStateException("independent source enumeration disagrees");
                var row = new LinkedHashMap<String, Object>();
                row.put("split", split); row.put("case", graph.id()); row.put("round", round); row.put("arm", arm);
                row.put("variableCount", graph.problem().variableCount()); row.put("factorCount", graph.edges().size());
                row.put("count", outcome.count().map(BigInteger::toString).orElse("UNAVAILABLE"));
                row.put("expectedCount", truth.get(index).toString()); row.put("independentCountMatches", outcome.count().isPresent());
                row.put("work", outcome.work()); row.put("reason", outcome.reason()); row.put("selected", outcome.selected());
                row.put("failedFreshTrials", outcome.failedTrials());
                row.put("wallNanos", measurement.wallNanos()); row.put("threadCpuNanos", measurement.threadCpuNanos());
                row.put("allocatedBytes", measurement.allocatedBytes());
                rows.add(row);
            }
        }
    }

    private static Outcome run(String arm, Problem problem, Optional<FiniteConstraintPlanLearning.Model> model,
            long uses, IterationLimits limits, long trial, long probe) {
        if (arm.equals("COSTED_LEARNED")) {
            var result = FiniteConstraintPlanLearning.countWithPolicy(model, problem, Strategy.MIN_FILL, uses, probe, limits, () -> false);
            return new Outcome(result.value().flatMap(FiniteConstraintCounter.Result::count), result.work(), result.reason(),
                result.selectedProvider().orElse("NONE"), (int) result.attempts().stream().filter(a ->
                    a.disposition() == de.regelsuche.math.algorithms.planning.BoundedPlanSelection.Disposition.FAILED).count());
        }
        if (arm.equals("FRESH_SEARCH")) {
            var work = new CertificateWork(limits, () -> false);
            try {
                var trained = FiniteConstraintPlanLearning.train(List.of(new Example("current-source", problem)),
                    work.remainingLimits(), trial, () -> false);
                work.chargeCompleted(trained.work());
                Strategy chosen = trained.observations().isEmpty() ? Strategy.MIN_FILL
                    : trained.observations().getFirst().best().orElse(Strategy.MIN_FILL);
                var result = FiniteConstraintCounter.count(problem, chosen, work.remainingLimits(), () -> false);
                work.chargeCompleted(result.work());
                int failed = (int) trained.observations().stream().flatMap(o -> o.trials().stream())
                    .filter(t -> t.status() == FiniteConstraintCounter.Status.UNAVAILABLE).count();
                return new Outcome(result.count(), work.used(), result.reason(), chosen.name(), failed);
            } catch (CertificateWork.Stop stop) {
                return new Outcome(Optional.empty(), work.used(), stop.getMessage(), "NONE", 0);
            }
        }
        Strategy strategy = arm.equals("FIXED_MIN_DEGREE") ? Strategy.MIN_DEGREE : Strategy.MIN_FILL;
        var result = arm.equals("INDEXED_MIN_FILL") ? FiniteConstraintCounter.countIndexed(problem, strategy, limits, () -> false)
            : FiniteConstraintCounter.count(problem, strategy, limits, () -> false);
        return new Outcome(result.count(), result.work(), result.reason(), strategy.name(), 0);
    }

    static Graph graph(String family, int a, int b) {
        int n = graphOrder(family, a, b);
        var edges = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            boolean linked = switch (family) {
                case "path" -> j == i + 1;
                case "cycle" -> j == i + 1 || i == 0 && j == n - 1;
                case "binary_tree" -> (j - 1) / 2 == i;
                case "complete" -> true;
                case "star" -> i == 0;
                case "wheel" -> i == 0 || j == i + 1 || i == 1 && j == n - 1;
                case "grid" -> i / b == j / b && j == i + 1 || j == i + b;
                case "complete_bipartite" -> i < a && j >= a;
                case "disconnected_triangles" -> i / 3 == j / 3;
                default -> throw new IllegalArgumentException("unknown frozen generator");
            };
            if (linked) edges.add(List.of(i, j));
        }
        var factors = edges.stream().map(edge -> new Factor(edge,
            List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO))).toList();
        return new Graph(family, a, b, new Problem(n, factors, Map.of()), List.copyOf(edges));
    }

    private static int graphOrder(String family, int a, int b) {
        if (a < 1 || a > 16 || b < 0 || b > 16 || !List.of("grid", "complete_bipartite", "disconnected_triangles",
                "path", "cycle", "binary_tree", "complete", "star", "wheel").contains(family))
            throw new IllegalArgumentException("graph fixture limit");
        if ((family.equals("grid") || family.equals("complete_bipartite")) && b == 0)
            throw new IllegalArgumentException("nonempty graph dimensions required");
        int vertices = switch (family) {
            case "grid" -> a * b;
            case "complete_bipartite" -> a + b;
            case "disconnected_triangles" -> 3 * a;
            default -> a;
        };
        if (vertices > 16) throw new IllegalArgumentException("graph fixture limit");
        return vertices;
    }

    static BigInteger enumerate(Graph graph) {
        long valid = 0;
        for (int bits = 0; bits < (1 << graph.problem().variableCount()); bits++) {
            boolean accepted = true;
            for (var edge : graph.edges()) if ((bits & (1 << edge.getFirst())) != 0 && (bits & (1 << edge.getLast())) != 0) {
                accepted = false; break;
            }
            if (accepted) valid++;
        }
        return BigInteger.valueOf(valid);
    }

    private static List<Graph> graphs(JsonNode cases) {
        var result = new ArrayList<Graph>();
        for (var item : cases) result.add(graph(item.required("family").asText(), item.required("a").asInt(), item.required("b").asInt()));
        return List.copyOf(result);
    }
    @SafeVarargs private static void requireDisjointFamilies(List<Graph>... splits) {
        var prior = new TreeSet<String>();
        for (var split : splits) {
            var families = new TreeSet<String>(); split.forEach(g -> families.add(g.family()));
            if (!Collections.disjoint(prior, families)) throw new IllegalArgumentException("family leaked across frozen splits");
            prior.addAll(families);
        }
    }
    private static <T> Observation<T> observe(Supplier<T> action) {
        var cpu = ManagementFactory.getThreadMXBean();
        var allocation = cpu instanceof com.sun.management.ThreadMXBean extended && extended.isThreadAllocatedMemorySupported()
            && extended.isThreadAllocatedMemoryEnabled() ? extended : null;
        boolean hasCpu = cpu.isCurrentThreadCpuTimeSupported() && cpu.isThreadCpuTimeEnabled();
        long id = Thread.currentThread().threadId(), bytes = allocation == null ? -1 : allocation.getThreadAllocatedBytes(id);
        long started = System.nanoTime(), cpuStarted = hasCpu ? cpu.getCurrentThreadCpuTime() : -1;
        T value = action.get();
        return new Observation<>(value, System.nanoTime() - started, hasCpu ? cpu.getCurrentThreadCpuTime() - cpuStarted : -1,
            allocation == null ? -1 : allocation.getThreadAllocatedBytes(id) - bytes);
    }
    private static long sum(List<Map<String, Object>> rows, String arm, String split, String field) {
        return rows.stream().filter(r -> arm.equals(r.get("arm")) && split.equals(r.get("split"))).mapToLong(r -> (long) r.get(field)).sum();
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
