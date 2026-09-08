package de.regelsuche.evolution;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Learns a branching rule schedule from real, target-free primitive searches.
 * The supplied polynomial rules stay fixed: this learner induces program
 * topology, not new identities. All executions use the existing interpreter
 * and work-budget frontier. No target or evaluation family enters training.
 */
public final class TraceRewriteStrategyLearner {
    public static final String REVISION = "regelsuche.trace-rewrite-strategy-learner/v1";
    private static final long SHUFFLE_SEED = 0x74726163654cL;
    private final ExactPolynomialAnalysis exact = new ExactPolynomialAnalysis();

    public record Input(String id, String expression) {
        public Input {
            id = SchematicProofPlan.requireId(id, "input id");
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record Limits(Budget trainingBudget, int maximumInputs, int maximumTraceSteps,
                         int maximumProgramNodes) {
        public Limits {
            Objects.requireNonNull(trainingBudget, "trainingBudget");
            if (maximumInputs < 2 || maximumInputs > 32 || maximumTraceSteps < 2 || maximumTraceSteps > 8
                    || maximumProgramNodes < 3 || maximumProgramNodes > 128
                    || trainingBudget.maxPrimitiveSteps() > maximumTraceSteps
                    || trainingBudget.maxExactTheoryWorkUnits() != 0
                    || trainingBudget.maxExploredStates() > 512 || trainingBudget.maxCandidatesPerState() > 128
                    || trainingBudget.maxWorkUnits() > 1_000_000) {
                throw new IllegalArgumentException("invalid bounded trace-learning limits");
            }
        }
    }

    public enum Profile { FLAT_RULES, LEARNED_PROGRAM, SHUFFLED_PROGRAM }

    /** Failed, unchanged and budget-limited training searches remain in the model. */
    public record Observation(Input input, String alphaIdentity, Result search,
                              List<String> geneSequence, long replayWorkUnits, int exactAuditCalls) {
        public Observation { geneSequence = List.copyOf(geneSequence); }
        public String searchHash() { return SchematicProofPlan.hash(search.toCanonicalJson()); }
    }

    /** Privately issued after training; serialized plans alone carry no authority. */
    public static final class FrozenStrategy {
        private final EvolutionGenome inventory;
        private final Limits limits;
        private final List<Observation> observations;
        private final List<String> excludedIdentities;
        private final Optional<EvolutionRewriteProgramPlan> plan;
        private final Optional<EvolutionRewriteProgramPlan> shuffled;
        private final String canonicalJson;

        private FrozenStrategy(EvolutionGenome inventory, Limits limits, List<Observation> observations,
                Set<String> excludedIdentities, Optional<EvolutionRewriteProgramPlan> plan,
                Optional<EvolutionRewriteProgramPlan> shuffled) {
            this.inventory = inventory;
            this.limits = limits;
            this.observations = List.copyOf(observations);
            this.excludedIdentities = excludedIdentities.stream().sorted().toList();
            this.plan = plan;
            this.shuffled = shuffled;
            canonicalJson = render();
        }

        public EvolutionGenome inventory() { return inventory; }
        public Limits limits() { return limits; }
        public List<Observation> observations() { return observations; }
        public List<String> excludedIdentities() { return excludedIdentities; }
        public Optional<EvolutionRewriteProgramPlan> plan() { return plan; }
        public Optional<EvolutionRewriteProgramPlan> shuffledPlan() { return shuffled; }
        public String toCanonicalJson() { return canonicalJson; }
        public String contentHash() { return SchematicProofPlan.hash(canonicalJson); }
        public long trainingSearchWorkUnits() {
            return observations.stream().mapToLong(o -> o.search().metrics().chargedSearchWorkUnits())
                .reduce(0L, Math::addExact);
        }
        public long trainingReplayWorkUnits() {
            return observations.stream().mapToLong(Observation::replayWorkUnits).reduce(0L, Math::addExact);
        }
        public int trainingExactAuditCalls() {
            return observations.stream().mapToInt(Observation::exactAuditCalls).sum();
        }

        private String render() {
            var json = new JsonWriter().beginObject().property("schema", REVISION)
                .property("state", "TRAIN_COMPLETE_STRATEGY_FROZEN")
                .property("authority", "EXPERIMENTAL_NO_PRODUCTION_PROMOTION")
                .property("inventoryHash", inventory.contentHash())
                .property("identityRevision", ExactPolynomialAnalysis.REVISION)
                .property("formation", "TARGET_FREE_SCORER_SELECTED_PRIMITIVE_TRACES")
                .property("topology", "OBSERVED_RULE_SEQUENCES_SHARED_PREFIX_CHOICE")
                .property("objective", "EXPRESSION_SCORE_PLUS_2_PER_PRIMITIVE_PLUS_5_PER_EXPANDING_STEP")
                .property("shuffleSeed", SHUFFLE_SEED)
                .property("shufflePolicy", "FIXED_SEED_FISHER_YATES;ROTATE_UNCHANGED_SEQUENCE_ONCE")
                .property("orderAblationChanged", !plan.equals(shuffled))
                .property("maximumInputs", limits.maximumInputs())
                .property("maximumTraceSteps", limits.maximumTraceSteps())
                .property("maximumProgramNodes", limits.maximumProgramNodes())
                .object("trainingBudget", value -> writeBudget(value, limits.trainingBudget()))
                .property("trainingSearchWorkUnits", trainingSearchWorkUnits())
                .property("trainingReplayWorkUnits", trainingReplayWorkUnits())
                .property("trainingExactAuditCalls", trainingExactAuditCalls())
                .property("inventoryIdentityChecks", inventory.rewrites().size())
                .property("workClaim", "SEARCH_MECHANICS_AND_AUDIT_CALLS;NOT_COMPLETE_CPU_OR_LEARNING_COST")
                .stringArray("excludedAlphaPolynomialIdentities", excludedIdentities)
                .property("plan", plan.map(EvolutionRewriteProgramPlan::toCanonicalJson).orElse(""))
                .property("shuffledPlan", shuffled.map(EvolutionRewriteProgramPlan::toCanonicalJson).orElse(""))
                .array("observations", values -> observations.forEach(o -> values.objectValue(value ->
                    value.property("id", o.input().id()).property("input", o.input().expression())
                        .property("alphaIdentity", o.alphaIdentity()).property("searchHash", o.searchHash())
                        .property("status", o.search().status().name())
                        .property("selectedOutput", o.search().bestState().expression())
                        .stringArray("geneSequence", o.geneSequence())
                        .property("replayWorkUnits", o.replayWorkUnits())
                        .property("exactAuditCalls", o.exactAuditCalls()))));
            return json.endObject().toString();
        }
    }

    public record Application(String strategyHash, Profile profile, String input, String alphaIdentity,
                              Result search, int exactAuditCalls) {
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("strategyHash", strategyHash).property("profile", profile.name())
                .property("input", input).property("alphaIdentity", alphaIdentity)
                .property("searchHash", SchematicProofPlan.hash(search.toCanonicalJson()))
                .property("output", search.bestState().expression())
                .property("status", search.status().name())
                .property("score", search.bestState().score().weightedTotal())
                .property("primitiveSteps", search.bestState().primitiveDepth())
                .property("programUsed", search.bestState().programUsed())
                .property("chargedSearchWorkUnits", search.metrics().chargedSearchWorkUnits())
                .property("exactAuditCalls", exactAuditCalls)
                .endObject().toString();
        }
    }

