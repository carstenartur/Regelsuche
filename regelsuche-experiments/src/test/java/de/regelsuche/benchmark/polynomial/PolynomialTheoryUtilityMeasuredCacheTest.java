package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Larger authorities below are component controls, never frozen study rows. */
class PolynomialTheoryUtilityMeasuredCacheTest {
    @Test
    void retentionRefusesTheOldAdmissionFloorBeforeMutation() {
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var primitive = primitive("x^2-1");
        var authority = new Work(1023);
        assertThrows(PolynomialWorkAuthority.LimitReached.class, () -> store.retainMeasured(
            primitive, "control", "v1", observation(primitive), authority));
        assertEquals(0, store.size());
        assertEquals(0, store.stats().insertions());
        assertEquals(0, authority.ledger().units("cache.insertion.entry-writes"));
    }

    @Test
    void measuredRetentionKeepsRealFifoAndInvalidatesEvictedLookups() {
        var store = new VerifiedPolynomialTransitionCacheStore(1);
        var first = primitive("x^2-1");
        var second = primitive("x^2-4");
        var authority = new Work(100_000_000);
        var measured = store.retainMeasured(first, "control", "v1", observation(first), authority);
        var retained = measured.retention();
        var work = measured.work();
        assertEquals(1, work.units("cache.insertion.entry-writes"));
        assertTrue(work.units("cache.insertion.evidence-hash-utf8-bytes") > 0);
        var lookup = store.lookup(retained.lookupRequest());
        assertTrue(store.replay(lookup).replayed());
        var next = store.retainMeasured(second, "control", "v1", observation(second), authority);
        var replacement = next.retention();
        assertEquals(retained.entryId(), replacement.eviction().orElseThrow().entryId());
        assertFalse(store.replay(lookup).replayed());
        assertEquals(1, authority.ledger().units("cache.eviction.fifo-entry-removals"));
        long before = authority.ledger().totalWorkUnits();
        store.retain(second, "legacy", "v1", observation(second));
        assertEquals(before, authority.ledger().totalWorkUnits(), "measurement scope leaked into legacy API");
    }

    private static VerifiedPolynomialTransitionCacheStore.Observation observation(
            de.regelsuche.polynomial.ExactFactorizationTransformationPipeline.Result primitive) {
        return new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(),
            List.of("component-control"), List.of());
    }

    private static de.regelsuche.polynomial.ExactFactorizationTransformationPipeline.Result primitive(String source) {
        var parsed = new ExpressionParser().parseExactTerm(source);
        var nested = new ExactNestedFactorizationTransformationPipeline().transform(parsed,
            new TreePosition(List.of(), ExpressionFormatter.format(parsed.expression())),
            NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
        assertTrue(nested.transformed());
        return nested.transformation().orElseThrow();
    }

    private static final class Work implements PolynomialWorkAuthority {
        private final long ceiling;
        private final LinkedHashMap<String, Long> stages = new LinkedHashMap<>();
        private Work(long ceiling) { this.ceiling = ceiling; }
        @Override public long remainingOpaqueWorkUnits() { return ceiling - ledger().totalWorkUnits(); }
        @Override public void consume(PolynomialWorkLedger charge) {
            if (charge.totalWorkUnits() > remainingOpaqueWorkUnits()) throw new LimitReached();
            charge.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
        }
        private PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }
}
