package de.regelsuche.polynomial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded independent completeness campaign for exact decompositions.
 *
 * <p>The campaign trusts neither a backend completeness claim nor its
 * irreducibility evidence. It starts only after the enclosing verifier has
 * reconstructed the exact source product. A candidate is complete precisely
 * when its unresolved remainder is one and every distinct nonconstant factor
 * receives an independent original-domain irreducibility certificate.</p>
 */
final class IndependentFactorizationCompleteness {
    static final String METHOD_ID =
        "regelsuche.factorization-completeness/v1";

    private IndependentFactorizationCompleteness() {
    }

    static <C> Result verify(
        FactorizationRequest<C> request,
        List<FactorizationVerifier.VerifiedCandidate<C>> candidates,
        long maxWorkUnits
    ) {
        String requestHash = PolynomialEvidence.sha256(
            request.canonicalMaterial());
        String sourceHash = PolynomialEvidence.sha256(
            request.source().canonicalMaterial());
        Budget work = new Budget(maxWorkUnits);
        ArrayList<CandidateAttempt> attempts = new ArrayList<>();
        String lastInconclusiveDetail =
            "INDEPENDENT_COMPLETENESS_REQUIRES_REMAINDER_ONE";
        boolean sawRemainderOne = false;

        for (int candidateIndex = 0;
                candidateIndex < candidates.size();
                candidateIndex++) {
            FactorizationVerifier.VerifiedCandidate<C> candidate =
                candidates.get(candidateIndex);
            CandidateEvaluation evaluation = verifyCandidate(
                request,
                candidate,
                candidateIndex,
                work);
            if (evaluation.attempt() != null) {
                attempts.add(evaluation.attempt());
            }
            if (evaluation.outcome() == Outcome.CERTIFIED) {
                return result(
                    requestHash,
                    sourceHash,
                    Outcome.CERTIFIED,
                    evaluation.detailCode(),
                    attempts,
                    candidateIndex,
                    candidate.verificationCertificateHash(),
                    work.ledger());
            }
            if (evaluation.outcome()
                    == Outcome.NO_REMAINDER_ONE_CANDIDATE) {
                continue;
            }
            if (evaluation.outcome()
                    == Outcome.FACTOR_CERTIFICATE_INCONCLUSIVE) {
                sawRemainderOne = true;
                lastInconclusiveDetail = evaluation.detailCode();
                continue;
            }
            return result(
                requestHash,
                sourceHash,
                evaluation.outcome(),
                evaluation.detailCode(),
                attempts,
                -1,
                "",
                work.ledger());
        }

        return result(
            requestHash,
            sourceHash,
            sawRemainderOne
                ? Outcome.FACTOR_CERTIFICATE_INCONCLUSIVE
                : Outcome.NO_REMAINDER_ONE_CANDIDATE,
            sawRemainderOne
                ? lastInconclusiveDetail
                : "INDEPENDENT_COMPLETENESS_REQUIRES_REMAINDER_ONE",
            attempts,
            -1,
            "",
            work.ledger());
    }

    private static <C> CandidateEvaluation verifyCandidate(
        FactorizationRequest<C> request,
        FactorizationVerifier.VerifiedCandidate<C> candidate,
        int candidateIndex,
        Budget work
    ) {
        long workAtStart = work.total();
        if (!work.consume(
                "independent-completeness.candidate-checks",
                1)) {
            return new CandidateEvaluation(
                null,
                Outcome.WORK_BUDGET_EXHAUSTED,
                "INDEPENDENT_COMPLETENESS_WORK_BUDGET_EXCEEDED");
        }
        if (!candidate.unresolvedRemainder().isOne()) {
            return new CandidateEvaluation(
                candidateAttempt(
                    candidate,
                    candidateIndex,
                    false,
                    List.of(),
                    CandidateOutcome.REMAINDER_NOT_ONE,
                    "INDEPENDENT_COMPLETENESS_REMAINDER_NOT_ONE",
                    work.total() - workAtStart),
                Outcome.NO_REMAINDER_ONE_CANDIDATE,
                "INDEPENDENT_COMPLETENESS_REMAINDER_NOT_ONE");
        }

        ArrayList<FactorAttempt> factorAttempts = new ArrayList<>();
        for (int factorIndex = 0;
                factorIndex < candidate.factors().size();
                factorIndex++) {
            PolynomialFactor<C> factor =
                candidate.factors().get(factorIndex);
            if (!work.consume(
                    "independent-completeness.factor-dispatches",
                    1)) {
                return failedFactorEvaluation(
                    candidate,
                    candidateIndex,
                    factorAttempts,
                    Outcome.WORK_BUDGET_EXHAUSTED,
                    "INDEPENDENT_COMPLETENESS_WORK_BUDGET_EXCEEDED",
                    work.total() - workAtStart);
            }
            IndependentOriginalDomainIrreducibility.Result evidence =
                IndependentOriginalDomainIrreducibility.verifyFactor(
                    request,
                    factor.polynomial(),
                    work.remaining());
            work.absorb(evidence.work());
            factorAttempts.add(new FactorAttempt(
                factorIndex,
                factor.multiplicity(),
                PolynomialEvidence.sha256(
                    factor.polynomial().canonicalMaterial()),
                evidence));
            if (evidence.outcome()
                    != FactorizationVerifier
                        .IndependentIrreducibilityOutcome.CERTIFIED) {
                return failedFactorEvaluation(
                    candidate,
                    candidateIndex,
                    factorAttempts,
                    terminalOutcome(evidence.outcome()),
                    evidence.detailCode(),
                    work.total() - workAtStart);
            }
        }
        String detail =
            "INDEPENDENT_FACTORIZATION_COMPLETENESS_CERTIFIED";
        return new CandidateEvaluation(
            candidateAttempt(
                candidate,
                candidateIndex,
                true,
                factorAttempts,
                CandidateOutcome.CERTIFIED,
                detail,
                work.total() - workAtStart),
            Outcome.CERTIFIED,
            detail);
    }

