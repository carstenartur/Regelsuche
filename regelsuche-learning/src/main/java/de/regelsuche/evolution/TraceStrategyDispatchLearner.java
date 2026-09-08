package de.regelsuche.evolution;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.FrozenStrategy;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.CompiledLinearRewriteEngine;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.transform.AstRewriteTransformationEngines;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.PrimitiveBudgetedTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Selects conditional continuations by measured TRAIN utility. A route sees only
 * already produced primitive candidates, never a target or an application label.
 * This is a heuristic one-successor policy, not exhaustive search-space coverage.
 */
public final class TraceStrategyDispatchLearner {
    public static final String REVISION = "regelsuche.trace-strategy-dispatch/v1";
    public enum Profile { FLAT_EXHAUSTIVE, FLAT_GREEDY, LEARNED_DISPATCH, UNGATED_CONTINUATIONS }
    private static final ExpressionScorer SCORER = new ExpressionScorer();
    private static final Comparator<Transformation> ORDER = Comparator
        .comparingLong((Transformation step) -> (long) SCORER.score(step.transformedExpression()).weightedTotal()
            + 2L * step.primitiveStepCount() + (step.kind() == de.regelsuche.transform.RewriteKind.EXPAND ? 5 : 0))
        .thenComparingInt(Transformation::primitiveStepCount).thenComparing(Transformation::rule)
        .thenComparing(Transformation::transformedExpression).thenComparing(Transformation::applicationKey);

    public record Limits(Budget budget, int maximumInputs, int maximumContexts, int maximumTrials) {
        public Limits {
            Objects.requireNonNull(budget, "budget");
            if (maximumInputs < 2 || maximumInputs > 16 || maximumContexts < 1 || maximumContexts > 32
                    || maximumTrials < 1 || maximumTrials > 64 || budget.maxPrimitiveSteps() > 8
                    || budget.maxExactTheoryWorkUnits() != 0 || budget.maxExploredStates() > 512
                    || budget.maxCandidatesPerState() > 128 || budget.maxWorkUnits() > 1_000_000) {
                throw new IllegalArgumentException("invalid dispatch training limits");
            }
        }
    }

    public record Route(long contextMask, List<String> sequence) {
        public Route { sequence = List.copyOf(sequence); }
        String id() { return contextMask + ":" + String.join(",", sequence); }
    }

    public record Observation(Input input, Result search, int exactAuditCalls) {
        public long measuredWork() { return Math.addExact(search.metrics().chargedSearchWorkUnits(), exactAuditCalls); }
        public String toCanonicalJson() {
            return new JsonWriter().beginObject().property("id", input.id()).property("input", input.expression())
                .property("output", search.bestState().expression()).property("score", search.bestState().score().weightedTotal())
                .property("primitiveSteps", search.bestState().primitiveDepth()).property("programUsed", search.bestState().programUsed())
                .property("status", search.status().name()).property("searchWork", search.metrics().chargedSearchWorkUnits())
                .property("exactAuditCalls", exactAuditCalls).property("measuredWork", measuredWork())
                .property("searchHash", SchematicProofPlan.hash(search.toCanonicalJson())).endObject().toString();
        }
    }

    public record Trial(String id, List<Route> routes, List<Observation> observations, boolean accepted) {
        public Trial { routes = List.copyOf(routes); observations = List.copyOf(observations); }
        public long measuredWork() { return observations.stream().mapToLong(Observation::measuredWork).reduce(0, Math::addExact); }
    }

    public static final class FrozenPolicy {
        private final FrozenStrategy formation;
        private final Limits limits;
        private final List<Route> routes;
        private final List<Trial> trials;
        private final List<String> exclusions;
        private final long contextCollectionWork;
        private final String json;

        private FrozenPolicy(FrozenStrategy formation, Limits limits, List<Route> routes, List<Trial> trials,
                Set<String> exclusions, long contextCollectionWork) {
            this.formation = formation;
            this.limits = limits;
            this.routes = List.copyOf(routes);
            this.trials = List.copyOf(trials);
            this.exclusions = exclusions.stream().sorted().toList();
            this.contextCollectionWork = contextCollectionWork;
            this.json = render();
        }
        public FrozenStrategy formation() { return formation; }
        public Limits limits() { return limits; }
        public List<Route> routes() { return routes; }
        public List<Trial> trials() { return trials; }
        public List<String> exclusions() { return exclusions; }
        public long contextCollectionWork() { return contextCollectionWork; }
        public long dispatchLearningWork() {
            return Math.addExact(contextCollectionWork, trials.stream().mapToLong(Trial::measuredWork).reduce(0, Math::addExact));
        }
        public long formationWork() {
            return Math.addExact(Math.addExact(Math.addExact(formation.trainingSearchWorkUnits(), formation.trainingReplayWorkUnits()),
                formation.trainingExactAuditCalls() + formation.inventory().rewrites().size()), formation.trainingMinimalityWorkUnits());
        }
        public long learningWork() { return Math.addExact(formationWork(), dispatchLearningWork()); }
        public String toCanonicalJson() { return json; }
        public String contentHash() { return SchematicProofPlan.hash(json); }

