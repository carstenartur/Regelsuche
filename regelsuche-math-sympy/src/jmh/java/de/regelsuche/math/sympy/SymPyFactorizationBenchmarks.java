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
 * <p>Each track owns only the runtime state it measures. Native and CPython
 * tracks never initialize GraalPy. Warm embedded tracks reuse one initialized
 * context, while the cold embedded track constructs and closes a complete
 * runtime per operation. Backend-only methods exclude the common Regelsuche
 * product verifier; end-to-end methods include it.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SymPyFactorizationBenchmarks {
    @State(Scope.Benchmark)
    public static class NativeState {
        private FactorizationRequest<BigInteger> request;
        private BinaryQuarticFactorizationEngine engine;

        @Setup(Level.Trial)
        public void setup() {
            request = request();
            engine = new BinaryQuarticFactorizationEngine();
        }
    }

    @State(Scope.Benchmark)
    public static class EmbeddedWarmState {
        private FactorizationRequest<BigInteger> request;
        private GraalPySymPyFactorizationEngine<BigInteger> engine;

        @Setup(Level.Trial)
        public void setup() {
            request = request();
            engine = GraalPySymPyFactorizationEngine.integers();
            FactorizationVerifier.Report<BigInteger> warmup =
                FactorizationVerifier.execute(engine, request);
            if (!warmup.successful()) {
                throw new IllegalStateException(
                    "embedded SymPy warmup failed: " + warmup);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            engine.close();
        }
    }

    @State(Scope.Benchmark)
    public static class EmbeddedColdState {
        private FactorizationRequest<BigInteger> request;

        @Setup(Level.Trial)
        public void setup() {
            request = request();
        }
    }

    @State(Scope.Benchmark)
    public static class ProcessState {
        private FactorizationRequest<BigInteger> request;
        private ProcessSymPyFactorizationEngine<BigInteger> engine;

        @Setup(Level.Trial)
        public void setup() {
            request = request();
            engine = ProcessSymPyFactorizationEngine.integers(
                ProcessSymPyFactorizationEngine
                    .configuredPythonExecutable());
        }
    }

    @Benchmark
    public String nativeBackendWarm(NativeState state) {
        return state.engine.propose(state.request).engineResultHash();
    }

    @Benchmark
    public String nativeEndToEndWarm(NativeState state) {
        return FactorizationVerifier.execute(
            state.engine,
            state.request).verificationHash();
    }

    @Benchmark
    public String graalPyBackendWarm(EmbeddedWarmState state) {
        return state.engine.propose(state.request).engineResultHash();
    }

    @Benchmark
    public String graalPyEndToEndWarm(EmbeddedWarmState state) {
        return FactorizationVerifier.execute(
            state.engine,
            state.request).verificationHash();
    }

    @Benchmark
    public String graalPyEndToEndCold(EmbeddedColdState state) {
        try (GraalPySymPyFactorizationEngine<BigInteger> cold =
                GraalPySymPyFactorizationEngine.integers()) {
            return FactorizationVerifier.execute(
                cold,
                state.request).verificationHash();
        }
    }

    @Benchmark
    public String cpythonOneShotEndToEnd(ProcessState state) {
        return FactorizationVerifier.execute(
            state.engine,
            state.request).verificationHash();
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
