package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import java.security.MessageDigest;
import java.security.MessageDigestSpi;
import java.security.Provider;
import java.security.Security;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** Observes real SHA-256 input through an unchanged SUN implementation. */
@ResourceLock("java.security.providers")
public final class PolynomialTheoryUtilityCacheHashAccountingTest {
    @Test
    void refusedRetentionNeverConstructsAnUnmeasuredVerifiedTransition() {
        var parsed = new ExpressionParser().parseExactTerm("x^2-1");
        var authority = componentAuthority(100_000);
        var cache = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("budget-control", 1);
        try (var hashes = new HashObservation()) {
            var result = cache.execute(parsed, List.of(), authority);
            assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.BUDGET_INCONCLUSIVE, result.status());
            assertEquals("CACHE_RETENTION_NOT_ADMITTED", result.detailCode());
            assertTrue(result.pipeline().transformed());
            assertNull(result.retention());
            assertEquals(0, hashes.bytes("unscoped-transition"), "size inspection issued a complete unmeasured certificate");
            assertEquals(0, authority.work().cacheInsertionWork());
            hashes.assertLedger(authority);
        }
    }

    @Test
    void insertedAndReplayedEvidenceUsesExactlyTheHashWorkThatExecuted() {
        var parsed = new ExpressionParser().parseExactTerm("x^2-1");
        var authority = componentAuthority(100_000_000);
        var cache = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("hash-control", 1);
        try (var hashes = new HashObservation()) {
            var inserted = cache.execute(parsed, List.of(), authority);
            assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.VALIDATED_TRANSITION, inserted.status());
            long factorization = authority.work().factorizationWork();
            var replayed = cache.execute(parsed, List.of(), authority);
            assertEquals(PolynomialTheoryUtilityCandidateResult.TerminalStatus.VALIDATED_TRANSITION, replayed.status());
            assertEquals(factorization, authority.work().factorizationWork(), "cache replay repeated factorization");
            assertEquals(inserted.pipeline().transformation().orElseThrow().certificateHash(),
                replayed.released().authorization().orElseThrow().certificateHash());
            assertEquals(0, hashes.bytes("unscoped-transition"));
            assertTrue(hashes.bytes("cache.lookup.") > 0);
            assertTrue(hashes.bytes("cache.replay.") > 0);
            hashes.assertLedger(authority);
        }
    }

    @Test
    void measuredReceiptsKeepForeignAndEvictedLookupsClosedAndRefuseBeforeExecution() {
        var cache = new PolynomialTheoryUtilityDerivedCacheAdapter.CacheState("seed-control", 1);
        var first = cache.execute(new ExpressionParser().parseExactTerm("x^2-1"), List.of(), componentAuthority(100_000_000))
            .pipeline().transformation().orElseThrow();
        var second = cache.execute(new ExpressionParser().parseExactTerm("x^2-4"), List.of(), componentAuthority(100_000_000))
            .pipeline().transformation().orElseThrow();
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var observation = new VerifiedPolynomialTransitionCacheStore.Observation("component", List.of("root"), List.of());
        var retained = store.retain(first, "component", "v1", observation);
        var stale = store.lookup(retained.lookupRequest());
        var foreign = new VerifiedPolynomialTransitionCacheStore(1).lookup(retained.lookupRequest());
        store.retain(second, "component", "v1", observation);
        var stats = store.stats();
        var empty = componentAuthority(1);
        var work = componentAuthority(100_000_000);
        try (var hashes = new HashObservation()) {
            assertThrows(PolynomialWorkAuthority.LimitReached.class, () -> store.lookupMeasured(retained.lookupRequest(), empty));
            assertThrows(PolynomialWorkAuthority.LimitReached.class, () -> store.replayMeasured(stale, empty));
            assertEquals(stats, store.stats());
            assertEquals(0, empty.ledger().totalWorkUnits());
            assertEquals(0, hashes.bytes("cache.lookup."));
            assertEquals(0, hashes.bytes("cache.replay."));
            var rejectedForeign = store.replayMeasured(foreign, work);
            var rejectedStale = store.replayMeasured(stale, work);
            assertEquals(VerifiedPolynomialTransitionCacheStore.ReplayStatus.FOREIGN_LOOKUP, rejectedForeign.replay().status());
            assertEquals(VerifiedPolynomialTransitionCacheStore.ReplayStatus.STALE_LOOKUP, rejectedStale.replay().status());
            assertTrue(rejectedForeign.replay().authorization().isEmpty());
            assertTrue(rejectedStale.replay().authorization().isEmpty());
            assertTrue(hashes.bytes("cache.replay.") > 0, "failed evidence construction disappeared");
            hashes.assertLedger(work);
        }
    }

    private static PolynomialTheoryUtilityWorkAuthority componentAuthority(int budget) {
        var frozen = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input -> input.profileId().equals("VERIFIED_DERIVED_MACRO_CACHE")).findFirst().orElseThrow();
        return new PolynomialTheoryUtilityWorkAuthority(new PolynomialTheoryUtilityExecutionInput(
            frozen.inputId(), frozen.rowId(), frozen.runId(), frozen.caseId(), frozen.profileId(), frozen.checkpointId(),
            frozen.adapterId(), budget, budget, budget, frozen.inputStatus()));
    }

    private static final class HashObservation implements AutoCloseable {
        private static final ThreadLocal<HashObservation> ACTIVE = new ThreadLocal<>();
        private final Map<String, Long> bytes = new LinkedHashMap<>();
        private final Map<String, Long> digests = new LinkedHashMap<>();
        private final Provider provider = new Provider("CacheHashAccountingControl", "1.0", "Delegating SHA-256 observer") { };
        private HashObservation() {
            provider.put("MessageDigest.SHA-256", ObservedDigest.class.getName());
            assertEquals(1, Security.insertProviderAt(provider, 1));
            ACTIVE.set(this);
        }
        private long bytes(String category) { return bytes.getOrDefault(category, 0L); }
        private void assertLedger(PolynomialTheoryUtilityWorkAuthority authority) {
            for (String prefix : List.of("cache.lookup.", "cache.insertion.", "cache.replay.")) {
                assertEquals(bytes(prefix), authority.ledger().units(prefix + "evidence-hash-utf8-bytes"), prefix + " bytes");
                assertEquals(digests.getOrDefault(prefix, 0L).longValue(),
                    authority.ledger().units(prefix + "evidence-hash-completions"), prefix + " digests");
            }
        }
        private static String category() {
            var stack = StackWalker.getInstance().walk(frames -> frames.toList());
            String store = "de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore";
            if (stack.stream().anyMatch(frame -> frame.getClassName().equals(store + "$EvidenceDigest"))) {
                if (stack.stream().anyMatch(frame -> frame.getClassName().equals(store) && frame.getMethodName().equals("retain"))) return "cache.insertion.";
                if (stack.stream().anyMatch(frame -> frame.getClassName().equals(store) && frame.getMethodName().equals("lookup"))) return "cache.lookup.";
                if (stack.stream().anyMatch(frame -> frame.getClassName().equals(store) && frame.getMethodName().equals("replay"))) return "cache.replay.";
                if (stack.stream().anyMatch(frame -> frame.getClassName().equals(store + "$VerifiedTransition") && frame.getMethodName().equals("from"))) return "unscoped-transition";
            }
            if (stack.stream().anyMatch(frame -> frame.getClassName().equals(PolynomialTheoryUtilityDerivedCacheAdapter.class.getName())
                    && frame.getMethodName().equals("hash"))) return "cache.lookup.";
            return "other";
        }
        @Override public void close() {
            ACTIVE.remove();
            Security.removeProvider(provider.getName());
        }
    }

    public static final class ObservedDigest extends MessageDigestSpi {
        private final MessageDigest delegate;
        public ObservedDigest() {
            try { delegate = MessageDigest.getInstance("SHA-256", "SUN"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        private static void record(long bytes, boolean digest) {
            var current = HashObservation.ACTIVE.get();
            if (current == null) return;
            String category = HashObservation.category();
            if (digest) current.digests.merge(category, 1L, Math::addExact);
            else current.bytes.merge(category, bytes, Math::addExact);
        }
        @Override protected void engineUpdate(byte value) { record(1, false); delegate.update(value); }
        @Override protected void engineUpdate(byte[] value, int offset, int length) { record(length, false); delegate.update(value, offset, length); }
        @Override protected byte[] engineDigest() { record(0, true); return delegate.digest(); }
        @Override protected void engineReset() { delegate.reset(); }
        @Override protected int engineGetDigestLength() { return 32; }
    }
}
