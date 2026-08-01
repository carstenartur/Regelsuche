package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationCatalog;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationLimits;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.MutationStatus;
import de.regelsuche.evolution.DeterministicRewriteProgramMutator.RepeatBounds;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicRewriteProgramMutatorTest {
    @Test
    void deterministicallyRetainsAcceptedAndRejectedTopologyMutations() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan parent = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("initial_zero_source", List.of("add_zero")),
            8,
            8);
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2)),
            List.of(Requirement.maxPrimitiveSteps(2)),
            List.of(Priority.estimatedCostThenRule()),
            List.of(4, 81),
            List.of("mul_one"));
        MutationLimits limits = new MutationLimits(100, 100);
        DeterministicRewriteProgramMutator mutator =
            new DeterministicRewriteProgramMutator();

        var first = mutator.mutate(genome, parent, catalog, 20260801L, limits);
        var second = mutator.mutate(genome, parent, catalog, 20260801L, limits);

        assertEquals(first, second);
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.acceptedCount() > 0);
        assertTrue(first.rejectedCount() > 0);
        assertTrue(first.attempts().stream().anyMatch(attempt ->
            attempt.kind() == EvolutionRewriteProgramMutationKind.WRAP_PRUNE
                && attempt.status() == MutationStatus.REJECTED
                && attempt.blockers().stream().anyMatch(blocker ->
                    blocker.contains("maxCandidatesPerState"))));
        assertEquals(
            first.acceptedPlans().size(),
            new HashSet<>(first.acceptedPlans().stream()
                .map(EvolutionRewriteProgramPlan::alphaStructuralHash)
                .toList()).size());

        EvolutionRewriteProgramPlan composed = first.acceptedPlans().stream()
            .filter(plan -> plan.referencedGeneIds().containsAll(
                List.of("add_zero", "mul_one")))
            .filter(plan -> plan.toReadableProgram().contains("sequence"))
            .findFirst()
            .orElseThrow();
        var compiled = new EvolutionRewriteProgramCompiler().compile(
            genome, composed);
        assertTrue(compiled.engine().transform("(x * 1) + 0").stream()
            .anyMatch(result -> result.transformedExpression().equals("x")));
    }

    @Test
    void mutatesExistingWrappersAndDecisionSemantics() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan parent = EvolutionRewriteProgramPlan.create(
            genome,
            new Prune(
                "outer_prune",
                new Prioritize(
                    "outer_priority",
                    new FirstApplicable(
                        "fallback_choice",
                        List.of(
                            new Source("first_zero", List.of("add_zero")),
                            new Source("then_multiply", List.of("mul_one")))),
                    Priority.estimatedCostThenRule()),
                4,
                "initial explicit bound"),
            12,
            12);
        MutationCatalog emptyCatalog = new MutationCatalog(
            List.of(), List.of(), List.of(), List.of(), List.of());

        var batch = new DeterministicRewriteProgramMutator().mutate(
            genome,
            parent,
            emptyCatalog,
            0L,
            new MutationLimits(100, 100));

        assertTrue(batch.attempts().stream().anyMatch(attempt ->
            attempt.kind() == EvolutionRewriteProgramMutationKind.REMOVE_WRAPPER
                && attempt.status() == MutationStatus.ACCEPTED));
        assertTrue(batch.attempts().stream().anyMatch(attempt ->
            attempt.kind()
                == EvolutionRewriteProgramMutationKind.FIRST_APPLICABLE_TO_CHOICE
                && attempt.status() == MutationStatus.ACCEPTED));
        assertTrue(batch.attempts().stream().anyMatch(attempt ->
            attempt.kind()
                == EvolutionRewriteProgramMutationKind.SWAP_ADJACENT_CHILDREN
                && attempt.status() == MutationStatus.ACCEPTED));
    }

    @Test
    void enforcesCatalogGenomeAndAcceptanceBoundaries() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan parent = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("initial_zero_source", List.of("add_zero")),
            8,
            8);
        DeterministicRewriteProgramMutator mutator =
            new DeterministicRewriteProgramMutator();

        MutationCatalog unknownSource = new MutationCatalog(
            List.of(), List.of(), List.of(), List.of(), List.of("missing_gene"));
        assertThrows(IllegalArgumentException.class,
            () -> mutator.mutate(
                genome,
                parent,
                unknownSource,
                0L,
                new MutationLimits(10, 10)));

        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2)),
            List.of(Requirement.assumptionFree()),
            List.of(Priority.estimatedCostThenRule()),
            List.of(4),
            List.of("mul_one"));
        var bounded = mutator.mutate(
            genome,
            parent,
            catalog,
            0L,
            new MutationLimits(100, 1));

        assertEquals(1, bounded.acceptedCount());
        assertTrue(bounded.attempts().stream().anyMatch(attempt ->
            attempt.status() == MutationStatus.REJECTED
                && attempt.blockers().contains(
                    "ACCEPTED_BUDGET_EXHAUSTED:maxAccepted")));

        EvolutionGenome otherGenome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("other_gene", "?A-0", "?A"));
        assertThrows(IllegalArgumentException.class,
            () -> mutator.mutate(
                otherGenome,
                parent,
                new MutationCatalog(
                    List.of(), List.of(), List.of(), List.of(), List.of()),
                0L,
                new MutationLimits(10, 10)));
    }

    @Test
    void seedControlsDeterministicProposalWindowAndBatchHash() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan parent = EvolutionRewriteProgramPlan.create(
            genome,
            new Source("initial_zero_source", List.of("add_zero")),
            8,
            8);
        MutationCatalog catalog = new MutationCatalog(
            List.of(new RepeatBounds(1, 2), new RepeatBounds(1, 3)),
            List.of(
                Requirement.assumptionFree(),
                Requirement.maxPrimitiveSteps(3)),
            List.of(Priority.estimatedCostThenRule()),
            List.of(2, 4),
            List.of("mul_one"));
        DeterministicRewriteProgramMutator mutator =
            new DeterministicRewriteProgramMutator();

        var first = mutator.mutate(
            genome,
            parent,
            catalog,
            1L,
            new MutationLimits(3, 3));
        var second = mutator.mutate(
            genome,
            parent,
            catalog,
            2L,
            new MutationLimits(3, 3));

        assertNotEquals(
            first.attempts().getFirst().proposalKey(),
            second.attempts().getFirst().proposalKey());
        assertNotEquals(first.contentHash(), second.contentHash());
        assertTrue(first.toCanonicalJson().contains(
            "regelsuche.evolution-rewrite-program-mutation-batch/v1"));
    }

    private static EvolutionGenome genome() {
        return EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A"),
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
    }
}
