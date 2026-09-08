package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.program.BudgetedTransformationSource;
import de.regelsuche.search.program.BudgetedTransformationSourceExecutor;
import de.regelsuche.search.program.ExactPolynomialTransformationSource;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExactPolynomialTransformationSourceTest {
    private static final long WORK = 40_000_000;

    @Test
    void sourceIdentityUsesUtf8ByteLengthsForNonAsciiEngineIds() {
        FactorizationEngine<ExactRational> engine = new FactorizationEngine<>() {
            public String engineId() { return "engine:ä𝄞"; }
            public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }
            public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
                throw new AssertionError("identity construction must not invoke the engine");
            }
        };
        var source = new ExactPolynomialTransformationSource(engine,
            ExactPolynomialTransformationSource.Mode.ON_DEMAND, List.of(), 2);
        // Independent UTF-8 length-prefixed SHA-256 fixture; includes a supplementary code point.
        assertEquals("sha256:8a7c90a6c6da59468620bf77d9c9ac47df2a4d1d092755a0fc203d09e2aec4b1",
            source.identity().revisionHash());
        assertEquals(source.identity().revisionHash(), source.identity().authorityHash());
    }

    @Test
    void searchExecutorReusesTheSamePrimitiveAtTwoDifferentOccurrences() {
        AtomicInteger calls = new AtomicInteger();
        var source = new ExactPolynomialTransformationSource(counted(calls),
            ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE, List.of(0), 2);
        var executor = new BudgetedTransformationSourceExecutor();
        String expression = "(x^2-1)+(x^2-1)";
        var first = executor.execute(source, expression, WORK);
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, first.status(), first.sourceResult().detailCode());
        var secondSource = source.atPath(List.of(1));
        var second = executor.execute(secondSource, expression, WORK);
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, second.status(), second.sourceResult().detailCode());
        assertEquals(1, calls.get());
        var left = first.candidates().getFirst();
        var right = second.candidates().getFirst();
        assertEquals(left.evidenceHash(), right.evidenceHash());
        assertEquals(left.mathematicalWorkUnits(), right.mathematicalWorkUnits());
        assertNotEquals(left.applicationKey(), right.applicationKey());
        assertEquals(1, source.cacheStats().replays());
        assertEquals(7, secondSource.lastObservation().orElseThrow().cacheReplay().orElseThrow().primitiveExpansion().size());
        assertTrue(secondSource.lastWork().stages().keySet().stream().anyMatch(s -> s.startsWith("cache.replay.")));
        assertEquals(second.sourceResult().mechanicalWorkUnits(), secondSource.lastWork().totalWorkUnits());
    }

    @Test
    void learnedIdentityEntersTheSameExecutableCacheWithoutAnotherEngineInvocation() {
        AtomicInteger calls = new AtomicInteger();
        var engine = counted(calls);
        var learned = new PolynomialTheorySubsumptionClassifier(engine).classify("x^2-1", "(x-1)*(x+1)");
        assertTrue(learned.subsumed());
        var source = new ExactPolynomialTransformationSource(engine,
            ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE, List.of(0), 2);
        assertTrue(source.retainLearned(learned, List.of("mined:generation:1"), WORK).totalWorkUnits() > 0);
        var result = source.transform("f(x^2-1,9007199254740993)", WORK);
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, result.status(), result.detailCode());
        assertEquals(1, calls.get());
        assertEquals(learned.applicationKey(), result.candidates().getFirst().evidenceHash());
        assertTrue(result.candidates().getFirst().transformedExpression().contains("9007199254740993"),
            "surrounding exact literals must never be rendered through double");
    }

    @Test
    void disabledCacheAndInsufficientAuthorityRemainDistinctAndNeverResetTheBudget() {
        AtomicInteger calls = new AtomicInteger();
        var source = new ExactPolynomialTransformationSource(counted(calls),
            ExactPolynomialTransformationSource.Mode.ON_DEMAND, List.of(), 1);
        var tiny = source.transform("x^2-1", 1);
        assertEquals(BudgetedTransformationSource.Status.BUDGET_INCONCLUSIVE, tiny.status());
        assertTrue(tiny.mechanicalWorkUnits() <= 1);
        assertEquals(0, calls.get());
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, source.transform("x^2-1", WORK).status());
        assertEquals(BudgetedTransformationSource.Status.CANDIDATES, source.transform("x^2-1", WORK).status());
        assertEquals(2, calls.get());
        assertEquals(0, source.cacheStats().retainedEntries());
        assertEquals(BudgetedTransformationSource.Status.NO_MATCH, source.transform("x+1/x", WORK).status());
        assertEquals(BudgetedTransformationSource.Status.NO_MATCH, source.atPath(List.of(4)).transform("x^2-1", WORK).status());
    }

    @Test
    void cacheFifoEvictionDoesNotBecomeAProofOrAnUnboundedSideIndex() {
        AtomicInteger calls = new AtomicInteger();
        var source = new ExactPolynomialTransformationSource(counted(calls),
            ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE, List.of(), 1);
        for (String expression : List.of("x^2-1", "x^2-4", "x^2-1")) {
            var result = source.transform(expression, WORK);
            assertEquals(BudgetedTransformationSource.Status.CANDIDATES, result.status(), result.detailCode());
        }
        assertEquals(3, calls.get());
        assertEquals(2, source.cacheStats().evictions());
        assertEquals(1, source.cacheStats().retainedEntries());
        assertEquals(3, source.cacheStats().misses());
    }

    @Test
    void failedLearningAdmissionDoesNotInsertOrEvictAnything() {
        var engine = NativeUnivariateFactorizationEngine.boundedRationals();
        var learned = new PolynomialTheorySubsumptionClassifier(engine).classify("x^2-1", "(x-1)*(x+1)");
        var source = new ExactPolynomialTransformationSource(engine,
            ExactPolynomialTransformationSource.Mode.VERIFIED_CACHE, List.of(), 1);
        assertThrows(IllegalArgumentException.class, () -> source.retainLearned(learned, List.of("generation:1"), 1));
        assertEquals(0, source.cacheStats().retainedEntries());
        assertEquals(0, source.cacheStats().evictions());
        assertThrows(IllegalArgumentException.class, () -> source.transform("x^2-1", Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> source.atPath(List.of(-1)));
    }

    private FactorizationEngine<ExactRational> counted(AtomicInteger calls) {
        var nativeEngine = NativeUnivariateFactorizationEngine.boundedRationals();
        return new FactorizationEngine<>() {
            public String engineId() { return nativeEngine.engineId(); }
            public String coefficientDomainId() { return nativeEngine.coefficientDomainId(); }
            public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
                calls.incrementAndGet();
                return nativeEngine.propose(request);
            }
        };
    }
}
