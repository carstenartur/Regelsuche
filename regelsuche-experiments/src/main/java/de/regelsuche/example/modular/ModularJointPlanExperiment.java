package de.regelsuche.example.modular;

import de.regelsuche.ast.*;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.modular.ModularComputationDomain;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.program.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Paid modular architecture/execution diagnostic, not a learned-rule comparison.
 * Every row executes its full sequence and uses the same independently recomputed audit.
 * No warmup or trial is discarded. All one-time setup is charged to each scenario.
 */
public final class ModularJointPlanExperiment {
    public record Config(List<Integer> sequenceLengths, int repeats, int modulusBits, int exponentBits) {
        public Config {
            sequenceLengths = List.copyOf(sequenceLengths);
            if (sequenceLengths.isEmpty() || sequenceLengths.stream().anyMatch(n -> n < 1 || n > 100_000)
                    || repeats < 1 || repeats > 100 || modulusBits < 32 || modulusBits > 16_384
                    || exponentBits < 1 || exponentBits > 16_384) throw new IllegalArgumentException("invalid experiment bounds");
        }
    }
    public record Measurement(long wallNanos, long processCpuNanos, long threadAllocatedBytes,
            long heapBeforeBytes, long heapAfterBytes) {
        Measurement plus(Measurement other) {
            return new Measurement(wallNanos + other.wallNanos, availableSum(processCpuNanos, other.processCpuNanos),
                availableSum(threadAllocatedBytes, other.threadAllocatedBytes), heapBeforeBytes, other.heapAfterBytes);
        }
        void json(JsonWriter json) {
            json.property("wallNanos", wallNanos).property("processCpuNanos", processCpuNanos)
                .property("currentThreadAllocatedBytes", threadAllocatedBytes).property("heapBeforeBytes", heapBeforeBytes)
                .property("heapAfterBytes", heapAfterBytes);
        }
    }
    public record Row(int length, int repetition, String route, int executedInputs, int auditedInputs,
            String checksum, Measurement commonSetup, Measurement routeSetup, Measurement execution, Measurement audit) {
        public long paidWallNanos() { return paid().wallNanos(); }
        public Measurement paid() { return commonSetup.plus(routeSetup).plus(execution).plus(audit); }
        void json(JsonWriter json) {
            json.property("length", length).property("repetition", repetition).property("route", route)
                .property("executedInputs", executedInputs).property("auditedInputs", auditedInputs).property("checksum", checksum)
                .object("commonSetup", commonSetup::json).object("routeSetup", routeSetup::json)
                .object("execution", execution::json).object("audit", audit::json).object("fullyPaid", paid()::json);
        }
    }
    public record Report(Config config, List<Row> rows, long searchWork, long preparationWork,
            long inputCost, long outputCost, String outcome, long exploredStates, List<String> witnessRules,
            String source, String selected) {
        public Report { rows = List.copyOf(rows); witnessRules = List.copyOf(witnessRules); }
        public String toJson() {
            var json = new JsonWriter().beginObject().property("schema", "regelsuche.joint-modular-execution/v1")
                .property("purpose", "architecture-and-execution-only").property("learnedGainClaim", false)
                .property("timestamp", Instant.now().toString()).property("javaVersion", System.getProperty("java.version"))
                .property("availableProcessors", Runtime.getRuntime().availableProcessors())
                .property("warmupIterations", 0).property("discardedTrials", 0)
                .property("modulusBits", config.modulusBits()).property("exponentBits", config.exponentBits())
                .property("repetitions", config.repeats()).property("searchWork", searchWork)
                .property("preparationWork", preparationWork).property("predictedInputCost", inputCost)
                .property("predictedOutputCost", outputCost).property("searchOutcome", outcome)
                .property("exploredStates", exploredStates).stringArray("witnessRules", witnessRules)
                .property("sourceAst", source).property("selectedAst", selected)
                .property("measurementLimitations", "Process CPU includes JIT/GC; allocation covers the current Java thread; heap values are absolute samples, not per-route peaks. External process accounting includes JVM startup and report serialization. Each scenario pays complete setup; setup is actually performed once per report. No warmup, confidence interval, or global optimum claim.")
                .array("rows", writer -> rows.forEach(row -> writer.objectValue(row::json))).endObject();
            return json.toString();
        }
    }
    private record Measured<T>(T value, Measurement measurement) {}
    private record Bootstrap(ModularJointPlans domain, JointComputationPlan source) {}
    private record Input(BigInteger a, BigInteger x, BigInteger n, Map<String, Object> values) {}
    private final com.sun.management.OperatingSystemMXBean operatingSystem =
        ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean : null;
    private final com.sun.management.ThreadMXBean threads =
        ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean ? bean : null;