        private String render() {
            return new JsonWriter().beginObject().property("schema", REVISION)
                .property("formationHash", formation.contentHash()).property("authority", "EXPERIMENTAL_NO_PROMOTION")
                .property("policy", "ONE_SUCCESSOR;MEASURED_TRAIN_ROUTE_ADDITION;NONINFERIOR_SCORE_AND_STRICT_WORK_REDUCTION")
                .property("context", "AVAILABLE_PRIMITIVE_GENE_BITSET_FROM_EXISTING_BATCH")
                .property("continuationBackend", CompiledLinearRewriteEngine.REVISION)
                .property("workScope", "SEARCH_MECHANICS_PLUS_EXACT_AUDIT_CALLS;EXCLUDES_COMPLETE_CPU_AND_IDENTITY_WORK")
                .object("limits", value -> writeLimits(value, limits))
                .property("formationWork", formationWork()).property("contextCollectionWork", contextCollectionWork)
                .property("dispatchLearningWork", dispatchLearningWork()).property("learningWork", learningWork())
                .stringArray("exclusions", exclusions).stringArray("routes", routes.stream().map(Route::id).toList())
                .array("trials", values -> trials.forEach(trial -> values.objectValue(value -> value
                    .property("id", trial.id()).property("accepted", trial.accepted()).property("measuredWork", trial.measuredWork())
                    .stringArray("routes", trial.routes().stream().map(Route::id).toList())
                    .stringArray("observations", trial.observations().stream().map(Observation::toCanonicalJson).toList()))))
                .endObject().toString();
        }
    }

    public FrozenPolicy train(FrozenStrategy formation, List<Input> inputs, Limits limits) {
        Objects.requireNonNull(formation, "formation");
        Objects.requireNonNull(limits, "limits");
        if (limits.budget().maxPrimitiveSteps() > formation.limits().maximumTraceSteps()) {
            throw new IllegalArgumentException("dispatch budget exceeds formation envelope");
        }
        var ordered = List.copyOf(inputs).stream().sorted(Comparator.comparing(Input::id)).toList();
        if (ordered.size() < 2 || ordered.size() > limits.maximumInputs()
                || ordered.stream().map(Input::id).distinct().count() != ordered.size()) {
            throw new IllegalArgumentException("distinct bounded dispatch TRAIN inputs required");
        }
        var exact = new ExactPolynomialAnalysis();
        Set<String> exclusions = new TreeSet<>(formation.excludedIdentities());
        for (var input : ordered) {
            if (!exclusions.add(exact.alphaIdentity(input.expression()))) {
                throw new IllegalArgumentException("dispatch TRAIN overlaps formation or another TRAIN polynomial");
            }
        }
        var executable = new Executable(formation);
        var baseline = observe(executable, ordered, limits.budget(), List.of(), Profile.FLAT_GREEDY);
        var contexts = collectContexts(executable, baseline, limits.maximumContexts());
        var selection = selectRoutes(executable, ordered, limits, baseline, contexts.masks());
        for (var trial : selection.trials()) for (var observation : trial.observations()) {
            for (var state : observation.search().exploredStates()) exclusions.add(exact.alphaIdentity(state.expression()));
        }
        return new FrozenPolicy(formation, limits, selection.routes(), selection.trials(), exclusions, contexts.work());
    }

    private record ContextCollection(List<Long> masks, long work) {}
    private record RouteSelection(List<Route> routes, List<Trial> trials) {}

    private static ContextCollection collectContexts(Executable executable, List<Observation> baseline, int maximumContexts) {
        var contexts = new TreeSet<Long>();
        long collectionWork = 0;
        for (var observation : baseline) {
            for (var state : observation.search().exploredStates()) {
                var batch = executable.flat.transformMeasured(state.expression());
                collectionWork = Math.addExact(collectionWork, batch.workMetrics().totalWorkUnits());
                collectionWork = Math.addExact(collectionWork, batch.transformations().size() + 1L);
                long context = executable.context(batch.transformations());
                if (context != 0) contexts.add(context);
            }
        }
        if (contexts.size() > maximumContexts) throw new IllegalArgumentException("dispatch context limit exceeded");
        return new ContextCollection(contexts.stream().toList(), collectionWork);
    }

