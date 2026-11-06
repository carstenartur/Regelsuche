package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Thin source-only adapter. A learned producer must exit before a fresh consumer restores its snapshot. */
public final class NativePolynomialComparisonWorker {
    private static final TaskConstructionProfile CAPABILITIES = TaskConstructionProfile.POLYNOMIAL_SIMPLIFICATION_V1;
    private static final Map<String, Integer> SETTINGS = Map.of("workBudget", 1_000_000, "maxTheoryWork", 1_000_000,
        "primitiveDepth", 8, "searchDepth", 8, "maxStates", 32, "complexityDebt", 64);
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private final Set<String> excluded = new HashSet<>();
    private String profile;
    private String frozen;
    private CheckedSchemaTaskSolver solver;

    public Map<String, Object> initialize(String requested) {
        requireFresh(requested);
        long start = System.nanoTime(), cpu = cpu();
        var genome = TraceStrategyTransferExample.inventory();
        var inventory = TypedLearnedMoveInventory.primitives(genome);
        CheckedLearnedSchemaModel model = null;
        long training = 0;
        if (requested.equals("NATIVE_LEARNED")) {
            var formation = new TraceRewriteStrategyLearner().learn(genome,
                TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
            model = formation.typedMoves().checkedSchemas();
            training = Math.addExact(formation.typedMoves().formationWork(), model.formationWork());
        } else solver = inventory.newTaskSolverWithProfile(CAPABILITIES);
        profile = requested;
        var snapshot = new TreeMap<String, Object>();
        snapshot.put("schema", "regelsuche.native-polynomial-snapshot/v1");
        snapshot.put("profile", profile); snapshot.put("capabilityProfile", CAPABILITIES.name());
        snapshot.put("inventoryHash", genome.contentHash()); snapshot.put("settings", SETTINGS);
        snapshot.put("schemas", model == null ? "" : model.toCanonicalJson());
        frozen = LearnedSchedulingArtifacts.json(snapshot);
        return setup(model == null ? "INITIALIZED" : "ACQUIRED", training, 0, model == null ? 0 : model.schemas().size(), start, cpu);
    }

    public Map<String, Object> restore(String requested, String snapshot) {
        requireFresh(requested);
        if (!requested.equals("NATIVE_LEARNED")) throw new IllegalArgumentException("only the learned profile restores knowledge");
        long start = System.nanoTime(), cpu = cpu();
        var value = CheckedSchemaSupport.read(snapshot);
        CheckedSchemaSupport.fields(value, "schema", "profile", "capabilityProfile", "inventoryHash", "settings", "schemas");
        var genome = TraceStrategyTransferExample.inventory();
        if (!value.path("schema").asText().equals("regelsuche.native-polynomial-snapshot/v1")
                || !value.path("profile").asText().equals(requested)
                || !value.path("capabilityProfile").asText().equals(CAPABILITIES.name())
                || !value.path("inventoryHash").asText().equals(genome.contentHash())
                || !value.path("settings").equals(CheckedSchemaSupport.JSON.valueToTree(SETTINGS)))
            throw new IllegalArgumentException("snapshot profile, settings or inventory mismatch");
        var model = CheckedLearnedSchemaModel.load(CheckedSchemaSupport.text(value, "schemas"), genome.contentHash());
        solver = TypedLearnedMoveInventory.primitives(genome).newTaskSolverWithProfile(model, CAPABILITIES);
        profile = requested;
        frozen = snapshot;
        return setup("INITIALIZED", 0, Math.addExact(model.loadWork(), solver.preparationWork()), model.schemas().size(), start, cpu);
    }

    private Map<String, Object> setup(String status, long training, long restore, int schemas, long start, long beforeCpu) {
        var exact = new ExactPolynomialAnalysis();
        for (var input : TraceStrategyTransferExample.trainingInputs()) excluded.add(exact.alphaIdentity(input.expression()));
        var result = new TreeMap<String, Object>();
        result.put("status", status); result.put("profile", profile); result.put("model", frozen);
        result.put("modelHash", SchematicProofPlan.hash(frozen)); result.put("settings", SETTINGS);
        result.put("trainingWork", training); result.put("restorationWork", restore); result.put("schemas", schemas);
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(beforeCpu));
        return result;
    }

