package de.regelsuche.mining;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.FamilyAblation;
import de.regelsuche.mining.CrossFamilyBridgeAblationEvaluator.RunEvidence;
import de.regelsuche.mining.CrossFamilyBridgeHypothesisBuilder.BridgeHypothesis;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilyRole;
import de.regelsuche.mining.CrossFamilyBridgeTransferEvaluator.FamilySuite;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import de.regelsuche.mining.OpenTargetConjectureMiner.MiningReport;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetObservation;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/** Executable comparison registered before the first run on these public families. */
public final class PublicFamilyTransferComparison {
    private static final String REGISTRATION = "bc52b33d5dc44d1c7661a6335cb49b33733bb616";
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private static final SearchHeuristic BUDGET = new SearchHeuristic(3, 80, 1, 2, 40, 16);
    private static final String FORMATION = "f9cfcacdfa85a326fd758e70aeb74d9ee3972edbde4d8181a0db2d369dcf21e8";
    private static final String LABELS = "ef33ccd33dc3d2d48609bb0971abc9a0211e5f137d5d9ee7dd78254d2e0e7265";
    private static final String FROZEN_FORMATION = "d613013350cf0959d6e4e45f7f16d9d31b8f3cb6231fc1fe287443c186d70bef";
    private static final String EVALUATION = "593c4ff74cc7095af0578f3fca997c868ca50a3036c9455dc908d50969e1696d";

    private PublicFamilyTransferComparison() {}

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static JsonNode read(Path file, String expectedHash) throws Exception {
        var bytes = Files.readAllBytes(file);
        if (!hash(bytes).equals(expectedHash)) throw new IllegalArgumentException("Changed registered input: " + file);
        return JSON.readTree(bytes);
    }

    private static SearchProblem problem(String root, TransformationEngine engine) {
        return new SearchProblem(root, engine, new ExpressionScorer(), new ExpressionCanonicalizer(), BUDGET);
    }

    static JsonNode form(Path input) throws Exception {
        var result = JSON.createObjectNode().put("registration", REGISTRATION).put("formationInput", FORMATION);
        var batches = result.putArray("blindBatches");
        var blindSets = new TreeMap<String, MiningReport>();
        for (var batch : read(input.resolve("formation.json"), FORMATION)) {
            String id = batch.required("batch").asText();
            var rows = batches.addObject().put("batch", id);
            var observations = new ArrayList<OpenTargetObservation>();
            var searches = rows.putArray("searches");
            for (var observation : batch.required("observations")) {
                String source = observation.required("expression").asText();
                String observationId = observation.required("id").asText();
                var run = new BestFirstSearchStrategy().searchWithDiagnostics(problem(source, new AstRewriteTransformationEngine()));
                observations.add(OpenTargetObservation.from(observationId, "", source, run));
                searches.addObject().put("id", observationId).put("source", source).put("outcome", run.status().name())
                    .set("work", JSON.valueToTree(run.metrics()));
            }
            var mined = new OpenTargetConjectureMiner().mine(observations);
            rows.set("mining", JSON.valueToTree(mined));
            blindSets.put(id, mined);
        }
        var families = read(input.resolve("posthoc-family-labels.json"), LABELS);
        var candidates = new ArrayList<OpenTargetConjecture>();
        for (var batch : blindSets.entrySet()) {
            for (var c : batch.getValue().conjectures()) {
                String provenance = hash(String.join("\n", c.supportingObservationIds()).getBytes(StandardCharsets.UTF_8));
                candidates.add(new OpenTargetConjecture(c.conjectureId() + "-" + provenance, c.leftPattern(),
                    c.rightPattern(), c.supportCount(), c.distinctAlphaSupport(), List.of(families.required(batch.getKey()).asText()),
                    c.supportingObservationIds(), c.evidence(), c.parameterRelations(), c.expressionPlaceholderValues(),
                    c.candidateStatus(), c.evidenceStatus()));
            }
        }
        var clusters = new CrossFamilyStructuralClusterer().cluster(candidates);
        result.set("clusters", JSON.readTree(clusters.toCanonicalJson()));
        var bridges = result.putArray("bridges");
        for (var cluster : clusters.clusters()) {
            if (cluster.status() == CrossFamilyStructuralClusterer.ClusterStatus.ELIGIBLE_BRIDGE) {
                var members = candidates.stream().filter(c -> cluster.candidateIds().contains(c.conjectureId())).toList();
                var hypothesis = new CrossFamilyBridgeHypothesisBuilder().form(cluster, members);
                bridges.add(JSON.valueToTree(hypothesis));
            }
        }
        return result;
    }

    private static FamilySuite suite(JsonNode row) {
        var id = row.required("family").asText();
        return new FamilySuite(id, FamilyRole.valueOf(row.required("role").asText()), REGISTRATION + "/" + id,
            List.of(new PositiveHoldout(id + "-positive", row.required("positiveSource").asText(), row.required("positiveTarget").asText())),
            List.of(new NegativeHoldout(id + "-near-miss", row.required("negativeSource").asText())),
            CounterexampleBudget.defaultBudget());
    }