    private static RouteSelection selectRoutes(Executable executable, List<Input> inputs, Limits limits,
            List<Observation> baseline, List<Long> contexts) {
        List<Trial> trials = new ArrayList<>();
        trials.add(new Trial("baseline", List.of(), baseline, false));
        List<Route> selected = new ArrayList<>();
        long bestWork = totalWork(baseline);
        for (long context : contexts) {
            for (var continuation : executable.continuations) {
                if ((context & executable.bit(continuation.firstRule())) == 0) continue;
                if (trials.size() >= limits.maximumTrials()) throw new IllegalArgumentException("dispatch trial limit exceeded");
                var candidate = new ArrayList<>(selected.stream().filter(route -> route.contextMask() != context).toList());
                candidate.add(new Route(context, continuation.sequence()));
                candidate.sort(Comparator.comparingLong(Route::contextMask));
                var observations = observe(executable, inputs, limits.budget(), candidate, Profile.LEARNED_DISPATCH);
                long work = totalWork(observations);
                boolean accepted = scoresNoWorse(observations, baseline) && work < bestWork;
                trials.add(new Trial("route-" + trials.size(), candidate, observations, accepted));
                if (accepted) { selected = candidate; bestWork = work; }
            }
        }
        return new RouteSelection(List.copyOf(selected), List.copyOf(trials));
    }

    private static boolean scoresNoWorse(List<Observation> observations, List<Observation> baseline) {
        for (int i = 0; i < baseline.size(); i++) {
            if (observations.get(i).search().bestState().score().weightedTotal()
                    > baseline.get(i).search().bestState().score().weightedTotal()) return false;
        }
        return true;
    }

    public Observation apply(FrozenPolicy policy, Input input, Profile profile) {
        return prepare(policy, profile).apply(input);
    }