    public Report run(Config config) {
        var bootstrap = measure(ModularJointPlanExperiment::bootstrap);
        var directSetup = measure(() -> (Function<Input, Map<String, Object>>) input -> direct(bootstrap.value().domain(), input));
        var optimizedSetup = measure(() -> bootstrap.value().domain().optimizer(64).optimize(bootstrap.value().source(),
            new MoveSearch.Budget(5, 5, 0, 512, 1_000_000)));
        var optimized = optimizedSetup.value();
        if (!optimized.withinBudget()) throw new IllegalStateException("plan search exceeded declared budget");
        var rows = new ArrayList<Row>();
        int sequence = 0;
        for (int length : config.sequenceLengths()) {
            for (int repetition = 0; repetition < config.repeats(); repetition++) {
                int seed = repetition;
                var inputs = measure(() -> inputs(length, seed, config));
                Measurement common = bootstrap.measurement().plus(inputs.measurement());
                var order = (sequence + repetition) % 2 == 0 ? List.of("DIRECT", "PREPARED") : List.of("PREPARED", "DIRECT");
                for (String route : order) {
                    Function<Input, Map<String, Object>> evaluate = route.equals("DIRECT") ? directSetup.value()
                        : input -> optimized.prepared().execute(input.values());
                    var execution = measure(() -> inputs.value().stream().map(evaluate).toList());
                    var audit = measure(() -> audit(inputs.value(), execution.value()));
                    rows.add(new Row(length, repetition, route, execution.value().size(), inputs.value().size(), audit.value(),
                        common, route.equals("DIRECT") ? directSetup.measurement() : optimizedSetup.measurement(),
                        execution.measurement(), audit.measurement()));
                }
            }
            sequence++;
        }
        var codec = new CompiledAstReplayCodec();
        return new Report(config, rows, optimized.search().totalWork(), optimized.setupWork(), optimized.search().inputScore(),
            optimized.search().outputScore(), optimized.search().search().outcome().name(),
            optimized.search().search().metrics().exploredStates(), optimized.search().witness().stream().map(step -> step.move().ruleId()).toList(),
            codec.encodeExpression(bootstrap.value().source().expression()), codec.encodeExpression(optimized.plan().expression()));
    }
    private static Bootstrap bootstrap() {
        var domain = new ModularJointPlans(new ModularComputationDomain(Set.of("x"), Set.of("N"),
            Set.of(new ModularComputationDomain.NormalizedInput("a", "N"))));
        Expr a = new VariableExpr("a"), x = new VariableExpr("x"), n = new VariableExpr("N");
        Expr firstExponent = new BinaryExpr(x, BinaryOperator.ADD, new NumberExpr(1));
        Expr secondExponent = new BinaryExpr(new BinaryExpr(new NumberExpr(2), BinaryOperator.MUL, x), BinaryOperator.ADD, new NumberExpr(1));
        var outputs = new LinkedHashMap<String, Expr>();
        outputs.put("u", new FunctionExpr("modpow", List.of(a, firstExponent, n)));
        outputs.put("v", new FunctionExpr("modpow", List.of(a, secondExponent, n)));
        return new Bootstrap(domain, domain.plan(outputs, Set.of("a", "x", "N")));
    }
    private static List<Input> inputs(int length, int repetition, Config config) {
        BigInteger n = BigInteger.ONE.shiftLeft(config.modulusBits()).subtract(BigInteger.valueOf(159));
        BigInteger initialExponent = BigInteger.ONE.shiftLeft(config.exponentBits() - 1);
        var result = new ArrayList<Input>(length);
        for (int i = 0; i < length; i++) {
            BigInteger a = BigInteger.valueOf(2L + i + 13L * repetition).mod(n);
            BigInteger x = initialExponent.add(BigInteger.valueOf(7_919L * i + 104_729L * repetition + length));
            result.add(new Input(a, x, n, Map.of("a", a, "x", x, "N", n)));
        }
        return List.copyOf(result);
    }
    private static Map<String, Object> direct(ModularJointPlans domain, Input input) {
        domain.validateInputs(input.values());
        BigInteger u = input.a().modPow(input.x().add(BigInteger.ONE), input.n());
        BigInteger v = input.a().modPow(input.x().multiply(BigInteger.TWO).add(BigInteger.ONE), input.n());
        return Map.of("u", u, "v", v);
    }
    private static String audit(List<Input> inputs, List<Map<String, Object>> results) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            if (inputs.size() != results.size()) throw new IllegalStateException("result cardinality differs");
            for (int i = 0; i < inputs.size(); i++) {
                Input input = inputs.get(i);
                var actual = results.get(i);
                BigInteger u = input.a().modPow(input.x().add(BigInteger.ONE), input.n());
                BigInteger v = input.a().modPow(input.x().shiftLeft(1).add(BigInteger.ONE), input.n());
                if (!actual.keySet().equals(Set.of("u", "v")) || !u.equals(actual.get("u")) || !v.equals(actual.get("v")))
                    throw new IllegalStateException("independent output audit failed at input " + i);
                digest.update((u + "/" + v + ";").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private <T> Measured<T> measure(Supplier<T> operation) {
        long heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        long allocatedBefore = allocated(), cpuBefore = cpu(), wallBefore = System.nanoTime();
        T result = operation.get();
        long wall = System.nanoTime() - wallBefore, cpuAfter = cpu(), allocatedAfter = allocated();
        long heapAfter = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        return new Measured<>(result, new Measurement(wall, difference(cpuBefore, cpuAfter), difference(allocatedBefore, allocatedAfter), heapBefore, heapAfter));
    }
    private long cpu() { return operatingSystem == null ? -1 : operatingSystem.getProcessCpuTime(); }
    private long allocated() {
        return threads == null || !threads.isThreadAllocatedMemorySupported() || !threads.isThreadAllocatedMemoryEnabled()
            ? -1 : threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }
    private static long difference(long before, long after) { return before < 0 || after < 0 ? -1 : after - before; }
    private static long availableSum(long left, long right) { return left < 0 || right < 0 ? -1 : left + right; }
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("usage: ModularJointPlanExperiment OUTPUT_JSON");
        var report = new ModularJointPlanExperiment().run(new Config(List.of(1, 10, 100, 1_000), 3, 1_024, 256));
        Path output = Path.of(arguments[0]).toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toJson() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        System.out.println("Wrote " + output);
    }
}
