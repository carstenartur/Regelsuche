package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Source-only external pilot adapter; never reads corpus files, labels or targets. */
public final class ExternalPolynomialComparisonWorker {
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> PROFILES = Set.of("BASE", "LEARNED_NAIVE", "LEARNED_RANKED", "EXPERT");
    private LearnedSchedulingModel model;
    private String frozen;

    public Map<String, Object> initialize() {
        if (model != null) throw new IllegalStateException("already initialized");
        long start = System.nanoTime(), cpu = cpu();
        model = LearnedSchedulingModel.train();
        frozen = LearnedSchedulingArtifacts.modelJson(model);
        var result = new TreeMap<String, Object>();
        result.put("status", "INITIALIZED"); result.put("model", frozen);
        result.put("modelHash", SchematicProofPlan.hash(frozen));
        result.put("trainingWork", model.trainingWorkUnits());
        result.put("trainingWorkComponents", model.trainingWorkComponents());
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(cpu));
        result.put("settings", Map.of("workBudget", 16384, "primitiveDepth", 9, "searchDepth", 9, "maxStates", 256, "complexityDebt", 24));
        return result;
    }

    /** Select a verified anytime incumbent; internal overruns remain explicit, not capped away. */
    public Map<String, Object> run(String profile, String source) {
        if (model == null) throw new IllegalStateException("initialize before querying");
        if (!PROFILES.contains(profile)) throw new IllegalArgumentException("unknown profile");
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("invalid source");
        long start = System.nanoTime(), cpu = cpu();
        String input = LearnedSchedulingModel.format(source);
        var exact = new ExactPolynomialAnalysis();
        if (model.knowledge().excludedIdentities().contains(exact.alphaIdentity(input)))
            throw new IllegalArgumentException("TRAIN overlap");
        var configuration = LearnedSchedulingProtocol.configurations().stream().filter(c -> c.id().equals(profile)).findFirst().orElseThrow();
        // Empty goal has no syntactic target match; policies receive no manufactured expression or label.
        var context = new MoveContext("", List.of(), MoveContext.Phase.FROZEN_EVALUATION);
        var verifier = new PrimitiveReplayMoveVerifier(model.knowledge().inventory());
        var search = new MoveSearch().search(new MoveSearch.Problem(input, context, model.providers(configuration),
            model.policy(configuration), verifier, state -> cost(state.expression()), MoveSearch.Mode.FAST,
            configuration.scheduling(), new MoveSearch.Budget(9, 9, 0, 256, 16384, 24), model.stateValue(configuration)));
        if (search.reached()) throw new IllegalStateException("source-only search invented a target hit");
        String output = input;
        MoveState selected = null;
        var parents = new HashMap<MoveState, MoveSearch.Event>();
        for (var event : search.events()) if (event.decision() == MoveSearch.Decision.ENQUEUED) {
            if (event.verification() == null || !event.verification().accepted()) throw new IllegalStateException("unverified admitted state");
            parents.putIfAbsent(event.target(), event);
            String candidate = event.target().expression();
            if (cost(candidate) < cost(output) || (cost(candidate) == cost(output) && candidate.compareTo(output) < 0)) {
                output = candidate; selected = event.target();
            }
        }
        var witness = new ArrayList<MoveSearch.WitnessStep>();
        while (selected != null && selected.searchDepth() > 0) {
            var edge = parents.get(selected);
            if (edge == null) throw new IllegalStateException("incumbent has no retained primitive lineage");
            witness.add(new MoveSearch.WitnessStep(edge.source(), edge.target(), edge.move(), edge.verification()));
            selected = edge.source();
        }
        Collections.reverse(witness);
        long replayWork = 0;
        String current = input;
        for (var step : witness) {
            if (!current.equals(step.source().expression())) throw new IllegalStateException("broken selected witness");
            var replay = verifier.verify(step.source(), step.move(), context);
            replayWork = Math.addExact(replayWork, replay.work());
            if (!replay.accepted() || !replay.equals(step.verification())) throw new IllegalStateException("selected replay mismatch");
            current = step.target().expression();
        }
        if (!current.equals(output)) throw new IllegalStateException("selected witness endpoint mismatch");
        if (!frozen.equals(LearnedSchedulingArtifacts.modelJson(model))) throw new IllegalStateException("evaluation changed TRAIN model");
        var result = new TreeMap<String, Object>();
        result.put("status", "CANDIDATE"); result.put("profile", profile); result.put("input", input); result.put("output", output);
        result.put("target", ""); result.put("targetReached", false); result.put("modelHash", SchematicProofPlan.hash(frozen));
        result.put("inputCost", cost(input)); result.put("outputCost", cost(output)); result.put("witness", witness);
        result.put("search", LearnedSchedulingArtifacts.resultJson(search)); result.put("selectedReplayWork", replayWork);
        result.put("internalWorkWithinBudget", search.metrics().totalWork() <= 16384);
        result.put("nativeWallNanos", System.nanoTime() - start); result.put("nativeCpuNanos", elapsedCpu(cpu));
        return result;
    }

    /** Mirrors the common judge's surface cost, including unary minus as parser-generated 0 - x. */
    public static int cost(String source) { return cost(new ExpressionParser().parseTerm(source)); }
    private static int cost(Expr expression) {
        if (expression instanceof BinaryExpr binary) return 1 + cost(binary.left()) + cost(binary.right());
        if (expression instanceof NumberExpr || expression instanceof VariableExpr) return 0;
        throw new IllegalArgumentException("not polynomial syntax");
    }
    private static long cpu() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean operatingSystem ? operatingSystem.getProcessCpuTime() : -1;
    }
    private static Long elapsedCpu(long before) { long after = cpu(); return before < 0 || after < 0 ? null : after - before; }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("worker takes source-only requests on stdin, no corpus path");
        var worker = new ExternalPolynomialComparisonWorker();
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                try {
                    JsonNode request = JSON.readTree(line);
                    if (!request.isObject() || !request.path("op").isTextual()) throw new IllegalArgumentException("invalid request");
                    var fields = new java.util.HashSet<String>(); request.fieldNames().forEachRemaining(fields::add);
                    String op = request.get("op").textValue();
                    Object response;
                    if (op.equals("initialize") && fields.equals(Set.of("op"))) response = worker.initialize();
                    else if (op.equals("run") && fields.equals(Set.of("op", "source", "profile"))
                            && request.get("source").isTextual() && request.get("profile").isTextual())
                        response = worker.run(request.get("profile").textValue(), request.get("source").textValue());
                    else throw new IllegalArgumentException("unsupported fields or operation");
                    System.out.println(LearnedSchedulingArtifacts.json(response));
                } catch (RuntimeException | java.io.IOException failure) {
                    System.out.println(LearnedSchedulingArtifacts.json(Map.of("status", "ERROR", "error", failure.toString())));
                }
                System.out.flush();
            }
        }
    }
}