    /** Compile once for a stream of applications; no application feedback updates the policy. */
    public PreparedPolicy prepare(FrozenPolicy policy, Profile profile) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(profile, "profile");
        return new PreparedPolicy(policy, new Executable(policy.formation()).engine(policy.routes(), profile));
    }

    public static final class PreparedPolicy {
        private final FrozenPolicy policy;
        private final MeasuredTransformationEngine engine;
        private PreparedPolicy(FrozenPolicy policy, MeasuredTransformationEngine engine) {
            this.policy = policy;
            this.engine = engine;
        }
        public Observation apply(Input input) {
            if (policy.exclusions().contains(new ExactPolynomialAnalysis().alphaIdentity(input.expression()))) {
                throw new IllegalArgumentException("application overlaps an observed TRAIN polynomial");
            }
            return observe(engine, input, policy.limits().budget());
        }
    }

    static MeasuredTransformationEngine engine(FrozenPolicy policy, Profile profile) {
        return new Executable(policy.formation()).engine(policy.routes(), profile);
    }

    private static List<Observation> observe(Executable executable, List<Input> inputs, Budget budget,
            List<Route> routes, Profile profile) {
        var engine = executable.engine(routes, profile);
        return inputs.stream().map(input -> observe(engine, input, budget)).toList();
    }

    private static Observation observe(MeasuredTransformationEngine engine, Input input, Budget budget) {
        var auditor = new TraceRewriteStrategyLearner();
        Result result = new WorkBudgetBestFirstSearchStrategy().search(Problem.withoutTarget(input.expression(),
            new SearchExpansionSource.Measured(engine), SCORER, new ExpressionCanonicalizer(), budget));
        int audits = auditor.audit(result.bestState().path().getFirst(), result.bestState().transformations());
        return new Observation(input, result, audits);
    }

    private static long totalWork(List<Observation> observations) {
        return observations.stream().mapToLong(Observation::measuredWork).reduce(0, Math::addExact);
    }

    private record Continuation(List<String> sequence, String firstRule, MeasuredTransformationEngine tail) {}

    private static final class Executable {
        private final MeasuredTransformationEngine flat;
        private final Map<String, Long> bits = new TreeMap<>();
        private final List<Continuation> continuations = new ArrayList<>();

        private Executable(FrozenStrategy strategy) {
            var inventory = strategy.inventory();
            var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
            flat = MeasuredTransformationEngines.counting(AstRewriteTransformationEngines.production(rules,
                inventory.budget().maxAstGrowthPerStep(), inventory.budget().maxCandidatesPerState()));
            Map<String, String> ids = new TreeMap<>();
            for (int i = 0; i < rules.size(); i++) {
                bits.put(rules.get(i).id(), 1L << i);
                ids.put(inventory.rewrites().get(i).geneId(), rules.get(i).id());
            }
            var sequences = strategy.observations().stream().map(TraceRewriteStrategyLearner.Observation::geneSequence)
                .filter(sequence -> sequence.size() >= 2).distinct().sorted(Comparator.comparing(Object::toString)).toList();
            for (var sequence : sequences) {
                List<EvolutionRewriteProgramPlan.Node> nodes = new ArrayList<>();
                for (int i = 1; i < sequence.size(); i++) nodes.add(new EvolutionRewriteProgramPlan.Source("resume-" + i, List.of(sequence.get(i))));
                var root = nodes.size() == 1 ? nodes.getFirst() : new EvolutionRewriteProgramPlan.Sequence("resume", nodes);
                var plan = EvolutionRewriteProgramPlan.create(inventory, root,
                    strategy.limits().maximumProgramNodes(), strategy.limits().maximumProgramNodes());
                continuations.add(new Continuation(sequence, ids.get(sequence.getFirst()),
                    new CompiledLinearRewriteEngine(new EvolutionRewriteProgramCompiler().compile(inventory, plan).program(),
                        inventory.budget().maxCandidatesPerState())));
            }
        }
        private long bit(String rule) { return bits.getOrDefault(rule, 0L); }
        private long context(List<Transformation> candidates) {
            long mask = 0;
            for (var candidate : candidates) mask |= bit(candidate.rule());
            return mask;
        }

        private MeasuredTransformationEngine engine(List<Route> routes, Profile profile) {
            if (profile == Profile.FLAT_EXHAUSTIVE) return flat;
            Map<Long, List<String>> lookup = new TreeMap<>();
            routes.forEach(route -> lookup.put(route.contextMask(), route.sequence()));
            return (PrimitiveBudgetedTransformationEngine) (expression, remaining) -> {
                var batch = flat.transformMeasured(expression);
                var primitives = batch.transformations();
                if (primitives.isEmpty()) return batch;
                var work = batch.workMetrics();
                List<Transformation> resumed = new ArrayList<>();
                List<Continuation> enabled = List.of();
                if (profile == Profile.UNGATED_CONTINUATIONS) enabled = continuations;
                else if (profile == Profile.LEARNED_DISPATCH && !lookup.isEmpty()) {
                    var sequence = lookup.get(context(primitives));
                    work = work.plus(events(primitives.size() + 1L, 0, 0, 0));
                    if (sequence != null) enabled = continuations.stream().filter(value -> value.sequence().equals(sequence)).toList();
                }
                for (var continuation : enabled) {
                    work = work.plus(events(1, 0, 0, 0));
                    if (continuation.sequence().size() > remaining) continue;
                    for (var primitive : primitives) {
                        work = work.plus(events(1, 0, 0, 0));
                        if (!primitive.rule().equals(continuation.firstRule())) continue;
                        var suffixes = continuation.tail().transformMeasured(primitive.transformedExpression());
                        work = work.plus(suffixes.workMetrics());
                        for (var suffix : suffixes.transformations()) {
                            var path = new RewriteCandidate("learned-resumption", expression, suffix.transformedExpression(), List.of(primitive, suffix));
                            resumed.add(path.toTransformation());
                            work = work.plus(events(0, 0, 0, 1));
                        }
                    }
                }
                // A learned route commits to its resumed paths. Primitive siblings
                // remain the fallback when no complete, budget-fitting continuation exists.
                var candidates = resumed.isEmpty() ? new ArrayList<>(primitives) : resumed;
                candidates.sort(ORDER);
                work = work.plus(events(0, candidates.size(), primitives.size() + resumed.size() - 1L, 0));
                return new TransformationBatch(List.of(candidates.getFirst()), work);
            };
        }
    }

    private static TransformationWorkMetrics events(long checks, long ordered, long pruned, long composed) {
        return new TransformationWorkMetrics(0, 0, 0, 0, composed, checks, 0, ordered, pruned, 0, 0, 0, 0, 0);
    }

    static void writeLimits(JsonWriter value, Limits limits) {
        value.property("maximumInputs", limits.maximumInputs()).property("maximumContexts", limits.maximumContexts())
            .property("maximumTrials", limits.maximumTrials()).object("budget", budget -> TraceRewriteStrategyLearner.writeBudget(budget, limits.budget()));
    }
}
