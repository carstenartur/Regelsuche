package de.regelsuche.polynomial;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;

/**
 * Verifier-issued proof campaign for candidate factorization completeness.
 *
 * <p>The public nested records are diagnostic values. Only this type's
 * package-private issuer can assemble them into an authority-bearing trace,
 * and only {@link FactorizationVerifier.Report} authorizes the resulting
 * mathematical status.</p>
 */
public final class IndependentCompletenessTrace {
    private final State state;

    private IndependentCompletenessTrace(
        String methodId,
        String requestHash,
        String sourceHash,
        Outcome outcome,
        String detailCode,
        List<CandidateAttempt> candidateAttempts,
        int selectedCandidateIndex,
        String selectedCandidateCertificateHash,
        PolynomialWorkLedger work,
        String traceHash
    ) {
        state = new State(
            methodId,
            requestHash,
            sourceHash,
            outcome,
            detailCode,
            candidateAttempts,
            selectedCandidateIndex,
            selectedCandidateCertificateHash,
            work,
            traceHash);
    }

    static IndependentCompletenessTrace issue(
        IndependentFactorizationCompleteness.Result evidence,
        Function<
            IndependentOriginalDomainIrreducibility.Result,
            FactorizationVerifier.IndependentIrreducibilityTrace
        > irreducibilityTraceIssuer
    ) {
        List<CandidateAttempt> candidateAttempts =
            evidence.candidateAttempts().stream()
                .map(attempt -> issueCandidateAttempt(
                    attempt,
                    irreducibilityTraceIssuer))
                .toList();
        Outcome outcome = Outcome.valueOf(evidence.outcome().name());
        String traceHash = traceHash(
            evidence.methodId(),
            evidence.requestHash(),
            evidence.sourceHash(),
            outcome,
            evidence.detailCode(),
            candidateAttempts,
            evidence.selectedCandidateIndex(),
            evidence.selectedCandidateCertificateHash(),
            evidence.work());
        return new IndependentCompletenessTrace(
            evidence.methodId(),
            evidence.requestHash(),
            evidence.sourceHash(),
            outcome,
            evidence.detailCode(),
            candidateAttempts,
            evidence.selectedCandidateIndex(),
            evidence.selectedCandidateCertificateHash(),
            evidence.work(),
            traceHash);
    }

    private static CandidateAttempt issueCandidateAttempt(
        IndependentFactorizationCompleteness.CandidateAttempt evidence,
        Function<
            IndependentOriginalDomainIrreducibility.Result,
            FactorizationVerifier.IndependentIrreducibilityTrace
        > irreducibilityTraceIssuer
    ) {
        List<FactorAttempt> factorAttempts =
            evidence.factorAttempts().stream()
                .map(attempt -> issueFactorAttempt(
                    attempt,
                    irreducibilityTraceIssuer))
                .toList();
        CandidateOutcome outcome = CandidateOutcome.valueOf(
            evidence.outcome().name());
        String attemptHash = candidateAttemptHash(
            evidence.candidateIndex(),
            evidence.candidateCertificateHash(),
            evidence.remainderOne(),
            evidence.factorCount(),
            factorAttempts,
            outcome,
            evidence.detailCode(),
            evidence.workUnits());
        return new CandidateAttempt(
            evidence.candidateIndex(),
            evidence.candidateCertificateHash(),
            evidence.remainderOne(),
            evidence.factorCount(),
            factorAttempts,
            outcome,
            evidence.detailCode(),
            evidence.workUnits(),
            attemptHash);
    }

    private static FactorAttempt issueFactorAttempt(
        IndependentFactorizationCompleteness.FactorAttempt evidence,
        Function<
            IndependentOriginalDomainIrreducibility.Result,
            FactorizationVerifier.IndependentIrreducibilityTrace
        > irreducibilityTraceIssuer
    ) {
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            irreducibilityTraceIssuer.apply(evidence.evidence());
        String attemptHash = factorAttemptHash(
            evidence.factorIndex(),
            evidence.multiplicity(),
            evidence.factorHash(),
            trace);
        return new FactorAttempt(
            evidence.factorIndex(),
            evidence.multiplicity(),
            evidence.factorHash(),
            trace,
            attemptHash);
    }

