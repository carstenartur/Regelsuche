package de.regelsuche.math.sympy;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Factorization comparison over one exactly shared quartic request.
 *
 * <p>Warm embedded methods reuse an already initialized GraalPy context. Cold
 * embedded and CPython methods retain their initialization boundary by design.
 * Backend-only methods exclude the common Regelsuche product verifier;
 * end-to-end methods include it for both implementations.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class SymPyFactorizationBenchmarks {
    private FactorizationRequest<BigInteger> request;
    private BinaryQuarticFactorizationEngine nativeEngine;
    private GraalPySymPyFactorizationEngine<BigInteger> embeddedEngine;
    private ProcessSymPyFactorizationEngine<BigInteger> processEngine;

    @Setup(Level.Trial)
    public void setup() {
        request = request();
        nativeEngine = new BinaryQuarticFactorizationEngine();
        embeddedEngine = GraalPySymPyFactorizationEngine.integers();
        processEngine = ProcessSymPyFactorizationEngine.integers(
            ProcessSymPyFactorizationEngine
                .configuredPythonExecutable());
        FactorizationVerifier.Report<BigInteger> warmup =
            FactorizationVerifier.execute(embeddedEngine, request);
        if (!warmup.successful()) {
            throw new IllegalStateException(
                "embedded SymPy warmup failed: " + warmup);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        embeddedEngine.close();
    }

    @Benchmark
    public String nativeBackendWarm() {
        return nativeEngine.propose(request).engineResultHash();
    }

    @Benchmark
    public String nativeEndToEndWarm() {
        return FactorizationVerifier.execute(
            nativeEngine,
            request).verificationHash();
    }

    @Benchmark
    public String graalPyBackendWarm() {
        return embeddedEngine.propose(request).engineResultHash();
    }

    @Benchmark
    public String graalPyEndToEndWarm() {
        return FactorizationVerifier.execute(
            embeddedEngine,
            request).verificationHash();
    }

    @Benchmark
    public String graalPyEndToEndCold() {
        try (GraalPySymPyFactorizationEngine<BigInteger> cold =
                GraalPySymPyFactorizationEngine.integers()) {
            return FactorizationVerifier.execute(
                cold,
                request).verificationHash();
        }
    }

    @Benchmark
    public String cpythonOneShotEndToEnd() {
        return FactorizationVerifier.execute(
            processEngine,
            request).verificationHash();
    }

    private static FactorizationRequest<BigInteger> request() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(
                new PolynomialVariable("A"),
                new PolynomialVariable("B")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(4, 0), BigInteger.ONE,
                Monomial.of(0, 4), BigInteger.valueOf(4)));
        return FactorizationRequest.verifiedDecomposition(
            source,
            new FactorizationRequest.StructuralLimits(
                2,
                4,
                2,
                8),
            8,
            100_000);
    }
}
