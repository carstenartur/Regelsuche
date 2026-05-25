package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProofCacheTest {

    @Test
    void missOnEmptyCache() {
        InMemoryProofCache cache = new InMemoryProofCache();
        ProofCacheKey key = ProofCacheKey.of("A+0", "A", List.of(), "lean4");
        assertFalse(cache.get(key).isPresent());
        assertEquals(0, cache.size());
    }

    @Test
    void hitAfterPut() {
        InMemoryProofCache cache = new InMemoryProofCache();
        ProofCacheKey key = ProofCacheKey.of("A+0", "A", List.of(), "lean4");
        cache.put(key, CandidateProofStatus.FORMALLY_PROVED);
        assertTrue(cache.get(key).isPresent());
        assertEquals(CandidateProofStatus.FORMALLY_PROVED, cache.get(key).get());
    }

    @Test
    void belowThresholdNotCached() {
        InMemoryProofCache cache = new InMemoryProofCache();
        ProofCacheKey key = ProofCacheKey.of("A+0", "A", List.of(), "lean4");
        // VALIDATED_BY_EXAMPLES is below FORMALLY_PROVABLE threshold
        cache.put(key, CandidateProofStatus.VALIDATED_BY_EXAMPLES);
        assertFalse(cache.get(key).isPresent(),
            "results below FORMALLY_PROVABLE should not be cached");
    }

    @Test
    void clearEmptiesCache() {
        InMemoryProofCache cache = new InMemoryProofCache();
        ProofCacheKey key = ProofCacheKey.of("A+0", "A", List.of(), "lean4");
        cache.put(key, CandidateProofStatus.FORMALLY_PROVED);
        cache.clear();
        assertEquals(0, cache.size());
        assertFalse(cache.get(key).isPresent());
    }

    @Test
    void differentProverVersionsProduceDifferentKeys() {
        ProofCacheKey v1 = ProofCacheKey.of("A*B", "B*A", List.of(), "lean4-v1");
        ProofCacheKey v2 = ProofCacheKey.of("A*B", "B*A", List.of(), "lean4-v2");
        assertFalse(v1.equals(v2));
    }

    @Test
    void assumptionOrderDoesNotAffectKey() {
        List<Assumption> ab = List.of(Assumption.positive("a"), Assumption.nonZero("b"));
        List<Assumption> ba = List.of(Assumption.nonZero("b"), Assumption.positive("a"));
        ProofCacheKey k1 = ProofCacheKey.of("x", "x", ab, "lean4");
        ProofCacheKey k2 = ProofCacheKey.of("x", "x", ba, "lean4");
        assertEquals(k1, k2, "key must be order-independent for assumptions");
    }

    @Test
    void semanticallyEquivalentNonZeroAssumptionsProduceSameKey() {
        ProofCacheKey left = ProofCacheKey.of("x", "x",
            List.of(new Assumption(Assumption.Kind.NON_ZERO, "b != 0", List.of("b"))), "lean4");
        ProofCacheKey swapped = ProofCacheKey.of("x", "x",
            List.of(new Assumption(Assumption.Kind.NON_ZERO, "0 != b", List.of("b"))), "lean4");
        ProofCacheKey unicode = ProofCacheKey.of("x", "x",
            List.of(new Assumption(Assumption.Kind.NON_ZERO, "b≠0", List.of("b"))), "lean4");

        assertEquals(left, swapped);
        assertEquals(left, unicode);
    }

    @Test
    void differentAssumptionsProduceDifferentKeys() {
        ProofCacheKey withAssumption = ProofCacheKey.of("x/y", "x/y",
            List.of(Assumption.nonZero("y")), "lean4");
        ProofCacheKey withoutAssumption = ProofCacheKey.of("x/y", "x/y", List.of(), "lean4");
        assertFalse(withAssumption.equals(withoutAssumption));
    }
}
