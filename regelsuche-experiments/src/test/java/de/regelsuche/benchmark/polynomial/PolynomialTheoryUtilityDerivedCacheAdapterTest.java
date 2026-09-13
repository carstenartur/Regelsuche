package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import java.util.ArrayList;
import java.util.List;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityDerivedCacheAdapterTest {
    @Test
    void actualComponentCacheSharesGenerationAndPrimitiveLineageThenEvictsInFifoOrder() {
        var first = new ExpressionParser().parseExactTerm("x^2-1");
        var other = new ExpressionParser().parseExactTerm("x^2-4");
        var cache = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("component-only", 1);
        var frozen = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("VERIFIED_DERIVED_MACRO_CACHE")).findFirst().orElseThrow();
        // This authority cannot pass the productive adapter's exact frozen-row check.
        var component = new PolynomialTheoryUtilityExecutionInput(frozen.inputId(), frozen.rowId(), frozen.runId(),
            frozen.caseId(), frozen.profileId(), frozen.checkpointId(), frozen.adapterId(),
            100_000_000, 100_000_000, 100_000_000, frozen.inputStatus());
        var work = new PolynomialTheoryUtilityWorkAuthority(component);
        var inserted = cache.execute(first, List.of(), work);
        assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.VALIDATED_TRANSITION, inserted.status());
        assertNotNull(inserted.retention());
        long derivation = work.work().factorizationWork();
        var replayed = cache.execute(first, List.of(), work);
        assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.VALIDATED_TRANSITION, replayed.status());
        assertNotNull(replayed.released());
        assertEquals(derivation, work.work().factorizationWork(), "replay invoked another factorization");
        assertEquals(inserted.retention().retention().entryId(), replayed.lookup().retained().entryId());
        assertEquals(inserted.pipeline().transformation().orElseThrow().certificateHash(),
            replayed.released().authorization().orElseThrow().certificateHash());
        var replaced = cache.execute(other, List.of(), work);
        assertEquals(inserted.retention().retention().entryId(),
            replaced.retention().retention().eviction().orElseThrow().entryId());
        var recomputed = cache.execute(first, List.of(), work);
        assertNull(recomputed.released());
        assertTrue(recomputed.retention().retention().retentionGeneration()
            > inserted.retention().retention().retentionGeneration());
        var isolated = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("another-component-run", 1)
            .execute(first, List.of(), work);
        assertNull(isolated.released());
        assertNotEquals(recomputed.retention().retention().entryId(), isolated.retention().retention().entryId());
    }

    @Test
    void publicCheckpointKeepsEveryAttemptAndBudgetOutcomeWithoutInventedCacheEntries() {
        var adapter = new PolynomialTheoryUtilityDerivedCacheAdapter();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("VERIFIED_DERIVED_MACRO_CACHE")
                && value.checkpointId().equals("CP06_FULL")).toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var first = inputs.getFirst();
        var measured = new ArrayList<PolynomialTheoryUtilityMeasuredCandidate>();
        try (var run = (MeasuredRun) adapter.openRun(new RunDescriptor(first.runId(), first.profileId(),
                first.checkpointId(), first.adapterId(), inputs.size()))) {
            for (int index = 0; index < inputs.size(); index++) {
                measured.add(run.executeObserved(inputs.get(index), formation.get(index)));
            }
        }
        assertEquals(20, measured.size());
        assertTrue(measured.stream().flatMap(value -> value.measurements().factorizationAttempts().stream())
            .findAny().isPresent(), measured.stream().map(value -> value.result().input().caseId() + ":"
                + value.result().observations().occurrences()).toList().toString());
        assertTrue(measured.stream().flatMap(value -> value.result().observations().occurrences().stream())
            .anyMatch(value -> value.terminalStatus() == PolynomialTheoryUtilityCandidateResult.TerminalStatus.BUDGET_INCONCLUSIVE));
        for (int index = 0; index < measured.size(); index++) {
            var result = measured.get(index).result();
            assertEquals(PolynomialTheoryUtilityExecutionObservations.paths(formation.get(index)),
                result.observations().occurrences().stream().map(value -> value.path()).toList());
            assertTrue(result.work().mechanicalWork() <= inputs.get(index).totalMechanicalWork());
            assertTrue(result.work().factorizationWork() <= inputs.get(index).factorizationWork());
            assertEquals(0, result.work().cacheInsertionWork());
            assertTrue(measured.get(index).measurements().cacheEvents().stream()
                .allMatch(value -> value.kind() == PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS));
        }
    }
}