    public FrozenStrategy learn(EvolutionGenome inventory, List<Input> inputs, Limits limits) {
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(limits, "limits");
        if (inventory.trainingScope().sourceSplit() != EvolutionGenome.SourceSplit.TRAIN
                || inventory.rewrites().size() > 16 || limits.maximumProgramNodes() > inventory.budget().maxProgramLength()) {
            throw new IllegalArgumentException("TRAIN inventory and compatible program bounds required");
        }
        List<Input> ordered = List.copyOf(inputs).stream().sorted(Comparator.comparing(Input::id)).toList();
        if (ordered.size() < 2 || ordered.size() > limits.maximumInputs()
                || ordered.stream().map(Input::id).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("two or more distinct bounded TRAIN inputs required");
        }
        validateRules(inventory);
        var compiled = new EvolutionGenomeCompiler().compile(inventory);
        Map<String, String> genes = new HashMap<>();
        Map<String, RewriteRule> rules = new HashMap<>();
        for (int i = 0; i < compiled.rules().size(); i++) {
            RewriteRule rule = compiled.rules().get(i);
            genes.put(rule.id(), inventory.rewrites().get(i).geneId());
            rules.put(rule.id(), rule);
        }
        Set<String> excluded = new TreeSet<>();
        Map<String, String> identities = new HashMap<>();
        for (Input input : ordered) {
            String identity = exact.alphaIdentity(input.expression());
            if (!excluded.add(identity)) throw new IllegalArgumentException("semantically duplicate TRAIN input");
            identities.put(input.id(), identity);
        }
        var flat = flat(inventory);
        List<Observation> observations = new ArrayList<>();
        for (Input input : ordered) {
            Result result = search(input.expression(), flat, limits.trainingBudget());
            var selected = result.bestState();
            List<String> sequence = new ArrayList<>();
            String current = selected.path().getFirst();
            long replayWork = 0;
            int audits = 0;
            for (var step : selected.transformations()) {
                RewriteRule rule = rules.get(step.rule());
                if (rule == null || step.primitiveStepCount() != 1 || step.exactTheoryStepCount() != 0
                        || !step.assumptions().isEmpty()) {
                    throw new IllegalArgumentException("training path is not a supported primitive trace");
                }
                var replay = MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
                    List.of(rule), inventory.budget().maxAstGrowthPerStep(), inventory.budget().maxCandidatesPerState()))
                    .transformMeasured(current);
                replayWork = Math.addExact(replayWork, replay.workMetrics().totalWorkUnits());
                if (!replay.transformations().contains(step)) throw new IllegalArgumentException("primitive replay mismatch");
                exact.requireEquivalent(current, step.transformedExpression());
                audits++;
                sequence.add(genes.get(step.rule()));
                current = step.transformedExpression();
            }
            // Exclude every observed value, including unsuccessfully explored alternatives.
            for (var state : result.exploredStates()) excluded.add(exact.alphaIdentity(state.expression()));
            for (String state : selected.path()) excluded.add(exact.alphaIdentity(state));
            if (sequence.size() < 2 || sequence.size() > limits.maximumTraceSteps()
                    || new ExpressionScorer().score(input.expression()).weightedTotal() <= selected.score().weightedTotal()) {
                sequence = List.of();
            }
            observations.add(new Observation(input, identities.get(input.id()), result, sequence, replayWork, audits));
        }
        List<List<String>> sequences = observations.stream().map(Observation::geneSequence)
            .filter(s -> !s.isEmpty()).distinct().toList();
        var plan = compileTopology(inventory, sequences, limits.maximumProgramNodes());
        List<List<String>> shuffledSequences = new ArrayList<>();
        Random random = new Random(SHUFFLE_SEED);
        for (List<String> sequence : sequences) {
            var shuffled = new ArrayList<>(sequence);
            java.util.Collections.shuffle(shuffled, random);
            // An unchanged random permutation is not an order ablation.
            // This fixed fallback never consults application results.
            if (shuffled.equals(sequence)) java.util.Collections.rotate(shuffled, 1);
            shuffledSequences.add(List.copyOf(shuffled));
        }
        return new FrozenStrategy(inventory, limits, observations, excluded, plan,
            compileTopology(inventory, shuffledSequences, limits.maximumProgramNodes()));
    }

    public Application apply(FrozenStrategy strategy, String source, Budget budget, Profile profile) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(budget, "budget");
        if (budget.maxPrimitiveSteps() > strategy.limits().maximumTraceSteps()
                || budget.maxExactTheoryWorkUnits() != 0 || budget.maxExploredStates() > 512
                || budget.maxCandidatesPerState() > 128 || budget.maxWorkUnits() > 1_000_000) {
            throw new IllegalArgumentException("application exceeds supported bounded search envelope");
        }
        String identity = exact.alphaIdentity(source);
        if (strategy.excludedIdentities().contains(identity)) {
            throw new IllegalArgumentException("application overlaps an observed TRAIN polynomial up to variable renaming");
        }
        MeasuredTransformationEngine engine = flat(strategy.inventory());
        Optional<EvolutionRewriteProgramPlan> plan = switch (profile) {
            case FLAT_RULES -> Optional.empty();
            case LEARNED_PROGRAM -> strategy.plan();
            case SHUFFLED_PROGRAM -> strategy.shuffledPlan();
        };
        if (plan.isPresent()) {
            engine = MeasuredTransformationEngines.union(engine,
                new EvolutionRewriteProgramCompiler().compile(strategy.inventory(), plan.orElseThrow()).engine());
        }
        Result result = search(source, engine, budget);
        int calls = audit(result.bestState().path().getFirst(), result.bestState().transformations());
        return new Application(strategy.contentHash(), profile, source, identity, result, calls);
    }

    private int audit(String source, List<de.regelsuche.transform.Transformation> steps) {
        int calls = 0;
        String current = source;
        for (var step : steps) {
            if (!step.assumptions().isEmpty()) throw new IllegalArgumentException("unexpected assumption in polynomial trace");
            if (step.provenance() instanceof de.regelsuche.transform.TransformationProvenance.Sequence sequence) {
                if (!sequence.sourceExpression().equals(current)) throw new IllegalArgumentException("unbound program trace");
                calls += audit(current, sequence.steps());
            } else {
                exact.requireEquivalent(current, step.transformedExpression());
                calls++;
            }
            current = step.transformedExpression();
        }
        return calls;
    }

    private void validateRules(EvolutionGenome inventory) {
        for (var gene : inventory.rewrites()) {
            if (!gene.assumptions().isEmpty()) throw new IllegalArgumentException("only unconditional polynomial rules supported");
            var left = EvolutionGenomeCompiler.parsePattern(gene.sourcePattern());
            var right = EvolutionGenomeCompiler.parsePattern(gene.targetPattern());
            rejectFixedSymbols(left);
            rejectFixedSymbols(right);
            exact.requireEquivalent(EvolutionGenome.transformPlaceholders(gene.sourcePattern(), s -> "slot" + s.substring(1)),
                EvolutionGenome.transformPlaceholders(gene.targetPattern(), s -> "slot" + s.substring(1)));
        }
    }

    private static void rejectFixedSymbols(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.LiteralVariable || pattern instanceof PatternExpr.Function) {
            throw new IllegalArgumentException("literal symbols and functions are outside the polynomial strategy inventory");
        }
        if (pattern instanceof PatternExpr.Operation op) {
            rejectFixedSymbols(op.left());
            rejectFixedSymbols(op.right());
        }
    }

    private static MeasuredTransformationEngine flat(EvolutionGenome inventory) {
        return MeasuredTransformationEngines.counting(new AstRewriteTransformationEngine(
            new EvolutionGenomeCompiler().compile(inventory).rules(), inventory.budget().maxAstGrowthPerStep(),
            inventory.budget().maxCandidatesPerState()));
    }

    private static Result search(String source, MeasuredTransformationEngine engine, Budget budget) {
        return new WorkBudgetBestFirstSearchStrategy().search(Problem.withoutTarget(source,
            new SearchExpansionSource.Measured(engine), new ExpressionScorer(), new ExpressionCanonicalizer(), budget));
    }

    private static Optional<EvolutionRewriteProgramPlan> compileTopology(EvolutionGenome inventory,
            List<List<String>> sequences, int maximumNodes) {
        if (sequences.isEmpty()) return Optional.empty();
        int[] ids = {0};
        var root = trie(sequences, ids);
        return Optional.of(EvolutionRewriteProgramPlan.create(inventory, root, maximumNodes, maximumNodes));
    }

    /** Share nonterminal prefixes; preserve terminal alternatives without inventing an identity action. */
    private static EvolutionRewriteProgramPlan.Node trie(List<List<String>> sequences, int[] ids) {
        Map<String, List<List<String>>> groups = new TreeMap<>();
        for (var sequence : sequences) {
            groups.computeIfAbsent(sequence.getFirst(), ignored -> new ArrayList<>())
                .add(List.copyOf(sequence.subList(1, sequence.size())));
        }
        List<EvolutionRewriteProgramPlan.Node> branches = new ArrayList<>();
        for (var group : groups.entrySet()) {
            if (group.getValue().stream().anyMatch(List::isEmpty)) {
                branches.add(new EvolutionRewriteProgramPlan.Source(nodeId(ids), List.of(group.getKey())));
            }
            var suffixes = group.getValue().stream().filter(s -> !s.isEmpty()).distinct().toList();
            if (!suffixes.isEmpty()) {
                var head = new EvolutionRewriteProgramPlan.Source(nodeId(ids), List.of(group.getKey()));
                branches.add(new EvolutionRewriteProgramPlan.Sequence(nodeId(ids), List.of(head, trie(suffixes, ids))));
            }
        }
        return branches.size() == 1 ? branches.getFirst() : new EvolutionRewriteProgramPlan.Choice(nodeId(ids), branches);
    }

    private static String nodeId(int[] ids) { return "trace_node_" + ids[0]++; }

    static void writeLearningProtocol(JsonWriter json, Limits limits) {
        json.property("revision", REVISION).property("identityRevision", ExactPolynomialAnalysis.REVISION)
            .property("shuffleSeed", SHUFFLE_SEED)
            .property("shufflePolicy", "FIXED_SEED_FISHER_YATES;ROTATE_UNCHANGED_SEQUENCE_ONCE")
            .property("maximumInputs", limits.maximumInputs()).property("maximumTraceSteps", limits.maximumTraceSteps())
            .property("maximumProgramNodes", limits.maximumProgramNodes())
            .object("trainingBudget", value -> writeBudget(value, limits.trainingBudget()));
    }

    static void writeBudget(JsonWriter json, Budget budget) {
        json.property("primitiveSteps", budget.maxPrimitiveSteps()).property("states", budget.maxExploredStates())
            .property("candidatesPerState", budget.maxCandidatesPerState()).property("expandingSteps", budget.maxExpandingSteps())
            .property("workUnits", budget.maxWorkUnits());
    }
}