    public Map<String, Object> run(String requested, String source) {
        if (solver == null) throw new IllegalStateException("initialize or restore a query consumer first");
        if (!profile.equals(requested)) throw new IllegalArgumentException("cannot switch profile");
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("source limit");
        long start = System.nanoTime(), beforeCpu = cpu();
        long[] adapter = {source.length()};
        var exact = ExactPolynomialAnalysis.withProductWork(units -> adapter[0] = Math.addExact(adapter[0], units));
        if (excluded.contains(exact.alphaIdentity(source))) throw new IllegalArgumentException("TRAIN overlap");
        var parsed = StructuralExpansionRules.observe(() -> new ExpressionParser().parseExactTerm(source).expression());
        adapter[0] = Math.addExact(adapter[0], parsed.work());
        var budget = new MoveSearch.Budget(8, 8, 1_000_000, 32, Math.max(1, 1_000_000 - adapter[0]), 64);
        var selected = solver.simplify(parsed.value(), List.of(), budget);
        var rendered = StructuralExpansionRules.observe(() -> ExpressionFormatter.format(selected.incumbent().expression()));
        adapter[0] = Math.addExact(adapter[0], Math.addExact(rendered.work(), rendered.value().length()));
        long total = Math.addExact(adapter[0], selected.totalWork());
        var result = new TreeMap<String, Object>();
        result.put("status", "CANDIDATE"); result.put("profile", profile); result.put("input", source);
        result.put("output", rendered.value()); result.put("modelHash", SchematicProofPlan.hash(frozen));
        result.put("typedInput", CODEC.encodeExpression(parsed.value()));
        result.put("typedOutput", CODEC.encodeExpression(selected.incumbent().expression()));
        result.put("target", ""); result.put("targetReached", selected.search().reached());
        result.put("inputCost", selected.inputScore()); result.put("outputCost", selected.outputScore());
        result.put("search", LearnedSchedulingArtifacts.resultJson(selected.search().encodedResult()));
        result.put("witness", selected.witness().stream().map(edge -> Map.of("source", CODEC.encodeExpression(edge.source().expression()),
            "target", CODEC.encodeExpression(edge.target().expression()), "move", edge.move(), "verification", edge.verification())).toList());
        result.put("adapterWork", adapter[0]); result.put("selectionWork", selected.selectionWork());
        result.put("selectedReplayWork", selected.replayWork()); result.put("totalWork", total);
        result.put("internalWorkWithinBudget", selected.withinBudget() && total <= 1_000_000);
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(beforeCpu));
        return result;
    }

    private void requireFresh(String requested) {
        if (profile != null) throw new IllegalStateException("worker already configured");
        if (!Set.of("NATIVE_BASE", "NATIVE_LEARNED").contains(requested)) throw new IllegalArgumentException("unknown native profile");
    }

    public Map<String, Object> handle(String line) {
        if (line == null || line.length() > CheckedSchemaSupport.MAXIMUM_JSON_CHARACTERS) throw new IllegalArgumentException("request limit");
        var request = CheckedSchemaSupport.read(line);
        String op = CheckedSchemaSupport.text(request, "op"), requested = CheckedSchemaSupport.text(request, "profile");
        return switch (op) {
            case "initialize" -> { CheckedSchemaSupport.fields(request, "op", "profile"); yield initialize(requested); }
            case "restore" -> { CheckedSchemaSupport.fields(request, "op", "profile", "model"); yield restore(requested, CheckedSchemaSupport.text(request, "model")); }
            case "run" -> { CheckedSchemaSupport.fields(request, "op", "profile", "source"); yield run(requested, CheckedSchemaSupport.text(request, "source")); }
            default -> throw new IllegalArgumentException("unknown operation");
        };
    }

    private static long cpu() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean process ? process.getProcessCpuTime() : -1;
    }
    private static Long elapsedCpu(long before) { long after = cpu(); return before < 0 || after < 0 ? null : after - before; }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("worker accepts source-only JSONL stdin");
        var worker = new NativePolynomialComparisonWorker();
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                try { System.out.println(LearnedSchedulingArtifacts.json(worker.handle(line))); }
                catch (RuntimeException failure) {
                    System.out.println(LearnedSchedulingArtifacts.json(Map.of("status", "ERROR", "error", failure.toString())));
                }
                System.out.flush();
            }
        }
    }
}
