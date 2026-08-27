package de.regelsuche.math.sympy;

import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Capability-matched warm comparison of the general native and SymPy
 * univariate factorization engines.
 *
 * <p>The frozen corpus contains the same exact {@code Z[x]} and {@code Q[x]}
 * requests for both engines. Backend tracks exclude the common verifier;
 * end-to-end tracks include the same {@link FactorizationVerifier}. The older
 * binary-quartic benchmark remains a separately labelled specialist control.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
public class GeneralUnivariateFactorizationBenchmarks {
    private static final String[] CASE_IDS = {
        "z-linear-pair-degree2",
        "z-content-mixed-degree4",
        "z-large-coefficient-degree4",
        "z-eisenstein-irreducible-degree5",
        "z-repeated-degree6",
        "z-sparse-cyclotomic-degree6",
        "q-linear-pair-degree2",
        "q-eisenstein-irreducible-degree4",
        "q-repeated-degree5"
    };
    private static final Map<String, CaseSpec> CASES = cases();

    @State(Scope.Benchmark)
    public static class NativeGeneralState {
        @Param({
            "z-linear-pair-degree2",
            "z-content-mixed-degree4",
            "z-large-coefficient-degree4",
            "z-eisenstein-irreducible-degree5",
            "z-repeated-degree6",
            "z-sparse-cyclotomic-degree6",
            "q-linear-pair-degree2",
            "q-eisenstein-irreducible-degree4",
            "q-repeated-degree5"
        })
        public String caseId;

        private Invocation invocation;

        @Setup(Level.Trial)
        public void setup() {
            CaseSpec specification = specification(caseId);
            invocation = specification.nativeFactory().get();
            qualify(specification, invocation.verify());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            invocation.close();
        }
    }

    @State(Scope.Benchmark)
    public static class GraalPyGeneralState {
        @Param({
            "z-linear-pair-degree2",
            "z-content-mixed-degree4",
            "z-large-coefficient-degree4",
            "z-eisenstein-irreducible-degree5",
            "z-repeated-degree6",
            "z-sparse-cyclotomic-degree6",
            "q-linear-pair-degree2",
            "q-eisenstein-irreducible-degree4",
            "q-repeated-degree5"
        })
        public String caseId;

        private Invocation invocation;

        @Setup(Level.Trial)
        public void setup() {
            CaseSpec specification = specification(caseId);
            invocation = specification.graalPyFactory().get();
            qualify(specification, invocation.verify());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            invocation.close();
        }
    }

    @Benchmark
    public String nativeGeneralBackendWarm(NativeGeneralState state) {
        return state.invocation.backend();
    }

    @Benchmark
    public String nativeGeneralEndToEndWarm(NativeGeneralState state) {
        return state.invocation.endToEnd();
    }

    @Benchmark
    public String graalPyGeneralBackendWarm(GraalPyGeneralState state) {
        return state.invocation.backend();
    }

    @Benchmark
    public String graalPyGeneralEndToEndWarm(GraalPyGeneralState state) {
        return state.invocation.endToEnd();
    }

    private static CaseSpec specification(String caseId) {
        CaseSpec result = CASES.get(caseId);
        if (result == null) {
            throw new IllegalArgumentException(
                "unknown general factorization case: " + caseId);
        }
        return result;
    }

    private static void qualify(
        CaseSpec specification,
        FactorizationVerifier.Report<?> report
    ) {
        if (specification.reducible()) {
            boolean valid = report.successful()
                && report.status()
                    == FactorizationVerifier.Status.PARTIAL_FACTORIZATION
                && report.claimStrength()
                    == FactorizationVerifier.ClaimStrength
                        .BACKEND_CLAIMED_COMPLETE
                && report.candidates().size() == 1
                && report.candidates().getFirst().factors().size()
                    == specification.expectedDistinctFactors();
            if (!valid) {
                throw new IllegalStateException(
                    "general factorization qualification failed for "
                        + specification.id() + ": " + report);
            }
            return;
        }
        boolean valid = !report.successful()
            && report.status()
                == FactorizationVerifier.Status.NO_FACTORIZATION_FOUND
            && report.claimStrength()
                == FactorizationVerifier.ClaimStrength
                    .BACKEND_CLAIMED_IRREDUCIBLE
            && report.candidates().isEmpty();
        if (!valid) {
            throw new IllegalStateException(
                "general irreducibility qualification failed for "
                    + specification.id() + ": " + report);
        }
    }

