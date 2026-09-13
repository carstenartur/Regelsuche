package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.*;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Real-engine regression checks; executable locally and through JUnit. */
final class NativeFactorizationContractChecks {
    private static final long WORK = 20_000_000;
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final PolynomialRing<ExactRational> Q = new PolynomialRing<>(
        ExactRationalField.INSTANCE, List.of(new PolynomialVariable("x")),
        PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private static final PolynomialRing<BigInteger> Z = new PolynomialRing<>(
        BigIntegerDomain.INSTANCE, List.of(new PolynomialVariable("x")),
        PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    private NativeFactorizationContractChecks() { }

    static void runningTotalRetainsEveryStageAndBudgetBoundary() {
        PolynomialWorkBudget work = new PolynomialWorkBudget(Long.MAX_VALUE);
        require(total(work) == 0, "initial total");
        work.consume("b", 3); work.consume("a", 4);
        PolynomialWorkLedger snapshot = work.ledger();
        String material = snapshot.canonicalMaterial();
        require(total(work) == 7, "accumulated total");
        work.consume("b", 5); work.consume("zero", 0);
        require(total(work) == 12, "updates and zero work");
        require(snapshot.totalWorkUnits() == 7 && material.equals(snapshot.canonicalMaterial()),
            "old evidence snapshot must remain immutable");
        require(work.ledger().stages().equals(Map.of("a", 4L, "b", 8L)), "complete stage ledger");
        rejects(UnsupportedOperationException.class, () -> snapshot.stages().put("x", 1L));
        rejects(IllegalArgumentException.class, () -> work.consume("", 1));
        rejects(IllegalArgumentException.class, () -> work.consume("a", -1));
        require(total(work) == 12, "invalid entries must not mutate work");
        work.consume("last", Long.MAX_VALUE - 12);
        require(total(work) == Long.MAX_VALUE, "exact ceiling");
        String full = work.ledger().canonicalMaterial();
        rejects(PolynomialWorkBudget.LimitReached.class, () -> work.consume("overflow", 1));
        require(total(work) == Long.MAX_VALUE && full.equals(work.ledger().canonicalMaterial()),
            "rejected overspend must not alter total or stage evidence");
    }

    // Reflection allows the new contract to fail as an assertion on the old implementation.
    private static long total(PolynomialWorkBudget work) {
        try {
            return (Long) PolynomialWorkBudget.class.getDeclaredMethod("totalWorkUnits").invoke(work);
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError("budget must expose the maintained total without constructing a ledger", missing);
        }
    }

    static void rationalConstantsHaveVerifiedEmptyFactorizations() {
        for (String text : List.of("1", "-1", "7", "-3/5")) {
            ExactRational constant = ExactRational.fromCanonicalText(text);
            var source = SparsePolynomial.constant(Q, constant);
            var request = FactorizationRequest.independentComplete(source, limits(16), 250_000, WORK);
            var engine = NativeUnivariateFactorizationEngine.boundedRationals();
            var raw = engine.propose(request);
            require(raw.outcome() == FactorizationEngine.Outcome.CANDIDATES,
                "constant " + text + " must produce a decomposition, got " + raw.outcome());
            require(raw.backendClaim() == FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION,
                "constant is complete, not irreducible");
            require(raw.proposals().size() == 1 && raw.proposals().getFirst().factors().isEmpty(), "empty factors");
            require(raw.proposals().getFirst().unit().equals(constant), "exact scalar content");
            require(raw.proposals().getFirst().unresolvedRemainder().isOne(), "unit remainder");
            var report = FactorizationVerifier.execute(engine, request);
            require(report.status() == FactorizationVerifier.Status.COMPLETE_FACTORIZATION, report.toString());
            require(report.claimStrength() == FactorizationVerifier.ClaimStrength.INDEPENDENTLY_CERTIFIED_COMPLETE,
                "must retain independent completeness authority");
            var trace = report.independentCompletenessTrace().orElseThrow();
            require(trace.certified() && trace.candidateAttempts().size() == 1, "retained candidate trace");
            var attempt = trace.candidateAttempts().getFirst();
            require(attempt.factorCount() == 0 && attempt.factorAttempts().isEmpty(),
                "no invented irreducibility witnesses for an empty factorization");
            require(report.work().units("verify.product-comparisons") == 1, "product equality is still checked");
            require(report.work().units("independent-completeness.candidate-checks") == 1,
                "completeness check remains charged");
            require(raw.engineResultHash().equals(engine.propose(request).engineResultHash()), "deterministic raw evidence");
            require(report.verificationHash().equals(FactorizationVerifier.execute(engine, request).verificationHash()),
                "deterministic independent evidence");
            var rendered = new ExactFactorizationExpressionRenderer().render(report.candidates().getFirst());
            require(rendered.rendered(), "constant must render: " + rendered);
            String normalized = rendered.expression().orElseThrow().replace(" ", "");
            require(normalized.equals(text) || normalized.equals("(" + text + ")"),
                "exact scalar rendering: " + rendered.expression());
            require(rendered.work().totalWorkUnits() > 0, "rendering must remain charged");
        }
    }

    static void integerConstantsRetainContentRatherThanClaimIntegerPrimality() {
        for (long n : new long[] {1, -1, 7, -12}) {
            var source = SparsePolynomial.constant(Z, BigInteger.valueOf(n));
            var request = FactorizationRequest.independentComplete(source, limits(16), 1, WORK);
            var report = FactorizationVerifier.execute(NativeUnivariateFactorizationEngine.boundedIntegers(), request);
            require(report.status() == FactorizationVerifier.Status.COMPLETE_FACTORIZATION, "integer content: " + report);
            require(report.candidates().getFirst().unit().equals(BigInteger.valueOf(n)), "integer scalar content");
            require(report.candidates().getFirst().factors().isEmpty(), "no fabricated nonconstant factors");
            require(report.independentIrreducibilityTrace().isEmpty(), "integer primality is not being claimed");
        }
    }

    static void unitOnlyProposalsRemainFailClosed() {
        var one = SparsePolynomial.one(Q);
        var nonconstant = UnivariatePolynomialView.of(Q, List.of(ExactRational.ONE, ExactRational.ONE)).toSparsePolynomial();
        rejects(IllegalArgumentException.class, () -> new FactorizationEngine.Proposal<>(ExactRational.ZERO, List.of(), one, HASH));
        rejects(IllegalArgumentException.class, () -> new FactorizationEngine.Proposal<>(ExactRational.ONE, List.of(), nonconstant, HASH));
        rejects(IllegalArgumentException.class, () -> new FactorizationEngine.Proposal<>(ExactRational.ONE, List.of(), one, "bad"));
        for (int prime : new int[] {2, 101}) {
            var fieldRing = new PolynomialRing<BigInteger>(PrimeField.of(prime),
                List.of(new PolynomialVariable("x")), PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
            var fieldOne = SparsePolynomial.one(fieldRing);
            rejects(IllegalArgumentException.class, () -> new FactorizationEngine.Proposal<>(
                BigInteger.ONE, List.of(), fieldOne, HASH));
            var linear = UnivariatePolynomialView.of(fieldRing,
                List.of(BigInteger.ONE, BigInteger.ONE)).toSparsePolynomial();
            var ordinary = new FactorizationEngine.Proposal<>(BigInteger.ONE,
                List.of(new PolynomialFactor<>(linear, 1)), fieldOne, HASH);
            require(ordinary.factors().size() == 1, "nonempty finite-field proposals remain supported");
        }
        var proposal = new FactorizationEngine.Proposal<>(ExactRational.integer(BigInteger.valueOf(7)), List.of(), one, HASH);
        FactorizationEngine<ExactRational> forged = new FactorizationEngine<>() {
            public String engineId() { return "test.unit-proposal/v1"; }
            public String coefficientDomainId() { return ExactRationalField.DOMAIN_ID; }
            public EngineResult<ExactRational> propose(FactorizationRequest<ExactRational> request) {
                return new EngineResult<>(engineId(), Outcome.CANDIDATES, "UNTRUSTED_UNIT", PolynomialWorkLedger.empty(),
                    List.of(proposal), BackendClaim.COMPLETE_FACTORIZATION, HASH);
            }
        };
        for (var source : List.of(nonconstant, SparsePolynomial.constant(Q, ExactRational.ONE))) {
            var report = FactorizationVerifier.execute(forged,
                FactorizationRequest.independentComplete(source, limits(16), 1, WORK));
            require(report.status() == FactorizationVerifier.Status.TECHNICAL_FAILURE, "forged constant accepted");
            require(report.detailCode().equals("FACTORIZATION_PROPOSAL_PRODUCT_MISMATCH"), "exact rejection");
        }
        rejects(IllegalArgumentException.class, () -> FactorizationRequest.verifiedDecomposition(
            SparsePolynomial.zero(Q), limits(16), 1, WORK));
    }

    static void constantBudgetsCannotBeBypassed() {
        var source = SparsePolynomial.constant(Q, ExactRational.integer(BigInteger.valueOf(7)));
        var engine = NativeUnivariateFactorizationEngine.boundedRationals();
        var zeroCandidates = engine.propose(FactorizationRequest.verifiedDecomposition(source, limits(16), 0, WORK));
        require(zeroCandidates.outcome() == FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE, "zero candidate budget");
        require(zeroCandidates.proposals().isEmpty() && zeroCandidates.backendClaim() == FactorizationEngine.BackendClaim.NONE,
            "no success after rejected admission");
        var noWork = engine.propose(FactorizationRequest.verifiedDecomposition(source, limits(16), 1, 1));
        require(noWork.outcome() == FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE, "normalization work budget");
        require(noWork.work().totalWorkUnits() <= 1, "work ceiling");
        var raw = engine.propose(FactorizationRequest.verifiedDecomposition(source, limits(16), 1, WORK));
        var noVerificationWork = FactorizationVerifier.execute(engine,
            FactorizationRequest.independentComplete(source, limits(16), 1, raw.work().totalWorkUnits()));
        require(noVerificationWork.status() == FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            "independent verification cannot be free");
    }

    static void explicitDegreeBudgetEnablesTheExistingAlgorithm() {
        var source = SparsePolynomial.one(Q);
        for (int k = 1; k <= 17; k++) {
            source = source.multiply(UnivariatePolynomialView.of(Q,
                List.of(ExactRational.integer(BigInteger.valueOf(-k)), ExactRational.ONE)).toSparsePolynomial());
        }
        var engine = NativeUnivariateFactorizationEngine.boundedRationals();
        var bounded = engine.propose(FactorizationRequest.verifiedDecomposition(source, limits(16), 250_000, WORK));
        require(bounded.outcome() == FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE, "explicit degree 16 stays enforced");
        require(bounded.detailCode().equals("MAX_TOTAL_DEGREE_EXCEEDED"), "degree diagnostic");
        var report = FactorizationVerifier.execute(engine,
            FactorizationRequest.independentComplete(source, limits(32), 250_000, WORK));
        require(report.status() == FactorizationVerifier.Status.COMPLETE_FACTORIZATION, report.toString());
        require(report.candidates().getFirst().factors().size() == 17, "seventeen exact factors");
        require(report.independentCompletenessTrace().orElseThrow().candidateAttempts().getFirst().factorCount() == 17,
            "retain all seventeen independent factor checks");
        require(report.work().totalWorkUnits() <= WORK, "unchanged work bound");
    }

    private static FactorizationRequest.StructuralLimits limits(int degree) {
        return new FactorizationRequest.StructuralLimits(1, degree, 128, 4096);
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable ex) {
            if (type.isInstance(ex)) return;
            throw new AssertionError("expected " + type.getName() + ", got " + ex, ex);
        }
        throw new AssertionError("expected rejection: " + type.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String[] names = {"runningTotal", "rationalConstants", "integerConstants", "proposalGuards", "budgets", "degree"};
        Runnable[] checks = {NativeFactorizationContractChecks::runningTotalRetainsEveryStageAndBudgetBoundary,
            NativeFactorizationContractChecks::rationalConstantsHaveVerifiedEmptyFactorizations,
            NativeFactorizationContractChecks::integerConstantsRetainContentRatherThanClaimIntegerPrimality,
            NativeFactorizationContractChecks::unitOnlyProposalsRemainFailClosed,
            NativeFactorizationContractChecks::constantBudgetsCannotBeBypassed,
            NativeFactorizationContractChecks::explicitDegreeBudgetEnablesTheExistingAlgorithm};
        int failures = 0;
        for (int i = 0; i < checks.length; i++) {
            try { checks[i].run(); System.out.println("PASS " + names[i]); }
            catch (Throwable ex) { failures++; System.out.println("FAIL " + names[i] + ": " + ex); ex.printStackTrace(System.out); }
        }
        if (failures != 0) throw new AssertionError(failures + " contract checks failed");
        System.out.println("6 native factorization contract checks passed");
    }
}
