package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationLimits;
import de.regelsuche.evolution.DeterministicGenomeMutator.MutationStatus;
import de.regelsuche.evolution.EvolutionGenome.AssumptionTemplate;
import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.RewriteGene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicGenomeMutatorTest {
    private final DeterministicGenomeMutator mutator =
        new DeterministicGenomeMutator();

    @Test
    void pinnedSeedProducesByteIdenticalLineageAndUniqueChildren() {
        EvolutionGenome parent = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        MutationCatalog catalog = new MutationCatalog(
            List.of(new AssumptionTemplate(
                Assumption.Kind.NON_ZERO,
                "?A != 0",
                List.of("?A"))),
            List.of(new FeatureWeight(FitnessSignal.RUNTIME_COST, -200)),
            List.of(2, 3));
        MutationLimits limits = new MutationLimits(64, 12);

        var first = mutator.mutate(parent, catalog, 20260717L, limits);
        var second = mutator.mutate(parent, catalog, 20260717L, limits);

        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(first.acceptedGenomeHashes(), second.acceptedGenomeHashes());
        assertFalse(first.acceptedChildren().isEmpty());
        assertEquals(
            first.acceptedChildren().size(),
            new HashSet<>(first.acceptedChildren().stream()
                .map(EvolutionGenome::alphaStructuralHash)
                .toList()).size());
        assertTrue(first.acceptedChildren().stream().noneMatch(child ->
            child.alphaStructuralHash().equals(parent.alphaStructuralHash())));
        assertTrue(first.acceptedChildren().stream().allMatch(child ->
            child.seedGenomeHashes().contains(parent.contentHash())));
    }

    @Test
    void assumptionRemovalIsEnumeratedButRejectedAsGuardWeakening() {
        AssumptionTemplate guard = new AssumptionTemplate(
            Assumption.Kind.NON_ZERO,
            "?A != 0",
            List.of("?A"));
        RewriteGene guarded = EvolutionGenomeTestFixtures.addZero("guarded_rule", "A")
            .withAssumptions(List.of(guard));
        EvolutionGenome parent = EvolutionGenomeTestFixtures.genome(guarded);

        var batch = mutator.mutate(
            parent,
            MutationCatalog.empty(),
            7L,
            new MutationLimits(64, 16));

        var removal = batch.attempts().stream()
            .filter(item -> item.kind() == EvolutionMutationKind.REMOVE_ASSUMPTION)
            .findFirst()
            .orElseThrow();
        assertEquals(MutationStatus.REJECTED, removal.status());
        assertTrue(removal.blockers().stream().anyMatch(value ->
            value.contains("GUARD_WEAKENING_REQUIRES_CERTIFICATE")));
    }

    @Test
    void writesRetainedMutationLineageEvidence() throws Exception {
        EvolutionGenome parent = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        var batch = mutator.mutate(
            parent,
            MutationCatalog.empty(),
            11L,
            new MutationLimits(32, 8));
        Path output = Path.of(
            "build", "reports", "evolution", "mutation-batch.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, batch.toCanonicalJson());

        assertEquals(batch.toCanonicalJson(), Files.readString(output));
        assertTrue(batch.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.evolution-mutation-batch/v1\""));
    }
}
