package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Explicit historical provisioning required when selecting this integration test. */
class FrozenSymPyOperationProbeIT {
    @Test
    void originalGraalPyReceivesTheJavaCodecAndReturnsTheExactAdmittedPrefix() throws Exception {
        var properties = List.of("frozen.checkout", "frozen.graalpy", "frozen.packages");
        assumeTrue(properties.stream().anyMatch(name -> System.getProperty(name) != null),
            "Historical probe not requested; supply frozen.checkout, frozen.graalpy and frozen.packages");
        for (String name : properties) {
            String value = System.getProperty(name);
            assertTrue(value != null && !value.isBlank(),
                () -> "Explicit historical probe requires nonblank system property " + name);
        }
        var probe = new FrozenSymPyOperationProbe(Path.of(System.getProperty("frozen.checkout")),
            Path.of(System.getProperty("frozen.graalpy")), Path.of(System.getProperty("frozen.packages")),
            new SymPyFactorizationPolicy("1.14.0", Duration.ofMinutes(4), 16384, 262144, 16, 64, 64, 1024));
        var ring = new PolynomialRing<>(BigIntegerDomain.INSTANCE, List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        var source = new SparsePolynomial<>(ring, Map.of(Monomial.of(4), BigInteger.ONE,
            Monomial.of(1), BigInteger.ONE, Monomial.of(0), BigInteger.ONE));
        var request = FactorizationRequest.verifiedDecomposition(source,
            new FactorizationRequest.StructuralLimits(1, 8, 16, 64), 4, 5);
        var observation = probe.observeIntegers(request, FrozenSymPyOperationObservation.Coverage.DECLARED_OPERATIONS);
        assertEquals(FrozenSymPyOperationObservation.Status.WORK_EXHAUSTED, observation.status());
        assertEquals(5, observation.work().totalWorkUnits());
        assertTrue(new ObjectMapper().readTree(observation.receipt()).path("result").isNull());
        var full = probe.observeIntegers(request, FrozenSymPyOperationObservation.Coverage.COMPLETE_BACKEND_WORK);
        assertEquals(FrozenSymPyOperationObservation.Status.UNMEASURED, full.status());
        assertEquals(0, full.work().totalWorkUnits());
        assertEquals("transport.json.loads", new ObjectMapper().readTree(full.receipt()).path("reason").textValue());
    }
}
