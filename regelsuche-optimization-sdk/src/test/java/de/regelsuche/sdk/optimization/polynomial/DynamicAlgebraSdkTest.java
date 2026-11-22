package de.regelsuche.sdk.optimization.polynomial;

import de.regelsuche.math.algorithms.polynomial.DynamicAlgebraEvaluation.*;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicAlgebraSdkTest {
    @Test void bothTypedEntryPointsCarryTheCallerBudgetAndCancellation() {
        var t = List.of(BigInteger.ZERO, BigInteger.ONE);
        var req = new Request(3, t, new IfZero(new Literal(t), new Literal(List.of(BigInteger.TWO)), new Literal(t)));
        var budget = new OptimizationBudget(100_000, 1_000, 1, 2000);
        var cert = DynamicAlgebraSdk.evaluate(req,budget,CancellationToken.NONE).value().orElseThrow();
        assertEquals(List.of(BigInteger.TWO), cert.branches().getFirst().value().orElseThrow());
        assertEquals(Status.VERIFIED, DynamicAlgebraSdk.verify(req,cert,budget,CancellationToken.NONE).status());
        assertEquals(Status.INCONCLUSIVE, DynamicAlgebraSdk.verify(req,cert,budget,() -> true).status());
    }
}
