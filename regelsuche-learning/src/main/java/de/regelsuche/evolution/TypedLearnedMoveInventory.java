package de.regelsuche.evolution;

import de.regelsuche.inventory.RuleUtilityEvidence;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledLinearRewriteEngine;
import de.regelsuche.search.program.RewriteProgram;
import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Opt-in live typed execution of admitted traces from the existing target-free TRAIN learner. */
public final class TypedLearnedMoveInventory {
    private final TraceRewriteStrategyLearner.FrozenStrategy formation;
    private final List<MoveProvider> primitives;
    private final List<TypedProgramMoveProvider> learned;
    private final Map<String, TypedMoveSearch.Verifier> primitiveVerifiers;

    TypedLearnedMoveInventory(TraceRewriteStrategyLearner.FrozenStrategy formation) {
        this(formation.inventory(), formation);
    }

    /** Fixed primitive control: no unused learner, histories or learned programs. */
    static TypedLearnedMoveInventory primitives(EvolutionGenome inventory) {
        return new TypedLearnedMoveInventory(java.util.Objects.requireNonNull(inventory), null);
    }

    private TypedLearnedMoveInventory(EvolutionGenome inventory, TraceRewriteStrategyLearner.FrozenStrategy formation) {
        this.formation = formation;
        var bounds = inventory.budget();
        if (bounds.maxCandidatesPerState() > 128) {
            throw new IllegalArgumentException("typed compiled inventory supports at most 128 candidates per stage");
        }
        var rules = new EvolutionGenomeCompiler().compile(inventory).rules();
        var engines = new HashMap<String, PreparedAstRewriteTransformationEngine>();
        var providers = new ArrayList<MoveProvider>();
        var verifiers = new HashMap<String, TypedMoveSearch.Verifier>();
        for (int i = 0; i < rules.size(); i++) {
            var rule = rules.get(i);
            var engine = new PreparedAstRewriteTransformationEngine(List.of(rule), bounds.maxAstGrowthPerStep(), bounds.maxCandidatesPerState());
            engines.put(inventory.rewrites().get(i).geneId(), engine);
            var transport = engine.astTransport();
            providers.add(TypedMoveSearch.primitiveProvider(new MoveProvider.Descriptor(rule.id(), rule.id(),
                SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE, List.of(),
                SearchMove.ValueEvidence.UNKNOWN, inventory.contentHash()), transport));
            verifiers.put(rule.id(), TypedMoveSearch.primitiveReplay(transport));
        }
        primitives = List.copyOf(providers);
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

    public List<MoveProvider> primitiveProviders() { return primitives; }
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

    /** Initial uncached integration control; positive limits specify requested per-provider retention. */
    public SearchSession newSearchSession(boolean includeLearned, int maximumEntriesPerProvider,
            long maximumCharactersPerProvider) {
        if (maximumEntriesPerProvider < 0 || maximumCharactersPerProvider < 0) {
            throw new IllegalArgumentException("negative session retention bound");
        }
        return new SearchSession(includeLearned ? providers() : primitiveProviders(), List.of(), verifier());
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
