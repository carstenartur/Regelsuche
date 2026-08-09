package de.regelsuche.evolution;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationBatch;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationLimits;
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

    public StratifiedMutationKindRewriteProgramMutator(
        Set<EvolutionRewriteProgramMutationKind> permittedKinds
    ) {
        Objects.requireNonNull(permittedKinds, "permittedKinds");
        if (permittedKinds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "permittedKinds must not contain null");
        }
        this.permittedKinds = Set.copyOf(permittedKinds);
    }

    @Override
    public MutationBatch mutate(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan parent,
        MutationCatalog catalog,
        long seed,
        MutationLimits limits
    ) {
        return mutateStratifiedByMutationKind(
            genome,
            parent,
            catalog,
            seed,
            limits,
            permittedKinds);
    }

    public Set<EvolutionRewriteProgramMutationKind> permittedKinds() {
        return permittedKinds;
    }
}
