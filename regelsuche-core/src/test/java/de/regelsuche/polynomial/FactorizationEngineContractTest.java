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

        FactorizationEngine.WorkLedger first =
            new FactorizationEngine.WorkLedger(reverseInsertionOrder);
        FactorizationEngine.WorkLedger second =
            new FactorizationEngine.WorkLedger(Map.of(
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
    }

    @Test
    void completeBackendClaimCannotRetainAnUnresolvedRemainder() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> factor = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1), BigInteger.ONE,
                Monomial.of(0), BigInteger.ONE));
        SparsePolynomial<BigInteger> remainder = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1), BigInteger.ONE,
                Monomial.of(0), BigInteger.TWO));
        FactorizationEngine.Proposal<BigInteger> proposal =
            new FactorizationEngine.Proposal<>(
                BigInteger.ONE,
                List.of(new PolynomialFactor<>(factor, 1)),
                remainder,
                "sha256:" + "a".repeat(64));

        assertThrows(IllegalArgumentException.class, () ->
            new FactorizationEngine.EngineResult<>(
                "test.factorization-engine/v1",
                FactorizationEngine.Outcome.CANDIDATES,
                "CONTRADICTORY_COMPLETE_RESULT",
                FactorizationEngine.WorkLedger.empty(),
                List.of(proposal),
                FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION,
                "sha256:" + "b".repeat(64)));
    }
}