    private static Map<String, CaseSpec> cases() {
        PolynomialRing<BigInteger> integerRing = integerRing();
        PolynomialRing<ExactRational> rationalRing = rationalRing();
        Map<String, CaseSpec> result = new LinkedHashMap<>();

        SparsePolynomial<BigInteger> xMinusOne =
            integer(integerRing, -1, 1);
        SparsePolynomial<BigInteger> xPlusTwo =
            integer(integerRing, 2, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            integer(integerRing, 1, 0, 1);

        add(result, integerCase(
            "z-linear-pair-degree2",
            xMinusOne.multiply(xPlusTwo),
            true,
            2));
        add(result, integerCase(
            "z-content-mixed-degree4",
            xMinusOne.multiply(xPlusTwo)
                .multiply(xSquaredPlusOne)
                .scale(BigInteger.valueOf(6)),
            true,
            3));
        add(result, integerCase(
            "z-large-coefficient-degree4",
            integer(integerRing, 1, 101, 1)
                .multiply(integer(integerRing, 3, -97, 1)),
            true,
            2));
        add(result, integerCase(
            "z-eisenstein-irreducible-degree5",
            integer(integerRing, 2, 2, 0, 0, 0, 1),
            false,
            0));
        add(result, integerCase(
            "z-repeated-degree6",
            xMinusOne.pow(2)
                .multiply(xPlusTwo.pow(2))
                .multiply(xSquaredPlusOne),
            true,
            3));
        add(result, integerCase(
            "z-sparse-cyclotomic-degree6",
            integer(integerRing, -1, 0, 0, 0, 0, 0, 1),
            true,
            4));

        SparsePolynomial<ExactRational> xMinusHalf = rational(
            rationalRing,
            q(-1, 2),
            ExactRational.ONE);
        SparsePolynomial<ExactRational> xPlusThird = rational(
            rationalRing,
            q(1, 3),
            ExactRational.ONE);
        add(result, rationalCase(
            "q-linear-pair-degree2",
            xMinusHalf.multiply(xPlusThird)
                .scale(q(-7, 11)),
            true,
            2));
        add(result, rationalCase(
            "q-eisenstein-irreducible-degree4",
            rational(
                rationalRing,
                q(2, 3),
                q(2, 3),
                ExactRational.ZERO,
                ExactRational.ZERO,
                ExactRational.ONE),
            false,
            0));
        add(result, rationalCase(
            "q-repeated-degree5",
            xMinusHalf.pow(3)
                .multiply(xPlusThird.pow(2))
                .scale(q(-7, 11)),
            true,
            2));

        if (!List.copyOf(result.keySet()).equals(List.of(CASE_IDS))) {
            throw new IllegalStateException(
                "general factorization corpus order is invalid");
        }
        return Map.copyOf(result);
    }

    private static void add(
        Map<String, CaseSpec> target,
        CaseSpec specification
    ) {
        if (target.put(specification.id(), specification) != null) {
            throw new IllegalStateException(
                "duplicate factorization case: " + specification.id());
        }
    }

    private static CaseSpec integerCase(
        String id,
        SparsePolynomial<BigInteger> source,
        boolean reducible,
        int expectedDistinctFactors
    ) {
        FactorizationRequest<BigInteger> request = request(source);
        return new CaseSpec(
            id,
            reducible,
            expectedDistinctFactors,
            () -> invocation(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                request),
            () -> invocation(
                GraalPySymPyFactorizationEngine.integers(),
                request));
    }

    private static CaseSpec rationalCase(
        String id,
        SparsePolynomial<ExactRational> source,
        boolean reducible,
        int expectedDistinctFactors
    ) {
        FactorizationRequest<ExactRational> request = request(source);
        return new CaseSpec(
            id,
            reducible,
            expectedDistinctFactors,
            () -> invocation(
                NativeUnivariateFactorizationEngine.boundedRationals(),
                request),
            () -> invocation(
                GraalPySymPyFactorizationEngine.rationals(),
                request));
    }

    private static <C> Invocation invocation(
        FactorizationEngine<C> engine,
        FactorizationRequest<C> request
    ) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(request, "request");
        return new Invocation() {
            @Override
            public String backend() {
                return engine.propose(request).engineResultHash();
            }

            @Override
            public String endToEnd() {
                return verify().verificationHash();
            }

            @Override
            public FactorizationVerifier.Report<C> verify() {
                return FactorizationVerifier.execute(engine, request);
            }

            @Override
            public void close() {
                if (engine instanceof AutoCloseable closeable) {
                    try {
                        closeable.close();
                    } catch (Exception exception) {
                        throw new IllegalStateException(
                            "factorization benchmark teardown failed",
                            exception);
                    }
                }
            }
        };
    }

    private static PolynomialRing<BigInteger> integerRing() {
        return new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }

    private static PolynomialRing<ExactRational> rationalRing() {
        return new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }

    private static SparsePolynomial<BigInteger> integer(
        PolynomialRing<BigInteger> ring,
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            ring,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }

    private static SparsePolynomial<ExactRational> rational(
        PolynomialRing<ExactRational> ring,
        ExactRational... coefficients
    ) {
        return UnivariatePolynomialView.of(
            ring,
            List.of(coefficients))
            .toSparsePolynomial();
    }

    private static ExactRational q(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static <C> FactorizationRequest<C> request(
        SparsePolynomial<C> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            new FactorizationRequest.StructuralLimits(
                1,
                16,
                128,
                4_096),
            250_000,
            20_000_000);
    }

    private interface Invocation extends AutoCloseable {
        String backend();

        String endToEnd();

        FactorizationVerifier.Report<?> verify();

        @Override
        void close();
    }

    private record CaseSpec(
        String id,
        boolean reducible,
        int expectedDistinctFactors,
        Supplier<Invocation> nativeFactory,
        Supplier<Invocation> graalPyFactory
    ) {
        private CaseSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(nativeFactory, "nativeFactory");
            Objects.requireNonNull(graalPyFactory, "graalPyFactory");
            if (id.isBlank()
                    || expectedDistinctFactors < 0
                    || reducible != (expectedDistinctFactors > 0)) {
                throw new IllegalArgumentException(
                    "general factorization case is invalid");
            }
        }
    }
}
