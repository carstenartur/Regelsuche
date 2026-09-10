package de.regelsuche.evolution;

import de.regelsuche.inventory.*;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** TRAIN-only construction. The returned mathematical knowledge and scheduling tables are frozen together. */
public final class LearnedSchedulingModel {
    public record Trace(String id, List<String> genes, RuleUtilityEvidence utility) { public Trace { genes = List.copyOf(genes); } }
    public record TrainingRun(String id, MoveSearch.Result result) {}
    private final TraceRewriteStrategyLearner.FrozenStrategy knowledge;
    private final List<MoveProvider> primitives, learned, expert;
    private final List<Trace> traces;
    private final RuleActivityMemory.Snapshot activity;
    private final RuleHistoryMemory.Snapshot history;
    private final StateValue landmarks;
    private final List<TrainingRun> training;
    private final Map<String, Long> feedbackWork;

    private LearnedSchedulingModel(TraceRewriteStrategyLearner.FrozenStrategy knowledge, List<MoveProvider> primitives,
            List<MoveProvider> learned, List<MoveProvider> expert, List<Trace> traces, RuleActivityMemory.Snapshot activity,
            RuleHistoryMemory.Snapshot history, StateValue landmarks, List<TrainingRun> training, Map<String, Long> feedbackWork) {
        this.knowledge = knowledge; this.primitives = List.copyOf(primitives); this.learned = List.copyOf(learned); this.expert = List.copyOf(expert);
        this.traces = List.copyOf(traces); this.activity = activity; this.history = history; this.landmarks = landmarks; this.training = List.copyOf(training);
        this.feedbackWork = Map.copyOf(feedbackWork);
    }
    public static LearnedSchedulingModel train() {
        var knowledge = new TraceRewriteStrategyLearner().learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
        var rules = new EvolutionGenomeCompiler().compile(knowledge.inventory()).rules();
        var primitives = PrimitiveMoveProviders.complete(rules, knowledge.inventory().contentHash());
        var traces = new TreeMap<String, Trace>();
        for (var observation : knowledge.observations()) {
            if (observation.geneSequence().size() < 2 || observation.minimality().isEmpty()) continue;
            String id = "learned:" + SchematicProofPlan.hash(String.join("/", observation.geneSequence())).substring(7);
            traces.putIfAbsent(id, new Trace(id, observation.geneSequence(), new RuleUtilityAssessor().fromReference(observation.minimality().orElseThrow(), -1)));
        }
        var learned = traces.values().stream().map(trace -> sequence(trace.id(), trace.genes(), trace.utility(), primitives, SearchMove.SourceKind.LEARNED)).toList();
        var expert = List.of(sequence("expert:cancel", List.of("difference-product", "square-product", "cancel-addend"), RuleUtilityEvidence.UNKNOWN, primitives, SearchMove.SourceKind.EXPERT),
            sequence("expert:factor-left", List.of("factor-left"), RuleUtilityEvidence.UNKNOWN, primitives, SearchMove.SourceKind.EXPERT),
            sequence("expert:factor-right", List.of("factor-right"), RuleUtilityEvidence.UNKNOWN, primitives, SearchMove.SourceKind.EXPERT));
        var landmarks = landmarks(rules, primitives);
        var activity = new RuleActivityMemory(learned.stream().map(provider -> provider.descriptor().id()).toList());
        var history = new RuleHistoryMemory(); var training = new ArrayList<TrainingRun>();
        var all = new ArrayList<>(primitives); all.addAll(learned);
        var verifier = new PrimitiveReplayMoveVerifier(knowledge.inventory());
        var baselines = new TreeMap<String, MoveSearch.Result>();
        for (var observation : knowledge.observations()) {
            var base = trainRun(observation, primitives, MovePriorityPolicy.INVENTORY_ORDER, verifier, landmarks);
            baselines.put(observation.input().id(), base); training.add(new TrainingRun("base-" + observation.input().id(), base));
        }
        for (int epoch = 0; epoch < 5; epoch++) for (var observation : knowledge.observations()) {
            MovePriorityPolicy policy = new HistoryMovePolicy(history.freeze(), HistoryMovePolicy.Weights.DEFAULT);
            if (epoch > 0) policy = new ActivityMovePolicy(activity.freeze(), policy);
            var run = trainRun(observation, all, policy, verifier, landmarks);
            var saving = pairedSaving(baselines.get(observation.input().id()), run);
            activity.observe(run, MoveContext.Phase.TRAIN, saving); history.observe(run, MoveContext.Phase.TRAIN, saving);
            training.add(new TrainingRun("epoch-" + epoch + "-" + observation.input().id(), run));
        }
        var observed = training.stream().filter(run -> run.id().startsWith("epoch-")).map(TrainingRun::result).toList();
        long[] utilityWork = {0};
        var frozenTraces = traces.values().stream().map(trace -> new Trace(trace.id(), trace.genes(),
            RuleUtilityFeedback.update(trace.utility(), trace.id(), observed, MoveContext.Phase.TRAIN,
                units -> utilityWork[0] = Math.addExact(utilityWork[0], units)))).toList();
        var frozenProviders = frozenTraces.stream().map(trace -> sequence(trace.id(), trace.genes(), trace.utility(), primitives, SearchMove.SourceKind.LEARNED)).toList();
        return new LearnedSchedulingModel(knowledge, primitives, frozenProviders, expert, frozenTraces, activity.freeze(), history.freeze(), landmarks, training,
            Map.of("activityTraining", activity.measuredWork(), "historyTraining", history.measuredWork(), "utilityTraining", utilityWork[0]));
    }
    private static MoveSearch.Result trainRun(TraceRewriteStrategyLearner.Observation observation, List<MoveProvider> providers,
            MovePriorityPolicy policy, MoveVerifier verifier, StateValue landmarks) {
        return new MoveSearch().search(new MoveSearch.Problem(format(observation.input().expression()),
            new MoveContext(observation.search().bestState().expression(), List.of(), MoveContext.Phase.TRAIN), providers, policy, verifier,
            state -> new ExpressionScorer().score(state.expression()).weightedTotal(), MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(9, 9, 0, 256, 30000, 24), landmarks));
    }
    private static Map<String, Long> pairedSaving(MoveSearch.Result base, MoveSearch.Result learned) {
        var ids = learned.witness().stream().map(MoveSearch.WitnessStep::move).filter(move -> move.sourceKind() == SearchMove.SourceKind.LEARNED)
            .map(SearchMove::ruleId).distinct().toList();
        if (!base.reached() || !learned.reached() || ids.size() != 1) return Map.of();
        return Map.of(ids.getFirst(), Math.max(0, base.metrics().totalWork() - learned.metrics().totalWork()));
    }
    private static MoveProvider sequence(String id, List<String> genes, RuleUtilityEvidence utility, List<MoveProvider> primitives, SearchMove.SourceKind kind) {
        var sources = genes.stream().map(gene -> primitives.stream().filter(provider -> provider.descriptor().id().endsWith("_" + gene)).findFirst().orElseThrow()).toList();
        var evidence = new SearchMove.ValueEvidence(utility.confidence(), 0, utility.evidenceCount(), utility.bestKnownPrimitiveSteps(), 1,
            utility.boundedMinimumProved(), utility.reference() == null ? "" : utility.reference().assessmentHash());
        return new PrimitiveSequenceMoveProvider(new MoveProvider.Descriptor(id, "sequence:" + String.join("/", genes), kind,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), evidence, id), sources);
    }
    private static StateValue landmarks(List<RewriteRule> rules, List<MoveProvider> providers) {
        return new PrimitiveCapabilityLandmarks(rules.stream()
            .filter(rule -> rule.id().endsWith("_factor-left") || rule.id().endsWith("_factor-right") || rule.id().endsWith("_cancel-addend"))
            .map(rule -> new PrimitiveCapabilityLandmarks.Registration("applicable:" + rule.id(), rule.id(), rule, 6)).toList(), providers);
    }
    public List<MoveProvider> providers(LearnedSchedulingProtocol.Configuration configuration) {
        var all = new ArrayList<>(primitives);
        if (configuration.profile() == LearnedSearchProfile.EXPERT) all.addAll(expert);
        else if (configuration.profile() != LearnedSearchProfile.BASE) all.addAll(configuration.utility() ? learned : learned.stream().map(LearnedSchedulingModel::withoutUtility).toList());
        return List.copyOf(all);
    }
    private static MoveProvider withoutUtility(MoveProvider provider) {
        var d = provider.descriptor();
        var descriptor = new MoveProvider.Descriptor(d.id(), d.ruleFamily(), d.sourceKind(), d.proofStrength(), d.requiredAssumptions(), SearchMove.ValueEvidence.UNKNOWN, d.provenanceId());
        return new MoveProvider() {
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = provider.candidates(state, context);
                return new Batch(batch.moves().stream().map(move -> SearchMove.from(move.transformation(), descriptor, move.generationCost())).toList(), batch.work(), batch.complete());
            }
        };
    }
    public MovePriorityPolicy policy(LearnedSchedulingProtocol.Configuration configuration) {
        if (configuration.profile() == LearnedSearchProfile.EXPERT) return new MovePriorityPolicy() {
            @Override public double score(SearchMove move, MoveState state, MoveContext context) { return move.sourceKind() == SearchMove.SourceKind.EXPERT ? 100 : 0; }
            @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
                return provider.sourceKind() == SearchMove.SourceKind.EXPERT ? Stage.VALUABLE_LEARNED : Stage.NORMAL_PRIMITIVE;
            }
        };
        if (configuration.profile() != LearnedSearchProfile.LEARNED_RANKED) return MovePriorityPolicy.INVENTORY_ORDER;
        var tables = new RuleHistoryMemory.Snapshot(history.schema(), configuration.history() ? history.families() : Map.of(),
            configuration.history() && configuration.continuation() ? history.continuations() : Map.of());
        MovePriorityPolicy policy = new HistoryMovePolicy(tables, HistoryMovePolicy.Weights.DEFAULT);
        return configuration.activity() ? new ActivityMovePolicy(activity, policy) : policy;
    }
    public StateValue stateValue(LearnedSchedulingProtocol.Configuration configuration) { return configuration.landmarks() ? landmarks : StateValue.NONE; }
    public TraceRewriteStrategyLearner.FrozenStrategy knowledge() { return knowledge; }
    public List<Trace> traces() { return traces; }
    public RuleActivityMemory.Snapshot activity() { return activity; }
    public RuleHistoryMemory.Snapshot history() { return history; }
    public List<TrainingRun> training() { return training; }
    public Map<String, Long> trainingWorkComponents() {
        var components = new TreeMap<>(Map.of("discoverySearch", knowledge.trainingSearchWorkUnits(), "discoveryPrimitiveApplications", knowledge.trainingPrimitiveWorkUnits(),
            "discoveryReplay", knowledge.trainingReplayWorkUnits(), "discoveryReplayPrimitiveApplications", knowledge.trainingReplayPrimitiveWorkUnits(),
            "discoveryExactAlgebraAndAlphaIdentity", knowledge.trainingExactWorkUnits(), "boundedReferenceMechanical", knowledge.trainingMinimalityWorkUnits(),
            "boundedReferencePrimitiveInspectionAndFrontier", knowledge.trainingReferenceSupplementaryWorkUnits(),
            "schedulingTraining", training.stream().mapToLong(run -> run.result().metrics().totalWork()).sum()));
        components.putAll(feedbackWork);
        return java.util.Collections.unmodifiableMap(components);
    }
    public long trainingWorkUnits() {
        return trainingWorkComponents().values().stream().mapToLong(Long::longValue).reduce(0L, Math::addExact);
    }
    public static String format(String source) { return ExpressionFormatter.format(new ExpressionParser().parseTerm(source)); }
}
