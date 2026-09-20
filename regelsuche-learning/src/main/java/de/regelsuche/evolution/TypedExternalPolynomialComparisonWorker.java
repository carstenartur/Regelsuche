package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.inventory.HistoryMovePolicy;
import de.regelsuche.inventory.RuleHistoryMemory;
import de.regelsuche.inventory.TypedPolicySelection;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
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

/** Opt-in source-only development worker. Each process owns exactly one frozen profile. */
public final class TypedExternalPolynomialComparisonWorker {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROFILES = Set.of("BASE", "EXPERT", "PRIMITIVE_SELECTED", "LEARNED_NAIVE", "LEARNED_RANKED");
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Map<String, Integer> SETTINGS = Map.of("workBudget", 16384, "primitiveDepth", 9,
        "searchDepth", 9, "maxStates", 256, "complexityDebt", 24);
    private final Set<String> exclusions = new HashSet<>();
    private String profile;
    private TypedLearnedMoveInventory inventory;
    private TraceRewriteStrategyLearner.FrozenStrategy formation;
    private TypedLearningWorkStudy.Training training;
    private String frozen;

    public Map<String, Object> initialize(String requestedProfile) {
        if (profile != null) throw new IllegalStateException("already initialized");
        if (!PROFILES.contains(requestedProfile)) throw new IllegalArgumentException("unknown profile");
        long start = System.nanoTime(), cpu = cpu();
        var genome = TraceStrategyTransferExample.inventory();
        var exact = new ExactPolynomialAnalysis();
        for (var input : TraceStrategyTransferExample.trainingInputs()) exclusions.add(exact.alphaIdentity(input.expression()));
        if (requestedProfile.equals("BASE") || requestedProfile.equals("EXPERT")) {
            inventory = TypedLearnedMoveInventory.primitives(genome);
        } else {
            formation = new TraceRewriteStrategyLearner().learn(genome,
                TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
            inventory = formation.typedMoves();
            exclusions.addAll(formation.excludedIdentities());
            if (!requestedProfile.equals("LEARNED_NAIVE")) {
                training = TypedLearningWorkStudy.train(formation, inventory, requestedProfile.equals("LEARNED_RANKED"));
            }
        }
        profile = requestedProfile;
        frozen = modelJson();
        var costs = new TreeMap<String, Long>();
        costs.put("formation", inventory.formationWork());
        costs.put("historySearch", training == null ? 0 : training.historySearchWork());
        costs.put("historyMemory", training == null ? 0 : training.memoryWork());
        costs.put("policyTrials", training == null ? 0 : training.policy().trainingWork());
        var result = new TreeMap<String, Object>();
        result.put("status", "INITIALIZED"); result.put("profile", profile);
        result.put("model", frozen); result.put("modelHash", SchematicProofPlan.hash(frozen));
        result.put("settings", SETTINGS); result.put("trainingWorkComponents", costs);
        result.put("trainingWork", costs.values().stream().mapToLong(Long::longValue).reduce(0, Math::addExact));
        result.put("learnedPrograms", profile.startsWith("LEARNED_") ? inventory.learnedPrograms().size() : 0);
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(cpu));
        return result;
    }