    public String methodId() {
        return state.methodId();
    }

    public String requestHash() {
        return state.requestHash();
    }

    public String sourceHash() {
        return state.sourceHash();
    }

    public Outcome outcome() {
        return state.outcome();
    }

    public String detailCode() {
        return state.detailCode();
    }

    public List<CandidateAttempt> candidateAttempts() {
        return state.candidateAttempts();
    }

    public OptionalInt selectedCandidateIndex() {
        return state.selectedCandidateIndex() < 0
            ? OptionalInt.empty()
            : OptionalInt.of(state.selectedCandidateIndex());
    }

    public Optional<String> selectedCandidateCertificateHash() {
        return state.selectedCandidateCertificateHash().isEmpty()
            ? Optional.empty()
            : Optional.of(state.selectedCandidateCertificateHash());
    }

    public PolynomialWorkLedger work() {
        return state.work();
    }

    public String traceHash() {
        return state.traceHash();
    }

    public boolean certified() {
        return outcome() == Outcome.CERTIFIED;
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        PolynomialEvidence.append(result, methodId());
        PolynomialEvidence.append(result, requestHash());
        PolynomialEvidence.append(result, sourceHash());
        PolynomialEvidence.append(result, outcome().name());
        PolynomialEvidence.append(result, detailCode());
        PolynomialEvidence.append(
            result,
            Integer.toString(candidateAttempts().size()));
        candidateAttempts().forEach(attempt -> PolynomialEvidence.append(
            result,
            attempt.canonicalMaterial()));
        PolynomialEvidence.append(
            result,
            Integer.toString(state.selectedCandidateIndex()));
        PolynomialEvidence.append(
            result,
            state.selectedCandidateCertificateHash());
        PolynomialEvidence.append(result, work().canonicalMaterial());
        PolynomialEvidence.append(result, traceHash());
        return result.toString();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof IndependentCompletenessTrace trace
                && state.equals(trace.state);
    }

    @Override
    public int hashCode() {
        return state.hashCode();
    }

    @Override
    public String toString() {
        return "IndependentCompletenessTrace[" + state + ']';
    }

    /** Result of independently checking a decomposition campaign. */
    public enum Outcome {
        CERTIFIED,
        NO_REMAINDER_ONE_CANDIDATE,
        FACTOR_CERTIFICATE_INCONCLUSIVE,
        WORK_BUDGET_EXHAUSTED,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_SHAPE,
        TECHNICAL_FAILURE
    }

    /** Result retained for one exact-product candidate. */
    public enum CandidateOutcome {
        CERTIFIED,
        REMAINDER_NOT_ONE,
        FACTOR_NOT_CERTIFIED
    }

