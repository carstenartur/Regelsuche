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

/** Capability-matched warm comparison over one frozen Z[x]/Q[x] corpus. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(3)
@Warmup(iterations = 3, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
public class GeneralUnivariateFactorizationBenchmarks {
    private static final String[] IDS = {
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

    public abstract static class BaseState {
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

        abstract Invocation create(CaseSpec specification);

        @Setup(Level.Trial)
        public void setup() {
            CaseSpec specification = specification(caseId);
            invocation = create(specification);
            qualify(specification, invocation.verify());
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            invocation.close();
        }
    }

    @State(Scope.Benchmark)
    public static class NativeState extends BaseState {
        @Override
        Invocation create(CaseSpec specification) {
            return specification.nativeFactory().get();
        }
    }

    @State(Scope.Benchmark)
    public static class GraalPyState extends BaseState {
        @Override
        Invocation create(CaseSpec specification) {
            return specification.graalPyFactory().get();
        }
    }

    @Benchmark
    public String nativeGeneralBackendWarm(NativeState state) {
        return state.invocation.backend();
    }

    @Benchmark
    public String nativeGeneralEndToEndWarm(NativeState state) {
        return state.invocation.endToEnd();
    }

    @Benchmark
    public String graalPyGeneralBackendWarm(GraalPyState state) {
        return state.invocation.backend();
    }

    @Benchmark
    public String graalPyGeneralEndToEndWarm(GraalPyState state) {
        return state.invocation.endToEnd();
    }

    private static CaseSpec specification(String id) {
        CaseSpec result = CASES.get(id);
        if (result == null) {
            throw new IllegalArgumentException("unknown case: " + id);
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
                    == specification.factorCount();
            require(valid, specification, report);
            return;
        }
        boolean claimed = !report.successful()
            && report.status()
                == FactorizationVerifier.Status.NO_FACTORIZATION_FOUND
            && report.claimStrength()
                == FactorizationVerifier.ClaimStrength
                    .BACKEND_CLAIMED_IRREDUCIBLE
            && report.candidates().isEmpty();
        boolean trivial = report.successful()
            && report.status()
                == FactorizationVerifier.Status.PARTIAL_FACTORIZATION
            && report.claimStrength()
                == FactorizationVerifier.ClaimStrength
                    .BACKEND_CLAIMED_COMPLETE
            && report.candidates().size() == 1
            && report.candidates().getFirst().factors().size() == 1
            && report.candidates().getFirst().factors().getFirst()
                .multiplicity() == 1;
        require(claimed || trivial, specification, report);
    }

    private static void require(
        boolean valid,
        CaseSpec specification,
        FactorizationVerifier.Report<?> report
    ) {
        if (!valid) {
            throw new IllegalStateException(
                "factorization qualification failed for "
                    + specification.id() + ": " + report);
        }
    }

    private static Map<String, CaseSpec> cases() {
        PolynomialRing<BigInteger> z = ring(BigIntegerDomain.INSTANCE);
        PolynomialRing<ExactRational> q = ring(ExactRationalField.INSTANCE);
        SparsePolynomial<BigInteger> zm1 = z(z, -1, 1);
        SparsePolynomial<BigInteger> zp2 = z(z, 2, 1);
        SparsePolynomial<BigInteger> z2p1 = z(z, 1, 0, 1);
        SparsePolynomial<ExactRational> qmh = q(q, r(-1, 2), ExactRational.ONE);
        SparsePolynomial<ExactRational> qpt = q(q, r(1, 3), ExactRational.ONE);
        Map<String, CaseSpec> result = new LinkedHashMap<>();
        add(result, integer("z-linear-pair-degree2", zm1.multiply(zp2), 2));
        add(result, integer(
            "z-content-mixed-degree4",
            zm1.multiply(zp2).multiply(z2p1).scale(BigInteger.valueOf(6)),
            3));
        add(result, integer(
            "z-large-coefficient-degree4",
            z(z, 1, 101, 1).multiply(z(z, 3, -97, 1)),
            2));
        add(result, integer(
            "z-eisenstein-irreducible-degree5",
            z(z, 2, 2, 0, 0, 0, 1),
            0));
        add(result, integer(
            "z-repeated-degree6",
            zm1.pow(2).multiply(zp2.pow(2)).multiply(z2p1),
            3));
        add(result, integer(
            "z-sparse-cyclotomic-degree6",
            z(z, -1, 0, 0, 0, 0, 0, 1),
            4));
        add(result, rational(
            "q-linear-pair-degree2",
            qmh.multiply(qpt).scale(r(-7, 11)),
            2));
        add(result, rational(
            "q-eisenstein-irreducible-degree4",
            q(q, r(2, 3), r(2, 3), ExactRational.ZERO,
                ExactRational.ZERO, ExactRational.ONE),
            0));
        add(result, rational(
            "q-repeated-degree5",
            qmh.pow(3).multiply(qpt.pow(2)).scale(r(-7, 11)),
            2));
        if (!List.copyOf(result.keySet()).equals(List.of(IDS))) {
            throw new IllegalStateException("corpus order is invalid");
        }
        return Map.copyOf(result);
    }

    private static void add(Map<String, CaseSpec> target, CaseSpec value) {
        if (target.put(value.id(), value) != null) {
            throw new IllegalStateException("duplicate case: " + value.id());
        }
    }

    private static CaseSpec integer(
        String id,
        SparsePolynomial<BigInteger> source,
        int factors
    ) {
        FactorizationRequest<BigInteger> request = request(source);
        return spec(
            id,
            factors,
            () -> invocation(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                request),
            () -> invocation(
                GraalPySymPyFactorizationEngine.integers(),
                request));
    }

    private static CaseSpec rational(
        String id,
        SparsePolynomial<ExactRational> source,
        int factors
    ) {
        FactorizationRequest<ExactRational> request = request(source);
        return spec(
            id,
            factors,
            () -> invocation(
                NativeUnivariateFactorizationEngine.boundedRationals(),
                request),
            () -> invocation(
                GraalPySymPyFactorizationEngine.rationals(),
                request));
    }

    private static CaseSpec spec(
        String id,
        int factors,
        Supplier<Invocation> nativeFactory,
        Supplier<Invocation> graalPyFactory
    ) {
        return new CaseSpec(
            id,
            factors > 0,
            factors,
            nativeFactory,
            graalPyFactory);
    }

    private static <C> Invocation invocation(
        FactorizationEngine<C> engine,
        FactorizationRequest<C> request
    ) {
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
                        throw new IllegalStateException(exception);
                    }
                }
            }
        };
    }

    private static <C> PolynomialRing<C> ring(
        de.regelsuche.polynomial.CoefficientDomain<C> domain
    ) {
        return new PolynomialRing<>(
            domain,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    }

    private static SparsePolynomial<BigInteger> z(
        PolynomialRing<BigInteger> ring,
        long... values
    ) {
        return UnivariatePolynomialView.of(
            ring,
            Arrays.stream(values).mapToObj(BigInteger::valueOf).toList())
            .toSparsePolynomial();
    }

    private static SparsePolynomial<ExactRational> q(
        PolynomialRing<ExactRational> ring,
        ExactRational... values
    ) {
        return UnivariatePolynomialView.of(ring, List.of(values))
            .toSparsePolynomial();
    }

    private static ExactRational r(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static <C> FactorizationRequest<C> request(
        SparsePolynomial<C> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            new FactorizationRequest.StructuralLimits(1, 16, 128, 4_096),
            250_000,
            20_000_000);
    }

    private interface Invocation extends AutoCloseable {
        String backend();
        String endToEnd();
        FactorizationVerifier.Report<?> verify();
        @Override void close();
    }

    private record CaseSpec(
        String id,
        boolean reducible,
        int factorCount,
        Supplier<Invocation> nativeFactory,
        Supplier<Invocation> graalPyFactory
    ) {
        private CaseSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(nativeFactory, "nativeFactory");
            Objects.requireNonNull(graalPyFactory, "graalPyFactory");
            if (id.isBlank() || factorCount < 0
                    || reducible != (factorCount > 0)) {
                throw new IllegalArgumentException("invalid case");
            }
        }
    }
}