    static JsonNode evaluate(JsonNode formation, Path input) throws Exception {
        String formationHash = hash(JSON.writeValueAsBytes(formation));
        if (!formationHash.equals(FROZEN_FORMATION)) {
            throw new IllegalArgumentException("Formation differs from the retained blind-stage output");
        }
        var rows = read(input.resolve("evaluation.json"), EVALUATION);
        var families = new ArrayList<FamilySuite>();
        rows.forEach(row -> families.add(suite(row)));
        var result = JSON.createObjectNode().put("registration", REGISTRATION).put("evaluationInput", EVALUATION)
            .put("formationBytesSha256", formationHash);
        var evaluations = result.putArray("bridges");
        for (var frozen : formation.required("bridges")) {
            var bridge = JSON.treeToValue(frozen, BridgeHypothesis.class);
            var transfer = new CrossFamilyBridgeTransferEvaluator().evaluate(bridge, families);
            var evaluation = evaluations.addObject().put("formationHash", bridge.formationHash());
            evaluation.set("transfer", JSON.readTree(transfer.toCanonicalJson()));
            var evidence = evaluation.putArray("familyRuns");
            var ablations = new ArrayList<FamilyAblation>();
            var operator = new DynamicOperatorCompiler().compile(bridge.hypothesisId(), REGISTRATION,
                bridge.leftPattern(), bridge.rightPattern()).operator().orElseThrow();
            boolean invalidTargetReached = false;
            for (var row : rows) {
                var family = suite(row);
                var plan = new EvaluationPlan(family.revision(), family.positiveHoldouts(), family.negativeHoldouts(),
                    family.counterexampleBudget());
                var checked = new OpenTargetConjectureEvaluator().evaluate(bridge.conjecture(), plan);
                var proof = new OpenTargetConjectureProofGate().evaluate(bridge.conjecture(), checked);
                var familyRow = evidence.addObject().put("family", family.familyId()).put("role", family.role().name());
                familyRow.set("validation", JSON.valueToTree(checked));
                familyRow.set("proof", JSON.readTree(proof.toCanonicalJson()));
                for (var control : List.of("positive", "negative")) {
                    String source = row.required(control + "Source").asText();
                    String target = row.required(control + "Target").asText();
                    var pair = familyRow.putObject(control).put("source", source).put("target", target);
                    var without = measure(source, target, null, pair.putObject("without"));
                    var with = measure(source, target, operator, pair.putObject("with"));
                    if (control.equals("positive")) ablations.add(new FamilyAblation(family.familyId(), family.role(), with, without));
                    else invalidTargetReached |= with.successful() || without.successful();
                }
            }
            var ablation = new CrossFamilyBridgeAblationEvaluator().evaluate(bridge, transfer, ablations);
            evaluation.set("positiveTaskAblation", JSON.readTree(ablation.toCanonicalJson()));
            evaluation.put("invalidTargetReached", invalidTargetReached);
        }
        result.put("status", evaluations.isEmpty() ? "NO_ELIGIBLE_BRIDGE" : "EVALUATED");
        return result;
    }

    private static RunEvidence measure(String source, String target, DynamicPatternOperator bridge, ObjectNode record) {
        var nativeEngine = new AstRewriteTransformationEngine();
        var applications = record.putArray("bridgeApplications");
        var calls = record.putArray("expansions");
        TransformationEngine engine = expression -> {
            var call = calls.addObject().put("expression", expression);
            List<Transformation> nativeSteps = nativeEngine.transform(expression);
            call.put("nativeCandidates", nativeSteps.size()).put("bridgeInvoked", bridge != null);
            if (bridge == null) return nativeSteps;
            var extra = bridge.generateCandidates(expression);
            call.put("bridgeCandidates", extra.size());
            var transformations = new ArrayList<>(nativeSteps);
            transformations.addAll(extra);
            for (var step : extra) applications.addObject().put("source", expression).put("output", step.transformedExpression());
            return transformations;
        };
        var run = new BestFirstSearchStrategy().searchWithDiagnostics(problem(source, engine)
            .withTarget(SearchProblem.SearchTarget.syntaxExact(target)));
        var state = run.reached() ? run.reachedState() : run.bestState();
        record.put("status", run.status().name()).put("bestDistance", run.bestDistance());
        record.set("work", JSON.valueToTree(run.metrics()));
        record.set("path", JSON.valueToTree(state.path()));
        record.set("rules", JSON.valueToTree(state.appliedRuleIds()));
        record.set("assumptions", JSON.valueToTree(state.assumptions()));
        return new RunEvidence(run.reached(), run.reached() ? state.depth() : -1, run.metrics().exploredStates());
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || !((args[0].equals("form") && args.length == 3)
                || (args[0].equals("evaluate") && args.length == 4))) {
            throw new IllegalArgumentException("form INPUTS OUTPUT or evaluate INPUTS OUTPUT FROZEN_FORMATION");
        }
        Path inputs = Path.of(args[1]);
        JsonNode stage = switch (args[0]) {
            case "form" -> form(inputs);
            case "evaluate" -> evaluate(JSON.readTree(Files.readAllBytes(Path.of(args[3]))), inputs);
            default -> throw new IllegalArgumentException("Expected form or evaluate");
        };
        Files.write(Path.of(args[2]), JSON.writeValueAsBytes(stage));
    }
}
