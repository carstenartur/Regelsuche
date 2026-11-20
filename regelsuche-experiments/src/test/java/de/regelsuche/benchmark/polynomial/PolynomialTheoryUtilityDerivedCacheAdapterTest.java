package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityDerivedCacheAdapterTest {
    @Test
    void actualComponentCacheSharesGenerationAndPrimitiveLineageThenEvictsInFifoOrder() {
        var first = new ExpressionParser().parseExactTerm("x^2-1");
        var other = new ExpressionParser().parseExactTerm("x^2-4");
        var last = new ExpressionParser().parseExactTerm("x^2-9");
        var cache = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("component-only", 2);
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
        var second = cache.execute(other, List.of(), work);
        assertTrue(second.retention().retention().eviction().isEmpty());
        long derivation = work.work().factorizationWork();
        var replayed = cache.execute(first, List.of(), work);
        assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.VALIDATED_TRANSITION, replayed.status());
        assertNotNull(replayed.released());
        assertEquals(derivation, work.work().factorizationWork(), "replay invoked another factorization");
        assertEquals(inserted.retention().retention().entryId(), replayed.lookup().retained().entryId());
        assertEquals(inserted.pipeline().transformation().orElseThrow().certificateHash(),
            replayed.released().authorization().orElseThrow().certificateHash());
        var replaced = cache.execute(last, List.of(), work);
        assertEquals(inserted.retention().retention().entryId(),
            replaced.retention().retention().eviction().orElseThrow().entryId());
        var retained = cache.execute(other, List.of(), work);
        assertEquals(second.retention().retention().entryId(), retained.lookup().retained().entryId());
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
        var measured = measuredCheckpoint("CP06_FULL");
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        assertEquals(20, measured.size());
        assertTrue(measured.stream().flatMap(value -> value.measurements().factorizationAttempts().stream())
            .findAny().isPresent(), measured.stream().map(value -> value.result().input().caseId() + ":"
                + value.result().observations().occurrences()).toList().toString());
        assertTrue(measured.stream().flatMap(value -> value.result().observations().occurrences().stream())
            .anyMatch(value -> value.terminalStatus() == PolynomialTheoryUtilityCandidateResult.TerminalStatus.BUDGET_INCONCLUSIVE));
        var cacheEvents = measured.stream().flatMap(value -> value.measurements().cacheEvents().stream()).toList();
        assertFalse(cacheEvents.isEmpty());
        assertTrue(cacheEvents.stream().allMatch(value -> value.kind() == PolynomialTheoryUtilityCacheEvent.Kind.LOOKUP_MISS));
        for (int index = 0; index < measured.size(); index++) {
            var result = measured.get(index).result();
            assertEquals(PolynomialTheoryUtilityExecutionObservations.paths(formation.get(index)),
                result.observations().occurrences().stream().map(value -> value.path()).toList());
            assertTrue(result.work().mechanicalWork() <= result.input().totalMechanicalWork());
            assertTrue(result.work().factorizationWork() <= result.input().factorizationWork());
            assertEquals(0, result.work().cacheInsertionWork());
        }
    }

    static List<PolynomialTheoryUtilityMeasuredCandidate> measuredCheckpoint(String checkpoint) {
        var adapter = new PolynomialTheoryUtilityDerivedCacheAdapter();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals(adapter.profileId())
                && value.checkpointId().equals(checkpoint)).toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var first = inputs.getFirst();
        var measured = new ArrayList<PolynomialTheoryUtilityMeasuredCandidate>();
        try (var run = (MeasuredRun) adapter.openRun(new RunDescriptor(first.runId(), first.profileId(),
                first.checkpointId(), first.adapterId(), inputs.size()))) {
            for (int index = 0; index < inputs.size(); index++) {
                measured.add(run.executeObserved(inputs.get(index), formation.get(index)));
            }
        }
        return measured;
    }
}