    public Map<String, Object> run(String requestedProfile, String source) {
        if (profile == null) throw new IllegalStateException("initialize before querying");
        if (!profile.equals(requestedProfile)) throw new IllegalArgumentException("cannot switch frozen profile");
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("invalid source");
        long start = System.nanoTime(), cpu = cpu();
        if (exclusions.contains(new ExactPolynomialAnalysis().alphaIdentity(source))) throw new IllegalArgumentException("TRAIN overlap");
        var expression = new ExpressionParser().parseTerm(source);
        var providers = profile.startsWith("LEARNED_") ? inventory.providers() : inventory.primitiveProviders();
        var problem = new TypedMoveSearch.Problem(expression,
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            providers, policy(), inventory.verifier(), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(9, 9, 0, 256, 16384, 24));
        var selected = new TypedSourceOnlySearch().search(problem,
            state -> TypedPolynomialSurfaceCost.evaluate(state.expression()));
        if (!frozen.equals(modelJson())) throw new IllegalStateException("evaluation changed frozen knowledge");
        var result = new TreeMap<String, Object>();
        result.put("status", "CANDIDATE"); result.put("profile", profile); result.put("input", source);
        result.put("output", ExpressionFormatter.format(selected.incumbent().expression()));
        result.put("typedInput", CODEC.encodeExpression(expression));
        result.put("typedOutput", CODEC.encodeExpression(selected.incumbent().expression()));
        result.put("target", ""); result.put("targetReached", selected.search().reached());
        result.put("inputCost", selected.inputScore()); result.put("outputCost", selected.outputScore());
        result.put("witness", selected.witness().stream().map(step -> Map.of(
            "source", CODEC.encodeExpression(step.source().expression()), "target", CODEC.encodeExpression(step.target().expression()),
            "move", step.move(), "verification", step.verification())).toList());
        result.put("search", LearnedSchedulingArtifacts.resultJson(selected.search().encodedResult()));
        result.put("selectionWork", selected.selectionWork()); result.put("selectedReplayWork", selected.replayWork());
        result.put("totalWork", selected.totalWork()); result.put("internalWorkWithinBudget", selected.withinBudget());
        result.put("modelHash", SchematicProofPlan.hash(frozen));
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(cpu));
        return result;
    }

    private MovePriorityPolicy policy() {
        if (training != null) {
            var selected = training.policy().selected();
            return selected.kind() == TypedPolicySelection.PolicyKind.INVENTORY_ORDER ? MovePriorityPolicy.INVENTORY_ORDER
                : HistoryMovePolicy.typed(training.policy().history(), selected.weights());
        }
        // EXPERT is the pre-existing fixed default weighting, not an oracle or a fitted policy.
        return profile.equals("EXPERT") ? HistoryMovePolicy.typed(new RuleHistoryMemory().freeze(), HistoryMovePolicy.Weights.DEFAULT)
            : MovePriorityPolicy.INVENTORY_ORDER;
    }

    private String modelJson() {
        var model = new TreeMap<String, Object>();
        model.put("schema", "regelsuche.typed-external-frozen-profile/v1"); model.put("profile", profile);
        model.put("inventory", TraceStrategyTransferExample.inventory().toCanonicalJson());
        model.put("settings", SETTINGS); model.put("excludedIdentities", exclusions);
        model.put("formation", formation == null ? "" : formation.toCanonicalJson());
        model.put("policy", training == null ? (profile.equals("EXPERT") ? "FIXED_DEFAULT_WEIGHTS" : "INVENTORY_ORDER")
            : training.policy().toCanonicalJson());
        model.put("historyRuns", training == null ? List.of() : training.historyRuns().stream().map(run -> Map.of(
            "id", run.id(), "source", run.source(), "goal", run.goal(),
            "search", LearnedSchedulingArtifacts.resultJson(run.result().encodedResult()))).toList());
        return LearnedSchedulingArtifacts.json(model);
    }

    /** Strict source-only boundary; unknown fields cannot smuggle targets into evaluation. */
    public Map<String, Object> handle(String line) throws java.io.IOException {
        if (line == null || line.length() > 8192) throw new IllegalArgumentException("request size limit");
        JsonNode request = JSON.readTree(line);
        if (request == null || !request.isObject() || !request.path("op").isTextual() || !request.path("profile").isTextual()) {
            throw new IllegalArgumentException("invalid request");
        }
        var fields = new HashSet<String>(); request.fieldNames().forEachRemaining(fields::add);
        String op = request.get("op").textValue(), selected = request.get("profile").textValue();
        if (op.equals("initialize") && fields.equals(Set.of("op", "profile"))) return initialize(selected);
        if (op.equals("run") && fields.equals(Set.of("op", "profile", "source")) && request.get("source").isTextual()) {
            return run(selected, request.get("source").textValue());
        }
        throw new IllegalArgumentException("unsupported fields or operation");
    }
    private static long cpu() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem ? operatingSystem.getProcessCpuTime() : -1;
    }
    private static Long elapsedCpu(long before) { long after = cpu(); return before < 0 || after < 0 ? null : after - before; }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("worker accepts source-only stdin requests");
        var worker = new TypedExternalPolynomialComparisonWorker();
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                try { System.out.println(LearnedSchedulingArtifacts.json(worker.handle(line))); }
                catch (RuntimeException | java.io.IOException failure) {
                    System.out.println(LearnedSchedulingArtifacts.json(Map.of("status", "ERROR", "error", failure.toString())));
                }
                System.out.flush();
            }
        }
    }
}