    /** One independently checked factor within a candidate. */
    public record FactorAttempt(
        int factorIndex,
        int multiplicity,
        String factorHash,
        FactorizationVerifier.IndependentIrreducibilityTrace
            irreducibilityTrace,
        String attemptHash
    ) {
        public FactorAttempt {
            require(
                factorIndex >= 0,
                "factor index must not be negative");
            require(
                multiplicity >= 1,
                "factor multiplicity must be positive");
            require(validHash(factorHash), "factor hash is invalid");
            require(
                irreducibilityTrace != null,
                "factor irreducibility trace is required");
            require(
                factorHash.equals(irreducibilityTrace.sourceHash()),
                "factor trace must bind the factor hash");
            require(validHash(attemptHash), "factor attempt hash is invalid");
            require(
                attemptHash.equals(factorAttemptHash(
                    factorIndex,
                    multiplicity,
                    factorHash,
                    irreducibilityTrace)),
                "factor attempt hash mismatch");
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                Integer.toString(factorIndex));
            PolynomialEvidence.append(
                result,
                Integer.toString(multiplicity));
            PolynomialEvidence.append(result, factorHash);
            PolynomialEvidence.append(
                result,
                irreducibilityTrace.canonicalMaterial());
            PolynomialEvidence.append(result, attemptHash);
            return result.toString();
        }
    }

    /** Audit data for one independently checked exact-product candidate. */
    public record CandidateAttempt(
        int candidateIndex,
        String candidateCertificateHash,
        boolean remainderOne,
        int factorCount,
        List<FactorAttempt> factorAttempts,
        CandidateOutcome outcome,
        String detailCode,
        long workUnits,
        String attemptHash
    ) {
        public CandidateAttempt {
            factorAttempts = List.copyOf(factorAttempts);
            require(
                candidateIndex >= 0,
                "candidate index must not be negative");
            require(
                validHash(candidateCertificateHash),
                "candidate certificate hash is invalid");
            require(
                factorCount >= 1,
                "candidate must declare at least one factor");
            require(
                factorAttempts.size() <= factorCount,
                "candidate retains more attempts than factors");
            require(outcome != null, "candidate outcome is required");
            require(detailCode != null, "candidate detail code is required");
            require(
                !detailCode.isBlank(),
                "candidate detail code must not be blank");
            require(
                workUnits >= 1,
                "candidate attempt must account for dispatch work");
            require(
                validHash(attemptHash),
                "candidate attempt hash is invalid");
            validateFactorSequence(factorAttempts);
            validateRemainder(remainderOne, outcome, factorAttempts);
            validateCertifiedCandidate(
                outcome,
                factorCount,
                factorAttempts);
            validateCandidateWork(workUnits, factorAttempts);
            require(
                attemptHash.equals(candidateAttemptHash(
                    candidateIndex,
                    candidateCertificateHash,
                    remainderOne,
                    factorCount,
                    factorAttempts,
                    outcome,
                    detailCode,
                    workUnits)),
                "completeness candidate attempt hash mismatch");
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            PolynomialEvidence.append(
                result,
                Integer.toString(candidateIndex));
            PolynomialEvidence.append(result, candidateCertificateHash);
            PolynomialEvidence.append(
                result,
                Boolean.toString(remainderOne));
            PolynomialEvidence.append(
                result,
                Integer.toString(factorCount));
            PolynomialEvidence.append(
                result,
                Integer.toString(factorAttempts.size()));
            factorAttempts.forEach(attempt -> PolynomialEvidence.append(
                result,
                attempt.canonicalMaterial()));
            PolynomialEvidence.append(result, outcome.name());
            PolynomialEvidence.append(result, detailCode);
            PolynomialEvidence.append(result, Long.toString(workUnits));
            PolynomialEvidence.append(result, attemptHash);
            return result.toString();
        }
    }

    private record State(
        String methodId,
        String requestHash,
        String sourceHash,
        Outcome outcome,
        String detailCode,
        List<CandidateAttempt> candidateAttempts,
        int selectedCandidateIndex,
        String selectedCandidateCertificateHash,
        PolynomialWorkLedger work,
        String traceHash
    ) {
        private State {
            candidateAttempts = List.copyOf(candidateAttempts);
            require(
                IndependentFactorizationCompleteness.METHOD_ID.equals(
                    methodId),
                "independent completeness method is invalid");
            require(validHash(requestHash), "completeness request hash is invalid");
            require(validHash(sourceHash), "completeness source hash is invalid");
            require(outcome != null, "completeness outcome is required");
            require(detailCode != null, "completeness detail code is required");
            require(
                !detailCode.isBlank(),
                "completeness detail code must not be blank");
            require(
                selectedCandidateIndex >= -1,
                "selected candidate index is invalid");
            require(
                selectedCandidateCertificateHash != null,
                "selected candidate hash is required");
            require(
                selectedCandidateCertificateHash.isEmpty()
                    || validHash(selectedCandidateCertificateHash),
                "selected candidate hash is invalid");
            require(work != null, "completeness work is required");
            require(validHash(traceHash), "completeness trace hash is invalid");
            validateCampaignAccounting(candidateAttempts, work);
            validateFactorBindings(requestHash, candidateAttempts);
            validateSelection(
                outcome,
                candidateAttempts,
                selectedCandidateIndex,
                selectedCandidateCertificateHash);
            require(
                traceHash.equals(IndependentCompletenessTrace.traceHash(
                    methodId,
                    requestHash,
                    sourceHash,
                    outcome,
                    detailCode,
                    candidateAttempts,
                    selectedCandidateIndex,
                    selectedCandidateCertificateHash,
                    work)),
                "independent completeness trace hash mismatch");
        }
    }

    private static String factorAttemptHash(
        int factorIndex,
        int multiplicity,
        String factorHash,
        FactorizationVerifier.IndependentIrreducibilityTrace trace
    ) {
        StringBuilder material = new StringBuilder(
            IndependentFactorizationCompleteness.METHOD_ID);
        PolynomialEvidence.append(
            material,
            Integer.toString(factorIndex));
        PolynomialEvidence.append(
            material,
            Integer.toString(multiplicity));
        PolynomialEvidence.append(material, factorHash);
        PolynomialEvidence.append(material, trace.canonicalMaterial());
        return PolynomialEvidence.sha256(material.toString());
    }

    private static String candidateAttemptHash(
        int candidateIndex,
        String candidateCertificateHash,
        boolean remainderOne,
        int factorCount,
        List<FactorAttempt> factorAttempts,
        CandidateOutcome outcome,
        String detailCode,
        long workUnits
    ) {
        StringBuilder material = new StringBuilder(
            IndependentFactorizationCompleteness.METHOD_ID);
        PolynomialEvidence.append(
            material,
            Integer.toString(candidateIndex));
        PolynomialEvidence.append(material, candidateCertificateHash);
        PolynomialEvidence.append(
            material,
            Boolean.toString(remainderOne));
        PolynomialEvidence.append(
            material,
            Integer.toString(factorCount));
        PolynomialEvidence.append(
            material,
            Integer.toString(factorAttempts.size()));
        factorAttempts.forEach(attempt -> PolynomialEvidence.append(
            material,
            attempt.canonicalMaterial()));
        PolynomialEvidence.append(material, outcome.name());
        PolynomialEvidence.append(material, detailCode);
        PolynomialEvidence.append(material, Long.toString(workUnits));
        return PolynomialEvidence.sha256(material.toString());
    }

    private static String traceHash(
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
        StringBuilder material = new StringBuilder(methodId);
        PolynomialEvidence.append(material, requestHash);
        PolynomialEvidence.append(material, sourceHash);
        PolynomialEvidence.append(material, outcome.name());
        PolynomialEvidence.append(material, detailCode);
        PolynomialEvidence.append(
            material,
            Integer.toString(candidateAttempts.size()));
        candidateAttempts.forEach(attempt -> PolynomialEvidence.append(
            material,
            attempt.canonicalMaterial()));
        PolynomialEvidence.append(
            material,
            Integer.toString(selectedCandidateIndex));
        PolynomialEvidence.append(
            material,
            selectedCandidateCertificateHash);
        PolynomialEvidence.append(material, work.canonicalMaterial());
        return PolynomialEvidence.sha256(material.toString());
    }

    private static void validateFactorSequence(
        List<FactorAttempt> factorAttempts
    ) {
        for (int index = 0; index < factorAttempts.size(); index++) {
            require(
                factorAttempts.get(index).factorIndex() == index,
                "completeness factor attempts must be consecutive");
        }
        require(
            factorAttempts.stream()
                .map(FactorAttempt::factorHash)
                .distinct().count() == factorAttempts.size(),
            "completeness factors must be distinct");
    }

    private static void validateRemainder(
        boolean remainderOne,
        CandidateOutcome outcome,
        List<FactorAttempt> factorAttempts
    ) {
        if (remainderOne) {
            require(
                outcome != CandidateOutcome.REMAINDER_NOT_ONE,
                "unit remainder cannot have remainder-failure outcome");
            return;
        }
        require(
            outcome == CandidateOutcome.REMAINDER_NOT_ONE,
            "nonunit remainder requires remainder-failure outcome");
        require(
            factorAttempts.isEmpty(),
            "nonunit remainder must be rejected before factor checks");
    }

    private static void validateCertifiedCandidate(
        CandidateOutcome outcome,
        int factorCount,
        List<FactorAttempt> factorAttempts
    ) {
        if (outcome == CandidateOutcome.REMAINDER_NOT_ONE) {
            return;
        }
        boolean everyRetainedFactorCertified = factorAttempts.stream()
            .allMatch(attempt ->
                attempt.irreducibilityTrace().certified());
        if (outcome == CandidateOutcome.CERTIFIED) {
            require(
                factorAttempts.size() == factorCount,
                "complete candidate requires every factor attempt");
            require(
                everyRetainedFactorCertified,
                "complete candidate requires every factor certificate");
            return;
        }
        require(
            factorAttempts.size() < factorCount
                || !everyRetainedFactorCertified,
            "failed candidate must retain an incomplete factor campaign");
    }

    private static void validateCandidateWork(
        long workUnits,
        List<FactorAttempt> factorAttempts
    ) {
        long expected = 1;
        for (FactorAttempt attempt : factorAttempts) {
            expected = Math.addExact(expected, 1);
            expected = Math.addExact(
                expected,
                attempt.irreducibilityTrace().work().totalWorkUnits());
        }
        require(
            workUnits == expected,
            "candidate attempt work does not match retained factor traces");
    }

    private static void validateCampaignAccounting(
        List<CandidateAttempt> candidateAttempts,
        PolynomialWorkLedger work
    ) {
        long retainedWork = 0;
        for (int index = 0;
                index < candidateAttempts.size();
                index++) {
            CandidateAttempt attempt = candidateAttempts.get(index);
            require(
                attempt.candidateIndex() == index,
                "candidate attempts must be consecutive");
            retainedWork = Math.addExact(
                retainedWork,
                attempt.workUnits());
        }
        require(
            retainedWork == work.totalWorkUnits(),
            "candidate attempts must account for trace work");
    }

    private static void validateFactorBindings(
        String requestHash,
        List<CandidateAttempt> candidateAttempts
    ) {
        require(
            candidateAttempts.stream()
                .flatMap(attempt -> attempt.factorAttempts().stream())
                .allMatch(attempt -> attempt.irreducibilityTrace()
                    .requestHash().equals(requestHash)),
            "factor traces must bind the completeness request");
    }

    private static void validateSelection(
        Outcome outcome,
        List<CandidateAttempt> candidateAttempts,
        int selectedCandidateIndex,
        String selectedCandidateCertificateHash
    ) {
        if (outcome != Outcome.CERTIFIED) {
            require(
                selectedCandidateIndex == -1,
                "inconclusive trace cannot select a candidate index");
            require(
                selectedCandidateCertificateHash.isEmpty(),
                "inconclusive trace cannot select a candidate hash");
            require(
                candidateAttempts.stream().noneMatch(attempt ->
                    attempt.outcome() == CandidateOutcome.CERTIFIED),
                "inconclusive trace cannot retain a certified candidate");
            return;
        }
        require(
            selectedCandidateIndex >= 0,
            "certified trace requires a selected candidate");
        require(
            selectedCandidateIndex < candidateAttempts.size(),
            "selected candidate index exceeds retained attempts");
        require(
            selectedCandidateIndex == candidateAttempts.size() - 1,
            "campaign must stop at its first certified candidate");
        CandidateAttempt selected =
            candidateAttempts.get(selectedCandidateIndex);
        require(
            selected.outcome() == CandidateOutcome.CERTIFIED,
            "selected candidate must be certified");
        require(
            selectedCandidateCertificateHash.equals(
                selected.candidateCertificateHash()),
            "selected candidate hash mismatch");
    }

    private static boolean validHash(String value) {
        return value != null
            && value.matches("sha256:[0-9a-f]{64}");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