    private static <C> CandidateEvaluation failedFactorEvaluation(
        FactorizationVerifier.VerifiedCandidate<C> candidate,
        int candidateIndex,
        List<FactorAttempt> factorAttempts,
        Outcome outcome,
        String detailCode,
        long workUnits
    ) {
        return new CandidateEvaluation(
            candidateAttempt(
                candidate,
                candidateIndex,
                true,
                factorAttempts,
                CandidateOutcome.FACTOR_NOT_CERTIFIED,
                detailCode,
                workUnits),
            outcome,
            detailCode);
    }

    private static CandidateAttempt candidateAttempt(
        FactorizationVerifier.VerifiedCandidate<?> candidate,
        int candidateIndex,
        boolean remainderOne,
        List<FactorAttempt> factorAttempts,
        CandidateOutcome outcome,
        String detailCode,
        long workUnits
    ) {
        return new CandidateAttempt(
            candidateIndex,
            candidate.verificationCertificateHash(),
            remainderOne,
            candidate.factors().size(),
            factorAttempts,
            outcome,
            detailCode,
            workUnits);
    }

    private static Outcome terminalOutcome(
        FactorizationVerifier.IndependentIrreducibilityOutcome outcome
    ) {
        return switch (outcome) {
            case CERTIFIED -> throw new IllegalArgumentException(
                "certified factor is not a terminal failure");
            case NO_WITNESS_WITHIN_POLICY,
                    DEGREE_LIMIT_EXCEEDED,
                    NORMALIZATION_LIMIT_EXCEEDED ->
                Outcome.FACTOR_CERTIFICATE_INCONCLUSIVE;
            case WORK_BUDGET_EXHAUSTED ->
                Outcome.WORK_BUDGET_EXHAUSTED;
            case UNSUPPORTED_DOMAIN -> Outcome.UNSUPPORTED_DOMAIN;
            case UNSUPPORTED_SHAPE -> Outcome.UNSUPPORTED_SHAPE;
            case TECHNICAL_FAILURE -> Outcome.TECHNICAL_FAILURE;
        };
    }

    private static Result result(
        String requestHash,
        String sourceHash,
        Outcome outcome,
        String detailCode,
        List<CandidateAttempt> attempts,
        int selectedCandidateIndex,
        String selectedCandidateCertificateHash,
        PolynomialWorkLedger work
    ) {
        return new Result(
            METHOD_ID,
            requestHash,
            sourceHash,
            outcome,
            detailCode,
            List.copyOf(attempts),
            selectedCandidateIndex,
            selectedCandidateCertificateHash,
            work);
    }

    enum Outcome {
        CERTIFIED,
        NO_REMAINDER_ONE_CANDIDATE,
        FACTOR_CERTIFICATE_INCONCLUSIVE,
        WORK_BUDGET_EXHAUSTED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        TECHNICAL_FAILURE
    }

    enum CandidateOutcome {
        CERTIFIED,
        REMAINDER_NOT_ONE,
        FACTOR_NOT_CERTIFIED
    }

    private record CandidateEvaluation(
        CandidateAttempt attempt,
        Outcome outcome,
        String detailCode
    ) {
    }

    record FactorAttempt(
        int factorIndex,
        int multiplicity,
        String factorHash,
        IndependentOriginalDomainIrreducibility.Result evidence
    ) {
    }

    record CandidateAttempt(
        int candidateIndex,
        String candidateCertificateHash,
        boolean remainderOne,
        int factorCount,
        List<FactorAttempt> factorAttempts,
        CandidateOutcome outcome,
        String detailCode,
        long workUnits
    ) {
        CandidateAttempt {
            factorAttempts = List.copyOf(factorAttempts);
        }
    }

    record Result(
        String methodId,
        String requestHash,
        String sourceHash,
        Outcome outcome,
        String detailCode,
        List<CandidateAttempt> candidateAttempts,
        int selectedCandidateIndex,
        String selectedCandidateCertificateHash,
        PolynomialWorkLedger work
    ) {
    }

    private static final class Budget {
        private final long limit;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private long total;

        private Budget(long limit) {
            if (limit < 0) {
                throw new IllegalArgumentException(
                    "independent completeness budget must not be negative");
            }
            this.limit = limit;
        }

        private boolean consume(String stage, long units) {
            if (units < 0 || total > limit - units) {
                return false;
            }
            if (units == 0) {
                return true;
            }
            total += units;
            stages.merge(stage, units, Math::addExact);
            return true;
        }

        private void absorb(PolynomialWorkLedger additional) {
            if (!additional.within(remaining())) {
                throw new IllegalStateException(
                    "independent factor verifier exceeded shared budget");
            }
            additional.stages().forEach((stage, units) -> {
                if (!consume(stage, units)) {
                    throw new IllegalStateException(
                        "independent factor verifier exceeded shared budget");
                }
            });
        }

        private long remaining() {
            return limit - total;
        }

        private long total() {
            return total;
        }

        private PolynomialWorkLedger ledger() {
            return new PolynomialWorkLedger(stages);
        }
    }
}
