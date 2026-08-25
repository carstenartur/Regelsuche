package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactorizationEngineContractTest {
    @Test
    void workLedgerRetainsCanonicalStageOrder() {
        Map<String, Long> reverseInsertionOrder = new LinkedHashMap<>();
        reverseInsertionOrder.put("verify.product", 3L);
        reverseInsertionOrder.put("engine.enumeration", 5L);
        reverseInsertionOrder.put("engine.constraints", 2L);

        PolynomialWorkLedger first =
            new PolynomialWorkLedger(reverseInsertionOrder);
        PolynomialWorkLedger second =
            new PolynomialWorkLedger(Map.of(
                "engine.constraints", 2L,
                "verify.product", 3L,
                "engine.enumeration", 5L));

        assertEquals(
            List.of(
                "engine.constraints",
                "engine.enumeration",
                "verify.product"),
            new ArrayList<>(first.stages().keySet()));
        assertEquals(first, second);
        assertEquals(
            first.canonicalMaterial(),
            second.canonicalMaterial());
        assertEquals(10L, first.totalWorkUnits());
        assertThrows(UnsupportedOperationException.class, () ->
            first.stages().put("late-stage", 1L));

        Map<String, Long> overflowing = new LinkedHashMap<>();
        overflowing.put("engine.maximum", Long.MAX_VALUE);
        overflowing.put("verify.one-more", 1L);
        assertThrows(IllegalArgumentException.class, () ->
            new PolynomialWorkLedger(overflowing));
    }

    @Test
    void unresolvedRemainderMustBeCanonicalAndMatchBackendClaim() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> factor = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1), BigInteger.ONE,
                Monomial.of(0), BigInteger.ONE));
        List<PolynomialFactor<BigInteger>> factors = List.of(
            new PolynomialFactor<>(factor, 1));

        assertThrows(IllegalArgumentException.class, () ->
            new FactorizationEngine.Proposal<>(
                BigInteger.ONE,
                factors,
                SparsePolynomial.constant(ring, BigInteger.TWO),
                "sha256:" + "a".repeat(64)));

        SparsePolynomial<BigInteger> unresolved = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1), BigInteger.ONE,
                Monomial.of(0), BigInteger.TWO));
        FactorizationEngine.Proposal<BigInteger> proposal =
            new FactorizationEngine.Proposal<>(
                BigInteger.ONE,
                factors,
                unresolved,
                "sha256:" + "b".repeat(64));

        assertThrows(IllegalArgumentException.class, () ->
            new FactorizationEngine.EngineResult<>(
                "test.factorization-engine/v1",
                FactorizationEngine.Outcome.CANDIDATES,
                "CONTRADICTORY_COMPLETE_RESULT",
                PolynomialWorkLedger.empty(),
                List.of(proposal),
                FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION,
                "sha256:" + "c".repeat(64)));
    }
}
