package de.regelsuche.evolution;

import de.regelsuche.inventory.RuleUtilityEvidence;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledLinearRewriteEngine;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Opt-in live typed execution of admitted traces from the existing target-free TRAIN learner. */
public final class TypedLearnedMoveInventory {
    private final TraceRewriteStrategyLearner.FrozenStrategy formation;
    private final EvolutionGenome inventory;
    private final String inventoryHash;
    private final List<MoveProvider> primitives;
    private final List<AstRewriteTransport> primitiveTransports;
    private final List<TypedProgramMoveProvider> learned;
    private final Map<String, TypedMoveSearch.Verifier> primitiveVerifiers;

    TypedLearnedMoveInventory(TraceRewriteStrategyLearner.FrozenStrategy formation) {
        this(formation.inventory(), formation);
    }

    /** Fixed primitive control: no unused learner, histories or learned programs. */
    public static TypedLearnedMoveInventory primitives(EvolutionGenome inventory) {
        return new TypedLearnedMoveInventory(java.util.Objects.requireNonNull(inventory), null);
    }

    private TypedLearnedMoveInventory(EvolutionGenome inventory, TraceRewriteStrategyLearner.FrozenStrategy formation) {
        this.formation = formation;
        this.inventory = inventory;
        inventoryHash = inventory.contentHash();
        var bounds = inventory.budget();
        if (bounds.maxCandidatesPerState() > 128) {
            throw new IllegalArgumentException("typed compiled inventory supports at most 128 candidates per stage");
        }
        var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
        var engines = new HashMap<String, PreparedAstRewriteTransformationEngine>();
        var providers = new ArrayList<MoveProvider>();
        var transports = new ArrayList<AstRewriteTransport>();
        var verifiers = new HashMap<String, TypedMoveSearch.Verifier>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            var engine = new PreparedAstRewriteTransformationEngine(List.of(rule), bounds.maxAstGrowthPerStep(), bounds.maxCandidatesPerState());
            engines.put(inventory.rewrites().get(i).geneId(), engine);
            var transport = engine.astTransport();
            transports.add(transport);
            providers.add(TypedMoveSearch.primitiveProvider(new MoveProvider.Descriptor(rule.id(), rule.id(),
                SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(),
                SearchMove.ValueEvidence.UNKNOWN, inventory.contentHash()), transport));
            verifiers.put(rule.id(), TypedMoveSearch.primitiveReplay(transport));
        }
        primitives = List.copyOf(providers);
        primitiveTransports = List.copyOf(transports);
        primitiveVerifiers = Map.copyOf(verifiers);
        var programs = new TreeMap<String, TypedProgramMoveProvider>();
        if (formation != null) for (var observation : formation.observations()) {
            if (observation.geneSequence().size() < 2 || observation.minimality().isEmpty()
                    || !observation.minimality().orElseThrow().observedReplayVerified()) continue;
            String id = "typed-learned:" + SchematicProofPlan.hash(String.join("/", observation.geneSequence()));
            programs.computeIfAbsent(id, ignored -> compile(id, observation.geneSequence(), engines,
                new RuleUtilityAssessor().fromReference(observation.minimality().orElseThrow(), -1), bounds.maxCandidatesPerState()));
        }
        learned = List.copyOf(programs.values());
    }

    /** The existing frozen learner and compiled programs, with object-native transport. */
    public List<NativeMoveProvider> nativeProviders() {
        var providers = new ArrayList<>(nativePrimitiveProviders());
        learned.forEach(provider -> providers.add(provider.nativeProvider()));
        return List.copyOf(providers);
    }

    public List<NativeMoveProvider> nativePrimitiveProviders() {
        var providers = new ArrayList<NativeMoveProvider>();
        for (int i = 0; i < primitives.size(); i++)
            providers.add(new NativeMoveSearch.Primitive(primitives.get(i).descriptor(), primitiveTransports.get(i)));
        return List.copyOf(providers);
    }

    public List<MoveProvider> primitiveProviders() { return primitives; }

    /** A task-completion control with the same frontier, stopping criterion and replay as learned solving. */
    public CheckedSchemaTaskSolver newTaskSolver() {
        return new CheckedSchemaTaskSolver(newSearchSession(false, 0, 0), null);
    }

    /**
     * Also use the primitive batch scheduler when no learned pattern can appear under any
     * sequence of this inventory's primitive rewrites. The paid route is limited to this
     * solver's complete, unaugmented primitive inventory.
     */
    public CheckedSchemaTaskSolver newTaskSolverWithSelection(CheckedLearnedSchemaModel model,
            boolean primitiveReachability) {
        var selected = newTaskSolverWithSelection(model);
        if (!primitiveReachability) return selected;
        return selected.withPrimitiveRoute(primitives, PrimitiveSchemaReachability.compile(inventory, model),
            PrimitiveSchemaReachability.REVISION);
    }

    /** Apply one explicitly selected capability profile to the same primitive frontier. */
    public CheckedSchemaTaskSolver newTaskSolverWithProfile(TaskConstructionProfile profile) {
        return java.util.Objects.requireNonNull(profile, "profile").attach(newTaskSolver());
    }

    /** Primitive and learned solving use the same selected native capabilities. */
    public CheckedSchemaTaskSolver newTaskSolverWithProfile(CheckedLearnedSchemaModel model, TaskConstructionProfile profile) {
        return java.util.Objects.requireNonNull(profile, "profile").attach(newTaskSolver(model));
    }

    /**
     * Prepare all independently proved schemata once. Each solve opens fresh lazy cursors and
     * keeps the primitive fallback; this does not change the historical exploration sessions.
     */
    public CheckedSchemaTaskSolver newTaskSolver(CheckedLearnedSchemaModel model) {
        return newTaskSolver(model, false);
    }

    /** Optional existing shape index; preparation and each dispatch remain explicitly paid. */
    public CheckedSchemaTaskSolver newTaskSolver(CheckedLearnedSchemaModel model, boolean indexed) {
        return prepareTaskSolver(model, indexed, false, false);
    }

    /** Use the checked domain size and source pattern shapes to bound each state's candidate sites. */
    public CheckedSchemaTaskSolver newTaskSolverWithSelection(CheckedLearnedSchemaModel model) {
        return prepareTaskSolver(model, false, true, false);
    }

    /** Recover each concrete substitution independently from the source during both proof checks. */
    public CheckedSchemaTaskSolver newTaskSolverWithRegeneratedWitness(CheckedLearnedSchemaModel model) {
        return prepareTaskSolver(model, false, true, true).withPrimitiveRoute(primitives,
            PrimitiveSchemaReachability.compile(inventory, model), PrimitiveSchemaReachability.REVISION + "+regenerated-witness/v1");
    }

    /** Share the selected cursor's checked occurrence counts with a node-count goal for one query. */
    public CheckedSchemaTaskSolver newTaskSolverWithDomainReceipts(CheckedLearnedSchemaModel model) {
        return newTaskSolverWithDomainReceipts(model, false);
    }

    public CheckedSchemaTaskSolver newTaskSolverWithSharedContext(CheckedLearnedSchemaModel model) {
        return newTaskSolverWithDomainReceipts(model, true);
    }

    public CheckedSchemaTaskSolver newTaskSolverWithDomainReceipts(CheckedLearnedSchemaModel model, boolean sharedContext) {
        var included = model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return selectedSolver(model, sharedContext, included);
    }

    CheckedSchemaTaskSolver solverForSchema(CheckedLearnedSchemaModel model, String schemaId) {
        return selectedSolver(model, false, Set.of(schemaId));
    }

    private CheckedSchemaTaskSolver selectedSolver(CheckedLearnedSchemaModel model, boolean sharedContext, Set<String> included) {
        var plan = CheckedSchemaMatcherPlan.prepareSelected(model, model.bounds().maximumSchemas(), Map.of(), included);
        var checkedSession = newSchemaSearchSession(model, List.of(plan.provider()), true, sharedContext);
        var solver = new CheckedSchemaTaskSolver(checkedSession, plan.compilationReceipt(), plan);
        String revision = PrimitiveSchemaReachability.REVISION + "+regenerated-witness/v1+node-goal-receipts/v1";
        return solver.withPrimitiveRoute(primitives, PrimitiveSchemaReachability.compile(inventory, model),
            sharedContext ? revision + "+common-context/v1" : revision, true);
    }

    public CheckedSchemaTaskSolver newTaskSolverWithTacticPolicy(CheckedLearnedSchemaModel model,
            LearnedTacticPolicy policy, boolean sharedContext, boolean ablateTactics) {
        var disabled = ablateTactics ? model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet()) : Set.<String>of();
        return newTaskSolverWithTacticPolicy(model, policy, sharedContext, disabled);
    }

    /** Keep selection and its training costs fixed when disabling individual proved tactics. */
    public CheckedSchemaTaskSolver newTaskSolverWithTacticPolicy(CheckedLearnedSchemaModel model,
            LearnedTacticPolicy policy, boolean sharedContext, Set<String> disabled) {
        policy.requireModel(model);
        var plans = new java.util.TreeMap<String,CheckedSchemaMatcherPlan>();
        long preparation = 0;
        for (var schema : model.schemas()) {
            var plan = CheckedSchemaMatcherPlan.prepareSelected(model, 1, Map.of(), Set.of(schema.id()));
            preparation = Math.addExact(preparation, plan.compilationWork() + 2);
            plans.put(schema.id(), plan);
        }
        if (!plans.keySet().containsAll(disabled)) throw new IllegalArgumentException("unknown disabled schema");
        var dispatch = new LearnedTacticPolicy.Dispatch(policy,Map.copyOf(plans),Set.copyOf(disabled));
        return newTaskSolverWithDomainReceipts(model, sharedContext).withTacticDispatch(dispatch, newTaskSolver(), preparation);
    }

    private CheckedSchemaTaskSolver prepareTaskSolver(CheckedLearnedSchemaModel model, boolean indexed,
            boolean selectSites, boolean deriveBindings) {
        java.util.Objects.requireNonNull(model, "model");
        if (!inventoryHash.equals(model.inventoryHash())) throw new IllegalArgumentException("schema inventory mismatch");
        var included = model.schemas().stream().map(CheckedLearnedSchemaModel.Schema::id)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        var plan = selectSites
            ? CheckedSchemaMatcherPlan.prepareSelected(model, model.bounds().maximumSchemas(), Map.of(), included) : indexed
            ? CheckedSchemaMatcherPlan.prepareIndexed(model, model.bounds().maximumSchemas(), Map.of(), included)
            : CheckedSchemaMatcherPlan.prepare(model, model.bounds().maximumSchemas(), Map.of(), included);
        return new CheckedSchemaTaskSolver(newSchemaSearchSession(model, List.of(plan.provider()), deriveBindings), plan.compilationReceipt(),
            selectSites ? plan : null);
    }

    /** Generalizes actual successful observations, then proves each symbolic statement. */
    public CheckedLearnedSchemaModel checkedSchemas() {
        if (formation == null) throw new IllegalStateException("training observations required");
        return CheckedLearnedSchemaModel.learn(formation);
    }

    /** Direct schema execution shares the primitive inventory and the existing typed frontier. */
    public SearchSession newSchemaSearchSession(CheckedLearnedSchemaModel model, int maximumSchemasPerOccurrence,
            Map<String, Double> utilityBySchemaId) {
        return newSchemaSearchSession(model, model.providers(maximumSchemasPerOccurrence, utilityBySchemaId));
    }

    public SearchSession newSchemaSearchSession(CheckedLearnedSchemaModel model, List<MoveProvider> schemaProviders) {
        return newSchemaSearchSession(model, schemaProviders, false);
    }

    private SearchSession newSchemaSearchSession(CheckedLearnedSchemaModel model, List<MoveProvider> schemaProviders,
            boolean deriveBindings) {
        return newSchemaSearchSession(model,schemaProviders,deriveBindings,false);
    }

    private SearchSession newSchemaSearchSession(CheckedLearnedSchemaModel model, List<MoveProvider> schemaProviders,
            boolean deriveBindings, boolean sharedContext) {
        java.util.Objects.requireNonNull(model, "model");
        if (!inventoryHash.equals(model.inventoryHash())) throw new IllegalArgumentException("schema inventory mismatch");
        var selected = new ArrayList<>(primitives);
        selected.addAll(schemaProviders);
        var schemas = sharedContext ? model.verifierWithSharedContext() : model.verifier(deriveBindings);
        var primitive = verifier();
        return new SearchSession(selected, List.of(), (source, move, context) ->
            move.sourceKind() == SearchMove.SourceKind.PRIMITIVE ? primitive.verify(source, move, context)
                : schemas.verify(source, move, context));
    }
    public List<TypedProgramMoveProvider> learnedPrograms() { return learned; }
    public List<MoveProvider> providers() {
        var providers = new ArrayList<>(primitives);
        providers.addAll(learned);
        return List.copyOf(providers);
    }

    /** Caller-owned provider inventory; proof regeneration remains independent of generation reuse. */
    public record SearchSession(List<MoveProvider> providers, List<TypedPrimitiveCandidateCache> primitiveCaches,
            TypedMoveSearch.Verifier verifier) {
        public SearchSession {
            providers = List.copyOf(providers);
            primitiveCaches = List.copyOf(primitiveCaches);
            java.util.Objects.requireNonNull(verifier, "verifier");
        }
    }

    /**
     * Create fresh bounded caches for one search, without changing the default inventory.
     * Limits are PER primitive provider. Include this construction in measured setup time.
     * Zero limits select the original provider instances without cache bookkeeping.
     */
    public SearchSession newSearchSession(boolean includeLearned, int maximumEntriesPerProvider,
            long maximumCharactersPerProvider) {
        if (maximumEntriesPerProvider < 0 || maximumCharactersPerProvider < 0) {
            throw new IllegalArgumentException("negative session retention bound");
        }
        if (maximumEntriesPerProvider == 0 || maximumCharactersPerProvider == 0) {
            return new SearchSession(includeLearned ? providers() : primitiveProviders(), List.of(), verifier());
        }
        var caches = new ArrayList<TypedPrimitiveCandidateCache>();
        for (int i = 0; i < primitives.size(); i++) {
            // EvolutionGenomeCompiler creates private final rules with immutable
            // genome/pattern fields. No external rule subclass enters this inventory.
            caches.add(TypedPrimitiveCandidateCache.forDeterministicTransport(primitives.get(i).descriptor(),
                primitiveTransports.get(i), maximumEntriesPerProvider, maximumCharactersPerProvider));
        }
        var selected = new ArrayList<MoveProvider>(caches);
        if (includeLearned) selected.addAll(learned);
        return new SearchSession(selected, caches, verifier());
    }

    /** Regeneration uses the registered inventory; a learned identifier alone never authorizes an edge. */
    public TypedMoveSearch.Verifier verifier() {
        var programs = new HashMap<String, TypedMoveSearch.Verifier>();
        learned.forEach(provider -> programs.put(provider.descriptor().id(), provider.verifier()));
        return (source, move, context) -> {
            var verifier = (move.sourceKind() == SearchMove.SourceKind.PRIMITIVE ? primitiveVerifiers : programs).get(move.ruleId());
            return verifier == null ? new MoveVerifier.Verification(false, 1, List.of(), "UNKNOWN_TYPED_PROGRAM")
                : verifier.verify(source, move, context);
        };
    }

    /**
     * Formation cost is separate from subsequent search and policy-selection ledgers.
     * Like LearnedSchedulingModel, this counts mechanical candidate events and
     * primitive mathematical applications as distinct work dimensions (v2), not
     * as a mechanical-only total. Source-candidate counts supply the legacy
     * learner's supplementary primitive-application receipts.
     */
    public long formationWork() {
        if (formation == null) return 0;
        return java.util.stream.LongStream.of(formation.trainingSearchWorkUnits(), formation.trainingPrimitiveWorkUnits(),
            formation.trainingReplayWorkUnits(), formation.trainingReplayPrimitiveWorkUnits(), formation.trainingExactWorkUnits(),
            formation.trainingMinimalityWorkUnits(), formation.trainingReferenceSupplementaryWorkUnits()).reduce(0, Math::addExact);
    }

    private static TypedProgramMoveProvider compile(String id, List<String> genes,
            Map<String, PreparedAstRewriteTransformationEngine> engines, RuleUtilityEvidence utility, int maximumCandidates) {
        var stages = new ArrayList<RewriteProgram>();
        for (int i = 0; i < genes.size(); i++) {
            var engine = engines.get(genes.get(i));
            if (engine == null) throw new IllegalArgumentException("TRAIN trace contains an unregistered gene");
            stages.add(new RewriteProgram.Source(RewriteProgram.NodeMetadata.named("stage-" + i + ":" + genes.get(i)), engine));
        }
        var evidence = new SearchMove.ValueEvidence(utility.confidence(), 0, utility.evidenceCount(),
            utility.bestKnownPrimitiveSteps(), 1, utility.boundedMinimumProved(),
            utility.reference() == null ? "" : utility.reference().assessmentHash());
        var descriptor = new MoveProvider.Descriptor(id, "sequence:" + String.join("/", genes), SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), evidence, id);
        var program = new RewriteProgram.Sequence(RewriteProgram.NodeMetadata.named(id), stages);
        return new TypedProgramMoveProvider(descriptor, new CompiledLinearRewriteEngine(program, maximumCandidates).compileAst());
    }
}
