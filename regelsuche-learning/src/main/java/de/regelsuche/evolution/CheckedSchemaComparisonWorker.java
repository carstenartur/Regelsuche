package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.inventory.TypedPolicySelection;
import de.regelsuche.inventory.TypedSourcePolicySelection;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Paid train/persist/restore lifecycle for the separately registered v2 experiment.
 * All search, learning, mathematical checking and policy selection use existing services.
 * The same scoped codec reuse and state valuation are available to every Java profile.
 */
public final class CheckedSchemaComparisonWorker {
    static final String REVISION = "regelsuche.checked-schema-comparison-profile/v2";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static final Set<String> PROFILES = Set.of("BASE", "LEARNED_SCHEMA", "LEARNED_SELECTED");
    static final List<String> SELECTION_SOURCES = List.of("((m+n)*(m-n)+n*n)*(m+3)",
        "((p+q)*(p-q)+q*q)+(p+5)", "(r+s)*(r-s)+s*(s+2)", "t+17");
    private static final Map<String, Integer> SETTINGS = Map.of("workBudget", 16384, "maxDepth", 9,
        "maxStates", 48, "maximumSchemasPerOccurrence", 4, "complexityDebt", 24,
        "codecEntries", 256, "codecCharacters", 2_000_000);
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state -> TypedPolynomialSurfaceCost.evaluate(state.expression());
    private static final MovePriorityPolicy SCHEMA_FIRST = new TypedMoveSearch.TypedPolicy() {
        @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
        @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
            return provider.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED
                : MovePriorityPolicy.INVENTORY_ORDER.stage(provider, state, context);
        }
    };
    private String profile, frozen, frozenHash;
    private Set<String> exclusions;
    private TypedLearnedMoveInventory inventory;
    private CheckedLearnedSchemaModel model;
    private TypedLearnedMoveInventory.SearchSession session;
    private TypedSourcePolicySelection.Profile selected;

    public Map<String, Object> initialize(String requestedProfile) {
        requireNew(requestedProfile);
        var measurement = Measurement.start();
        var genome = TraceStrategyTransferExample.inventory();
        exclusions = exclusions();
        long trainingWork = 0;
        if (requestedProfile.equals("BASE")) inventory = TypedLearnedMoveInventory.primitives(genome);
        else {
            var formation = scoped(() -> new TraceRewriteStrategyLearner().learn(genome,
                TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits()));
            inventory = formation.typedMoves();
            model = inventory.checkedSchemas();
            trainingWork = Math.addExact(inventory.formationWork(), model.formationWork());
        }
        var utilities = new TreeMap<String, Double>();
        List<String> included = model == null ? List.of() : model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).toList();
        String policyEvidence = "FIXED_PROFILE;NO_FITTING";
        if (requestedProfile.equals("LEARNED_SELECTED")) {
            var trained = scoped(() -> selectPolicy(utilities));
            selected = trained.selected();
            trainingWork = Math.addExact(trainingWork, trained.trainingWork());
            policyEvidence = trained.toCanonicalJson();
            included = selected.id().equals("BASE") ? List.of()
                : selected.id().startsWith("SINGLE:") ? List.of(selected.id().substring("SINGLE:".length())) : included;
        } else selected = new TypedSourcePolicySelection.Profile(requestedProfile.equals("BASE") ? "BASE" : "ALL",
            requestedProfile.equals("BASE") ? inventory.primitiveProviders() : inventory.newSchemaSearchSession(model, 4, Map.of()).providers(),
            requestedProfile.equals("BASE") ? MovePriorityPolicy.INVENTORY_ORDER : SCHEMA_FIRST);
        profile = requestedProfile;
        prepare(selected.id(), included, utilities);
        var envelope = new TreeMap<String, Object>();
        envelope.put("schema", REVISION); envelope.put("profile", profile); envelope.put("inventoryHash", genome.contentHash());
        envelope.put("settings", SETTINGS); envelope.put("excludedIdentities", new TreeSet<>(exclusions));
        envelope.put("checkedSchemas", model == null ? "" : model.toCanonicalJson());
        envelope.put("selected", selected.id()); envelope.put("includedSchemaIds", included);
        envelope.put("schemaUtilities", utilities); envelope.put("selectionEvidence", policyEvidence);
        envelope.put("trainingWork", trainingWork);
        frozen = LearnedSchedulingArtifacts.json(envelope);
        frozenHash = SchematicProofPlan.hash(frozen);
        var response = initialized(); response.put("trainingWork", trainingWork);
        return measurement.finish(response);
    }

    /** The serialized theorem is re-proved; no process-local learned capability is required. */
    public Map<String, Object> restore(String requestedProfile, String json) {
        requireNew(requestedProfile);
        var measurement = Measurement.start();
        if (json == null || json.length() > 2_000_000) throw new IllegalArgumentException("model size limit");
        JsonNode root = read(json);
        fields(root, Set.of("schema", "profile", "inventoryHash", "settings", "excludedIdentities", "checkedSchemas",
            "selected", "includedSchemaIds", "schemaUtilities", "selectionEvidence", "trainingWork"));
        var genome = TraceStrategyTransferExample.inventory();
        if (!REVISION.equals(text(root, "schema")) || !requestedProfile.equals(text(root, "profile"))
                || !genome.contentHash().equals(text(root, "inventoryHash")) || !JSON.valueToTree(SETTINGS).equals(root.get("settings"))
                || !root.path("trainingWork").canConvertToLong() || root.path("trainingWork").longValue() < 0)
            throw new IllegalArgumentException("incompatible profile semantics");
        exclusions = exclusions();
        if (!new TreeSet<>(strings(root.get("excludedIdentities"), 64)).equals(new TreeSet<>(exclusions)))
            throw new IllegalArgumentException("changed TRAIN/selection exclusion set");
        inventory = TypedLearnedMoveInventory.primitives(genome);
        String schemas = text(root, "checkedSchemas");
        if (requestedProfile.equals("BASE") && !schemas.isEmpty() || !requestedProfile.equals("BASE") && schemas.isEmpty())
            throw new IllegalArgumentException("profile knowledge mismatch");
        model = schemas.isEmpty() ? null : CheckedLearnedSchemaModel.load(schemas, genome.contentHash());
        var utilities = new TreeMap<String, Double>();
        if (!root.path("schemaUtilities").isObject()) throw new IllegalArgumentException("utilities required");
        root.get("schemaUtilities").properties().forEach(entry -> {
            if (!entry.getValue().isNumber() || !Double.isFinite(entry.getValue().doubleValue()))
                throw new IllegalArgumentException("finite utility required");
            utilities.put(entry.getKey(), entry.getValue().doubleValue());
        });
        String choice = text(root, "selected");
        List<String> included = strings(root.get("includedSchemaIds"), 32);
        if (requestedProfile.equals("BASE") && !choice.equals("BASE") || requestedProfile.equals("LEARNED_SCHEMA") && !choice.equals("ALL"))
            throw new IllegalArgumentException("fixed profile selection changed");
        prepare(choice, included, utilities);
        text(root, "selectionEvidence"); // Descriptive scheduling evidence never authorizes a theorem.
        profile = requestedProfile; frozen = json; frozenHash = SchematicProofPlan.hash(json);
        var response = initialized(); response.put("restoreWork", model == null ? 0L : model.loadWork());
        return measurement.finish(response);
    }

    private TypedSourcePolicySelection.Frozen selectPolicy(Map<String, Double> utility) {
        var all = inventory.newSchemaSearchSession(model, 4, Map.of());
        var tasks = new ArrayList<TypedPolicySelection.TrainingTask>();
        for (int i = 0; i < SELECTION_SOURCES.size(); i++) tasks.add(new TypedPolicySelection.TrainingTask("selection-" + i,
            problem(SELECTION_SOURCES.get(i), MoveContext.Phase.TRAIN, all, MovePriorityPolicy.INVENTORY_ORDER)));
        var profiles = new ArrayList<TypedSourcePolicySelection.Profile>();
        profiles.add(new TypedSourcePolicySelection.Profile("BASE", inventory.primitiveProviders(), MovePriorityPolicy.INVENTORY_ORDER));
        profiles.add(new TypedSourcePolicySelection.Profile("ALL", all.providers(), SCHEMA_FIRST));
        // Bounded ablations use the model's stable order, never the final evaluation cases.
        for (var schema : model.schemas().stream().limit(12).toList()) profiles.add(new TypedSourcePolicySelection.Profile("SINGLE:" + schema.id(),
            inventory.newSchemaSearchSession(model, model.providers(4, Map.of(), Set.of(schema.id()))).providers(), SCHEMA_FIRST));
        var trained = new TypedPolicySelection().trainSourceOnly(tasks, profiles, OBJECTIVE);
        var baseline = trained.trials().getFirst();
        for (var trial : trained.trials()) if (trial.profile().id().startsWith("SINGLE:")) {
            double value = (baseline.outputCost() - trial.outputCost()) * (double) (16384L * tasks.size() + baseline.totalWork())
                + baseline.totalWork() - trial.totalWork() - trial.violations() * 1_000_000.0;
            utility.put(trial.profile().id().substring("SINGLE:".length()), value);
        }
        var ranked = new TypedSourcePolicySelection.Profile("RANKED", inventory.newSchemaSearchSession(model, 4, utility).providers(), SCHEMA_FIRST);
        var confirmation = new TypedPolicySelection().trainSourceOnly(tasks, List.of(ranked), OBJECTIVE);
        var trials = new ArrayList<>(trained.trials()); trials.addAll(confirmation.trials());
        var winner = trials.stream().min(Comparator.comparingLong(TypedSourcePolicySelection.Trial::violations)
            .thenComparingLong(TypedSourcePolicySelection.Trial::outputCost).thenComparingLong(TypedSourcePolicySelection.Trial::totalWork)).orElseThrow();
        return new TypedSourcePolicySelection.Frozen(winner.profile(), trials, trained.trainingSources());
    }

    private void prepare(String choice, List<String> included, Map<String, Double> utilities) {
        if (choice.equals("BASE")) {
            if (!included.isEmpty()) throw new IllegalArgumentException("primitive selection contains schemas");
            session = inventory.newSearchSession(false, 0, 0);
        } else {
            if (model == null || !(choice.equals("ALL") || choice.equals("RANKED") || choice.startsWith("SINGLE:")))
                throw new IllegalArgumentException("unknown schema selection");
            if (choice.startsWith("SINGLE:") && !included.equals(List.of(choice.substring("SINGLE:".length()))))
                throw new IllegalArgumentException("single schema selection mismatch");
            Set<String> all = new HashSet<>(model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id).toList());
            if ((choice.equals("ALL") || choice.equals("RANKED")) && !new HashSet<>(included).equals(all))
                throw new IllegalArgumentException("schema selection incomplete");
            session = inventory.newSchemaSearchSession(model, model.providers(4, choice.equals("RANKED") ? utilities : Map.of(), Set.copyOf(included)));
        }
        selected = new TypedSourcePolicySelection.Profile(choice, session.providers(), choice.equals("BASE") ? MovePriorityPolicy.INVENTORY_ORDER : SCHEMA_FIRST);
    }

    public Map<String, Object> run(String requestedProfile, String source) {
        if (profile == null) throw new IllegalStateException("initialize or restore before querying");
        if (!profile.equals(requestedProfile)) throw new IllegalArgumentException("cannot switch frozen profile");
        if (source == null || source.isBlank() || source.length() > 2048) throw new IllegalArgumentException("invalid source");
        var measurement = Measurement.start();
        if (exclusions.contains(new ExactPolynomialAnalysis().alphaIdentity(source))) throw new IllegalArgumentException("TRAIN/selection overlap");
        var result = scoped(() -> {
            var problem = problem(source, MoveContext.Phase.FROZEN_EVALUATION, session, selected.policy());
            var best = new TypedSourceOnlySearch().search(problem, OBJECTIVE);
            var response = new TreeMap<String, Object>();
            response.put("status", "CANDIDATE"); response.put("profile", profile); response.put("input", source);
            response.put("output", ExpressionFormatter.format(best.incumbent().expression()));
            response.put("typedInput", CODEC.encodeExpression(problem.source()));
            response.put("typedOutput", CODEC.encodeExpression(best.incumbent().expression()));
            response.put("target", ""); response.put("targetReached", best.search().reached());
            response.put("inputCost", best.inputScore()); response.put("outputCost", best.outputScore());
            response.put("witness", best.witness().stream().map(step -> Map.of("source", CODEC.encodeExpression(step.source().expression()),
                "target", CODEC.encodeExpression(step.target().expression()), "move", step.move(), "verification", step.verification())).toList());
            response.put("search", LearnedSchedulingArtifacts.resultJson(best.search().encodedResult()));
            response.put("selectionWork", best.selectionWork()); response.put("selectedReplayWork", best.replayWork());
            response.put("totalWork", best.totalWork()); response.put("internalWorkWithinBudget", best.withinBudget());
            response.put("modelHash", frozenHash);
            return response;
        });
        return measurement.finish(result);
    }

    private static TypedMoveSearch.Problem problem(String source, MoveContext.Phase phase,
            TypedLearnedMoveInventory.SearchSession session, MovePriorityPolicy policy) {
        return new TypedMoveSearch.Problem(new ExpressionParser().parseTerm(source), TypedMoveSearch.Context.sourceOnly(List.of(), phase),
            session.providers(), policy, session.verifier(), state -> 0, MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED,
            new MoveSearch.Budget(9, 9, 16384, 48, 16384, 24), (state, context) -> {
                var score = OBJECTIVE.evaluate(state);
                return new StateValue.Assessment(Math.toIntExact(score.value()), -score.value(), score.work(), 0, Map.of());
            });
    }
    private static <T> T scoped(Supplier<T> work) { return CompiledAstReplayCodec.withExpressionCache(256, 2_000_000, work); }
    private static Set<String> exclusions() {
        var exact = new ExactPolynomialAnalysis();
        var values = new HashSet<String>();
        TraceStrategyTransferExample.trainingInputs().forEach(input -> values.add(exact.alphaIdentity(input.expression())));
        SELECTION_SOURCES.forEach(source -> values.add(exact.alphaIdentity(source)));
        return Set.copyOf(values);
    }
    private void requireNew(String requestedProfile) {
        if (profile != null) throw new IllegalStateException("already initialized");
        if (!PROFILES.contains(requestedProfile)) throw new IllegalArgumentException("unknown profile");
    }
    private TreeMap<String, Object> initialized() {
        var response = new TreeMap<String, Object>();
        response.put("status", "INITIALIZED"); response.put("profile", profile); response.put("model", frozen);
        response.put("modelHash", frozenHash); response.put("settings", SETTINGS);
        response.put("learnedSchemas", model == null ? 0 : model.schemas().size()); response.put("selected", selected.id());
        return response;
    }

    /** Unknown request fields cannot introduce a hidden target into selection or evaluation. */
    public Map<String, Object> handle(String line) throws java.io.IOException {
        if (line == null || line.length() > 4_000_000) throw new IllegalArgumentException("request size limit");
        JsonNode request = JSON.readTree(line);
        if (request == null || !request.isObject()) throw new IllegalArgumentException("object request required");
        String op = text(request, "op"), requested = text(request, "profile");
        if (op.equals("initialize")) { fields(request, Set.of("op", "profile")); return initialize(requested); }
        if (op.equals("restore")) { fields(request, Set.of("op", "profile", "model")); return restore(requested, text(request, "model")); }
        if (op.equals("run")) { fields(request, Set.of("op", "profile", "source")); return run(requested, text(request, "source")); }
        throw new IllegalArgumentException("unknown operation");
    }
    private static JsonNode read(String json) {
        try { return JSON.readTree(json); }
        catch (java.io.IOException bad) { throw new IllegalArgumentException("invalid model", bad); }
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("object required");
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("unexpected fields");
    }
    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("text field required: " + field);
        return node.get(field).textValue();
    }
    private static List<String> strings(JsonNode node, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) throw new IllegalArgumentException("bounded list required");
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.textValue().length() > 512) throw new IllegalArgumentException("short text required");
            values.add(item.textValue());
        }
        if (values.size() != new HashSet<>(values).size()) throw new IllegalArgumentException("duplicate list item");
        return List.copyOf(values);
    }
    private record Measurement(long wall, long cpu, long allocated) {
        static Measurement start() { return new Measurement(System.nanoTime(), cpuNow(), allocatedNow()); }
        Map<String, Object> finish(TreeMap<String, Object> response) {
            response.put("nativeWallNanos", System.nanoTime() - wall);
            response.put("nativeCpuNanos", delta(cpu, cpuNow()));
            response.put("nativeAllocatedBytes", delta(allocated, allocatedNow()));
            return response;
        }
        private static long delta(long before, long after) { return before < 0 || after < 0 ? -1 : Math.max(0, after - before); }
        private static long cpuNow() { return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean ? bean.getProcessCpuTime() : -1; }
        private static long allocatedNow() { return ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
            && bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1; }
    }
    public static void main(String[] args) throws Exception {
        var worker = new CheckedSchemaComparisonWorker();
        System.out.println("{\"status\":\"READY\",\"java\":\"" + Runtime.version() + "\"}");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                try { System.out.println(LearnedSchedulingArtifacts.json(worker.handle(line))); }
                catch (RuntimeException | java.io.IOException failure) { System.out.println(LearnedSchedulingArtifacts.json(Map.of("status", "ERROR", "error", failure.toString()))); }
                System.out.flush();
            }
        }
    }
}
