package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Protocol-specific mutator adapter for mutation-kind-stratified offspring
 * scheduling.
 *
 * <p>The legacy {@link DeterministicRewriteProgramMutator} remains the exact
 * implementation used by {@code ROTATED_PREFIX_V1}. This subtype gives the v2
 * protocol a distinct implementation identity while reusing the same proposal
 * generation and preflight logic.</p>
 */
public final class StratifiedMutationKindRewriteProgramMutator
        extends DeterministicRewriteProgramMutator {
    private final Set<EvolutionRewriteProgramMutationKind> permittedKinds;
    private final EvolutionRewriteProgramStudyPlan diagnosticsStudyPlan;
    private final List<ObservedBatch> observedBatches = new ArrayList<>();

    public StratifiedMutationKindRewriteProgramMutator(
        Set<EvolutionRewriteProgramMutationKind> permittedKinds
    ) {
        this(permittedKinds, null);
    }

    public StratifiedMutationKindRewriteProgramMutator(
        EvolutionRewriteProgramStudyPlan studyPlan
    ) {
        this(
            Objects.requireNonNull(studyPlan, "studyPlan").mutationOperators()
                .stream().collect(java.util.stream.Collectors.toSet()),
            studyPlan);
    }

    private StratifiedMutationKindRewriteProgramMutator(
        Set<EvolutionRewriteProgramMutationKind> permittedKinds,
        EvolutionRewriteProgramStudyPlan diagnosticsStudyPlan
    ) {
        Objects.requireNonNull(permittedKinds, "permittedKinds");
        if (permittedKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "permittedKinds must not contain null");
        }
        this.permittedKinds = Set.copyOf(permittedKinds);
        this.diagnosticsStudyPlan = diagnosticsStudyPlan;
    }

    @Override
    public MutationBatch mutate(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        long seed,
        MutationLimits limits
    ) {
        MutationBatch batch = mutateStratifiedByMutationKind(
            genome,
            parent,
            catalog,
            seed,
            limits,
            permittedKinds);
        if (diagnosticsStudyPlan != null) {
            String parentCandidateHash = EvolutionRewriteProgramCandidate.create(
                genome, parent).contentHash();
            observedBatches.add(new ObservedBatch(
                resolveGeneration(parentCandidateHash, seed),
                parentCandidateHash,
                batch));
        }
        return batch;
    }

    public Set<EvolutionRewriteProgramMutationKind> permittedKinds() {
        return permittedKinds;
    }

    public List<ObservedBatch> observedBatches() {
        return List.copyOf(observedBatches);
    }

    private int resolveGeneration(String parentCandidateHash, long observedSeed) {
        int resolved = 0;
        for (int generation = 1;
                generation <= diagnosticsStudyPlan.populationPolicy()
                    .generationCount();
                generation++) {
            if (mutationSeed(
                    diagnosticsStudyPlan.contentHash(),
                    generation,
                    parentCandidateHash) == observedSeed) {
                if (resolved != 0) {
                    throw new IllegalStateException(
                        "mutation seed maps to multiple generations");
                }
                resolved = generation;
            }
        }
        if (resolved == 0) {
            throw new IllegalStateException(
                "mutation seed does not match the bound study plan");
        }
        return resolved;
    }

    private static long mutationSeed(
        String studyHash,
        int generation,
        String parentHash
    ) {
        String digest = EvolutionGenome.hash(
            studyHash + "\n" + generation + "\n" + parentHash);
        return Long.parseUnsignedLong(
            digest.substring("sha256:".length(), "sha256:".length() + 16),
            16);
    }

    public record ObservedBatch(
        int generation,
        String parentCandidateHash,
        MutationBatch batch
    ) {
        public ObservedBatch {
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be positive");
            }
            EvolutionGenome.requireSha256(
                parentCandidateHash, "parentCandidateHash");
            Objects.requireNonNull(batch, "batch");
        }
    }
}
