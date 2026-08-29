package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.LookupRequest;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.Observation;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.RetentionResult;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VerifiedPolynomialTransitionCacheStoreTest {
    private static final String CACHE_ID = "polynomial-factorization";
    private static final String REVISION = "revision-1";
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void replaysTheSameVerifierAuthorizedTransitionAndPrimitiveExpansion() {
        var transformation = transform("x^2 - 1");
        var store = new VerifiedPolynomialTransitionCacheStore(4);
        RetentionResult retained = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-1"));
        var replay = store.replay(store.lookup(retained.lookupRequest()));

        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus.INSERTED,
            retained.status());
        assertTrue(retained.eviction().isEmpty());
        assertTrue(replay.replayed());
        assertEquals(
            transformation.certificateHash(),
            replay.transition().orElseThrow().authorityCertificateHash());
        assertEquals(
            retained.transitionId(),
            replay.transition().orElseThrow().id());
        assertEquals(7, replay.primitiveExpansion().size());
        assertEquals(
            replay.lookup().lookupWork().totalWorkUnits()
                + replay.replayWork().totalWorkUnits(),
            replay.actualExecutionWork().totalWorkUnits());
        assertTrue(replay.actualExecutionWork().stages().keySet().stream()
            .allMatch(stage -> stage.startsWith("verified-cache.")));
        assertEquals(
            1,
            replay.replayWork().units(
                "verified-cache.replay.lookup-authority-checks"));
        assertEquals(
            transformation.totalWork(),
            replay.retainedDerivationWork().orElseThrow());
        assertNotEquals(
            replay.actualExecutionWork(),
            replay.retainedDerivationWork().orElseThrow());
        assertTrue(retained.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(replay.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            new VerifiedPolynomialTransitionCacheStore.Stats(
                1,
                1,
                1,
                0,
                0,
                1),
            store.stats());
    }

    @Test
    void exactRevisionMismatchIsAMissAndCannotReplay() {
        var store = new VerifiedPolynomialTransitionCacheStore();
        var request = retain(store, "x^2 - 1", "observation-1")
            .lookupRequest();
        var mismatched = new LookupRequest(
            request.cacheId(),
            "revision-2",
            request.sourceEvidenceHash(),
            request.sourceExpression());
        var lookup = store.lookup(mismatched);
        var replay = store.replay(lookup);

        assertFalse(lookup.hit());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.LookupStatus.MISS,
            lookup.status());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.ReplayStatus.LOOKUP_MISS,
            replay.status());
        assertFalse(replay.replayed());
        assertTrue(replay.transition().isEmpty());
        assertTrue(replay.primitiveExpansion().isEmpty());
        assertEquals(0, store.stats().replays());
    }

    @Test
    void retentionLookupAndReplayEvidenceCanOnlyBeIssuedByTheStore() {
        assertEquals(
            0,
            VerifiedPolynomialTransitionCacheStore.RetentionResult.class
                .getConstructors()
                .length);
        assertEquals(
            0,
            VerifiedPolynomialTransitionCacheStore.LookupResult.class
                .getConstructors()
                .length);
        assertEquals(
            0,
            VerifiedPolynomialTransitionCacheStore.ReplayResult.class
                .getConstructors()
                .length);
        assertThrows(NoSuchMethodException.class, () ->
            VerifiedPolynomialTransitionCacheStore.RetentionResult.class
                .getMethod("entry"));
        assertThrows(NoSuchMethodException.class, () ->
            VerifiedPolynomialTransitionCacheStore.RetentionResult.class
                .getMethod("transition"));
        assertThrows(NoSuchMethodException.class, () ->
            VerifiedPolynomialTransitionCacheStore.LookupResult.class
                .getMethod("entry"));
        assertThrows(NoSuchMethodException.class, () ->
            VerifiedPolynomialTransitionCacheStore.class
                .getMethod("entries"));
        assertThrows(NoSuchMethodException.class, () ->
            VerifiedPolynomialTransitionCacheStore.class.getMethod(
                "findExact",
                LookupRequest.class));
    }

    @Test
    void lookupCannotCrossStoreLifetimeEvenForIdenticalRetentionGeneration() {
        var firstStore = new VerifiedPolynomialTransitionCacheStore();
        var secondStore = new VerifiedPolynomialTransitionCacheStore();
        var first = retain(firstStore, "x^2 - 1", "observation-1");
        var second = retain(secondStore, "x^2 - 1", "observation-1");
        var foreignLookup = firstStore.lookup(first.lookupRequest());

        assertEquals(first.entryId(), second.entryId());
        assertEquals(first.transitionId(), second.transitionId());
        assertEquals(
            first.retentionGeneration(),
            second.retentionGeneration());
        assertEquals(first.replayBindingId(), second.replayBindingId());

        var replay = secondStore.replay(foreignLookup);

        assertEquals(
            VerifiedPolynomialTransitionCacheStore.ReplayStatus.FOREIGN_LOOKUP,
            replay.status());
        assertFalse(replay.replayed());
        assertTrue(replay.transition().isEmpty());
        assertTrue(replay.primitiveExpansion().isEmpty());
        assertEquals(
            1,
            replay.replayWork().units(
                "verified-cache.replay.lookup-authority-checks"));
        assertEquals(
            0,
            replay.replayWork().units(
                "verified-cache.replay.entry-rechecks"));
        assertEquals(
            0,
            replay.replayWork().units(
                "verified-cache.replay.output-code-units"));
        assertEquals(0, secondStore.stats().replays());
    }

    @Test
    void repeatedEvidenceIsIdempotentAndDistinctObservationsAddLineage() {
        var store = new VerifiedPolynomialTransitionCacheStore();
        var transformation = transform("x^2 - 1");
        var first = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-1"));
        var repeated = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-1"));
        var additional = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-2"));

        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus.INSERTED,
            first.status());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus.UNCHANGED,
            repeated.status());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus
                .LINEAGE_ADDED,
            additional.status());
        assertEquals(first.entryId(), repeated.entryId());
        assertEquals(first.entryId(), additional.entryId());
        assertEquals(first.transitionId(), additional.transitionId());
        assertEquals(
            first.retentionGeneration(),
            additional.retentionGeneration());
        assertEquals(1, first.lineageCount());
        assertEquals(2, additional.lineageCount());
        assertEquals(1, store.size());
        assertEquals(1, store.stats().insertions());
    }

    @Test
    void lineageGrowthIsBoundedAndTheLimitIsVisible() {
        var store = new VerifiedPolynomialTransitionCacheStore(1, 2);
        var transformation = transform("x^2 - 1");
        var first = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-1"));
        var second = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-2"));
        var limited = store.retain(
            transformation,
            CACHE_ID,
            REVISION,
            observation("observation-3"));

        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus.INSERTED,
            first.status());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus
                .LINEAGE_ADDED,
            second.status());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.RetentionStatus
                .LINEAGE_LIMIT_REACHED,
            limited.status());
        assertEquals(2, limited.lineageCount());
        assertEquals(second.entryId(), limited.entryId());
        assertEquals(second.replayBindingId(), limited.replayBindingId());
        assertEquals(1, store.stats().insertions());
        assertEquals(0, store.stats().evictions());
    }

    @Test
    void visibleFifoEvictionInvalidatesLookupWithoutLeakingTransition() {
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var first = retain(store, "x^2 - 1", "observation-1");
        var lookupBeforeEviction = store.lookup(first.lookupRequest());
        var second = retain(store, "x^2 - 4", "observation-2");
        var eviction = second.eviction().orElseThrow();
        var staleReplay = store.replay(lookupBeforeEviction);

        assertEquals(first.entryId(), eviction.entryId());
        assertEquals(
            first.lookupRequest().keyId(),
            eviction.lookupKeyId());
        assertEquals(
            first.replayBindingId(),
            eviction.replayBindingId());
        assertThrows(NoSuchMethodException.class, () ->
            eviction.getClass().getMethod("transition"));
        assertEquals(1, store.size());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.ReplayStatus.STALE_LOOKUP,
            staleReplay.status());
        assertFalse(staleReplay.replayed());
        assertTrue(staleReplay.transition().isEmpty());
        assertEquals(
            0,
            staleReplay.replayWork().units(
                "verified-cache.replay.output-code-units"));
        assertEquals(
            0,
            staleReplay.replayWork().units(
                "verified-cache.replay.primitive-evidence-steps"));
        assertEquals(1, store.stats().evictions());
        assertEquals(0, store.stats().replays());
    }

    @Test
    void identicalReinsertionDoesNotReviveAnEvictedLookup() {
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var first = retain(store, "x^2 - 1", "observation-1");
        var oldLookup = store.lookup(first.lookupRequest());
        retain(store, "x^2 - 4", "observation-2");
        var reinserted = retain(store, "x^2 - 1", "observation-3");
        var currentLookup = store.lookup(reinserted.lookupRequest());
        var staleReplay = store.replay(oldLookup);

        assertEquals(first.entryId(), reinserted.entryId());
        assertEquals(first.transitionId(), reinserted.transitionId());
        assertNotEquals(
            first.retentionGeneration(),
            reinserted.retentionGeneration());
        assertNotEquals(
            first.replayBindingId(),
            reinserted.replayBindingId());
        assertNotEquals(
            oldLookup.certificateHash(),
            currentLookup.certificateHash());
        assertEquals(
            VerifiedPolynomialTransitionCacheStore.ReplayStatus.STALE_LOOKUP,
            staleReplay.status());
        assertFalse(staleReplay.replayed());
        assertTrue(staleReplay.transition().isEmpty());
        assertEquals(
            0,
            staleReplay.replayWork().units(
                "verified-cache.replay.output-code-units"));
        assertEquals(
            0,
            staleReplay.replayWork().units(
                "verified-cache.replay.primitive-evidence-steps"));
    }

    @Test
    void observationMaterialIsBounded() {
        List<String> tooManyValues = IntStream.range(
                0,
                VerifiedPolynomialTransitionCacheStore
                    .MAX_OBSERVATION_VALUES + 1)
            .mapToObj(index -> "source-" + index)
            .toList();

        assertThrows(IllegalArgumentException.class, () -> new Observation(
            "observation-1",
            tooManyValues,
            List.of()));
    }

    @Test
    void rejectsAnOutcomeWithoutVerifierAuthorizedTransformation() {
        ExactParsedTerm source = parser.parseExactTerm("x^2 + 1");
        var factorization = new ExactParsedFactorizationPipeline().factor(
            source,
            NativeUnivariateFactorizationEngine.boundedRationals());
        var unavailable = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);
        var store = new VerifiedPolynomialTransitionCacheStore();

        assertFalse(unavailable.transformed());
        assertThrows(IllegalArgumentException.class, () -> store.retain(
            unavailable,
            CACHE_ID,
            REVISION,
            observation("observation-1")));
        assertEquals(0, store.size());
    }

    @Test
    void rejectsWhitespaceNormalizedCacheIdentities() {
        var store = new VerifiedPolynomialTransitionCacheStore();
        assertThrows(IllegalArgumentException.class, () -> store.retain(
            transform("x^2 - 1"),
            " " + CACHE_ID + " ",
            REVISION,
            observation("observation-1")));
        assertEquals(0, store.size());
    }

    private RetentionResult retain(
        VerifiedPolynomialTransitionCacheStore store,
        String source,
        String observationId
    ) {
        return store.retain(
            transform(source),
            CACHE_ID,
            REVISION,
            observation(observationId));
    }

    private ExactFactorizationTransformationPipeline.Result transform(
        String sourceText
    ) {
        ExactParsedTerm source = parser.parseExactTerm(sourceText);
        var factorization = new ExactParsedFactorizationPipeline().factor(
            source,
            NativeUnivariateFactorizationEngine.boundedRationals());
        var transformation = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);
        assertTrue(
            transformation.transformed(),
            transformation.detailCode());
        return transformation;
    }

    private static Observation observation(String id) {
        return new Observation(
            id,
            List.of("test:verified-factorization"),
            List.of());
    }
}
