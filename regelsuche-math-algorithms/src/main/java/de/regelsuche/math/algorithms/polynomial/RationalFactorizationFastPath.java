package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Exact inexpensive proposals before the general bounded rational engine. */
final class RationalFactorizationFastPath implements FactorizationEngine<ExactRational> {
    private static final String ENGINE_ID = "regelsuche.factorization.native-univariate-rational-fast-path/v1";
    private static final ExactRational TWO = ExactRational.integer(2);
    private static final ExactRational FOUR = ExactRational.integer(4);
    private static final int MAX_FAST_DEGREE = 64;
    private static final int MAX_FAST_COEFFICIENT_BITS = 256;

    private final NativeUnivariateFactorizationPolicy policy;
    private final NativeUnivariateFactorizationEngine<ExactRational> fallback;

    RationalFactorizationFastPath(NativeUnivariateFactorizationPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
        this.fallback = NativeUnivariateFactorizationEngine.rationals(policy);
    }

    @Override public String engineId() { return ENGINE_ID; }
    @Override public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }

    @Override
    public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
        Objects.requireNonNull(request);
        var work = new PolynomialWorkBudget(request.maxWorkUnits());
        var source = request.source();
        try {
            if (request.evidenceRequirement() == FactorizationRequest.EvidenceRequirement.VERIFIED_DECOMPOSITION
                    && request.maxCandidates() > 0 && source.ring().coefficientDomain() == ExactRationalField.INSTANCE
                    && source.ring().variableCount() == 1 && request.structuralViolation().isEmpty()
                    && source.maxCoefficientBitLength() <= MAX_FAST_COEFFICIENT_BITS) {
                var result = fastCandidate(source, work);
                if (result != null && result.factors().stream()
                        .allMatch(factor -> request.structuralLimits().firstViolation(factor.polynomial()).isEmpty())) {
                    return candidate(request, result, work);
                }
            }
            long remaining = request.maxWorkUnits() - work.totalWorkUnits();
            if (remaining == 0) throw new PolynomialWorkBudget.LimitReached();
            var ordinary = fallback.propose(new FactorizationRequest<>(source, request.evidenceRequirement(),
                request.structuralLimits(), request.maxCandidates(), remaining));
            ordinary.work().stages().forEach(work::consume);
            return NativeEngineEvidence.result(request, policy, ENGINE_ID, ordinary.outcome(), ordinary.detailCode(),
                work.ledger(), ordinary.proposals(), ordinary.backendClaim(), List.of(ordinary.engineResultHash()));
        } catch (PolynomialWorkBudget.LimitReached exhausted) {
            return NativeEngineEvidence.result(request, policy, ENGINE_ID, Outcome.BUDGET_INCONCLUSIVE,
                "NATIVE_RATIONAL_FAST_PATH_WORK_EXHAUSTED", work.ledger(), List.of(), BackendClaim.NONE, List.of());
        }
    }

    private EngineResult<ExactRational> candidate(FactorizationRequest<ExactRational> request,
            Candidate result, PolynomialWorkBudget work) {
        var ledger = work.ledger();
        String certificate = NativeEngineEvidence.proposalCertificate(request, policy, ENGINE_ID,
            result.unit(), result.factors(), ledger, List.of());
        var proposal = new Proposal<>(result.unit(), result.factors(),
            SparsePolynomial.one(request.source().ring()), certificate);
        return NativeEngineEvidence.result(request, policy, ENGINE_ID, Outcome.CANDIDATES,
            "NATIVE_RATIONAL_FAST_FACTOR_PROPOSAL", ledger, List.of(proposal), BackendClaim.NONE, List.of());
    }

    private static Candidate fastCandidate(SparsePolynomial<ExactRational> source, PolynomialWorkBudget work) {
        int degree = source.degree(0);
        if (degree < 2 || degree > MAX_FAST_DEGREE) return null;
        int spacing = quadraticSpacing(source, degree, work);
        return spacing == 0 ? unitRoot(source, degree, work) : quadratic(source, spacing, work);
    }

    private static int quadraticSpacing(SparsePolynomial<ExactRational> source, int degree, PolynomialWorkBudget work) {
        if (degree == 2) return 1;
        if ((degree & 1) != 0 || source.termCount() > 3) return 0;
        int spacing = degree / 2;
        for (Monomial monomial : source.terms().keySet()) {
            work.consume("native-rational-fast.quadratic-shape-terms", 1);
            if (monomial.exponent(0) % spacing != 0) return 0;
        }
        return spacing;
    }

    private static Candidate quadratic(SparsePolynomial<ExactRational> source, int spacing, PolynomialWorkBudget work) {
        work.consume("native-rational-fast.quadratic-discriminant", 8);
        ExactRational a = source.coefficient(2 * spacing);
        ExactRational b = source.coefficient(spacing);
        ExactRational c = source.coefficient(0);
        var squareRoot = b.multiply(b).subtract(FOUR.multiply(a).multiply(c)).sqrtExact();
        if (squareRoot.isEmpty()) return null;
        work.consume("native-rational-fast.quadratic-factors", 10);
        var root = squareRoot.orElseThrow();
        var denominator = TWO.multiply(a);
        var first = monicBinomial(source.ring(), spacing, b.subtract(root).divide(denominator));
        var second = monicBinomial(source.ring(), spacing, b.add(root).divide(denominator));
        return new Candidate(a, List.of(new PolynomialFactor<>(first, 1), new PolynomialFactor<>(second, 1)));
    }

    private static Candidate unitRoot(SparsePolynomial<ExactRational> source, int degree, PolynomialWorkBudget work) {
        ExactRational atOne = ExactRational.ZERO;
        ExactRational atMinusOne = ExactRational.ZERO;
        for (var term : source.terms().entrySet()) {
            work.consume("native-rational-fast.unit-root-tests", 3);
            var coefficient = term.getValue();
            atOne = atOne.add(coefficient);
            atMinusOne = (term.getKey().exponent(0) & 1) == 0
                ? atMinusOne.add(coefficient) : atMinusOne.subtract(coefficient);
        }
        if (!atOne.isZero() && !atMinusOne.isZero()) return null;
        boolean positiveRoot = atOne.isZero();
        var quotient = new TreeMap<Monomial, ExactRational>(source.ring().monomialComparator());
        ExactRational value = ExactRational.ZERO;
        for (int exponent = degree; exponent >= 1; exponent--) {
            work.consume("native-rational-fast.synthetic-division", 3);
            ExactRational coefficient = source.coefficient(exponent);
            value = positiveRoot ? coefficient.add(value) : coefficient.subtract(value);
            if (!value.isZero()) quotient.put(Monomial.of(exponent - 1), value);
        }
        var linear = monicBinomial(source.ring(), 1, positiveRoot ? ExactRational.NEGATIVE_ONE : ExactRational.ONE);
        return new Candidate(ExactRational.ONE, List.of(new PolynomialFactor<>(linear, 1),
            new PolynomialFactor<>(new SparsePolynomial<>(source.ring(), quotient), 1)));
    }

    private static SparsePolynomial<ExactRational> monicBinomial(PolynomialRing<ExactRational> ring,
            int exponent, ExactRational constant) {
        return new SparsePolynomial<>(ring, Map.of(Monomial.of(exponent), ExactRational.ONE, Monomial.of(0), constant));
    }

    private record Candidate(ExactRational unit, List<PolynomialFactor<ExactRational>> factors) { }
}
