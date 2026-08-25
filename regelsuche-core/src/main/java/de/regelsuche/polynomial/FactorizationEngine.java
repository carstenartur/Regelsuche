package de.regelsuche.polynomial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Backend-neutral factorization engine SPI.
 *
 * <p>An engine emits proposals and optional backend claims. Neither product
 * equality nor completeness becomes Regelsuche evidence until
 * {@link FactorizationVerifier} has processed the result.</p>
 */
public interface FactorizationEngine<C> {
    String engineId();

    String coefficientDomainId();

    EngineResult<C> propose(FactorizationRequest<C> request);

    enum Outcome {
        CANDIDATES,
        NO_CANDIDATE,
        UNSUPPORTED_DOMAIN,
        UNSUPPORTED_REQUEST,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    /** A retained claim made by the engine, not independent evidence. */
    enum BackendClaim {
        NONE,
        COMPLETE_FACTORIZATION,
        IRREDUCIBLE
    }

    /** Canonical but not yet trusted exact decomposition proposal. */
    record Proposal<C>(
        C unit,
        List<PolynomialFactor<C>> factors,
        SparsePolynomial<C> unresolvedRemainder,
        String engineCertificateHash
    ) {
        public Proposal {
            Objects.requireNonNull(
                unresolvedRemainder,
                "unresolvedRemainder");
            PolynomialRing<C> ring = unresolvedRemainder.ring();
            unit = ring.coefficientDomain().canonical(
                Objects.requireNonNull(unit, "unit"));
            if (ring.coefficientDomain().isZero(unit)
                    || unresolvedRemainder.isZero()
                    || unresolvedRemainder.isConstant()
                        && !unresolvedRemainder.isOne()
                    || engineCertificateHash == null
                    || !engineCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "factorization engine proposal is invalid");
            }
            factors = canonicalFactors(
                ring,
                Objects.requireNonNull(factors, "factors"));
            if (factors.isEmpty()) {
                throw new IllegalArgumentException(
                    "factorization proposal requires at least one factor");
            }
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            append(
                result,
                unresolvedRemainder.ring()
                    .coefficientDomain()
                    .canonicalText(unit));
            for (PolynomialFactor<C> factor : factors) {
                append(
                    result,
                    Integer.toString(factor.multiplicity()));
                append(
                    result,
                    factor.polynomial().canonicalMaterial());
            }
            append(
                result,
                unresolvedRemainder.canonicalMaterial());
            append(result, engineCertificateHash);
            return result.toString();
        }

        private static <C> List<PolynomialFactor<C>> canonicalFactors(
            PolynomialRing<C> ring,
            List<PolynomialFactor<C>> factors
        ) {
            List<PolynomialFactor<C>> ordered = new ArrayList<>(
                List.copyOf(factors));
            if (ordered.stream().anyMatch(factor ->
                    factor == null
                        || !ring.equals(factor.polynomial().ring()))) {
                throw new IllegalArgumentException(
                    "factorization proposal ring mismatch");
            }
            ordered.sort(Comparator
                .comparing((PolynomialFactor<C> factor) ->
                    factor.polynomial().canonicalMaterial())
                .thenComparingInt(PolynomialFactor::multiplicity));
            List<PolynomialFactor<C>> merged = new ArrayList<>();
            for (PolynomialFactor<C> factor : ordered) {
                if (!merged.isEmpty()
                        && merged.getLast().polynomial().equals(
                            factor.polynomial())) {
                    PolynomialFactor<C> previous = merged.removeLast();
                    merged.add(new PolynomialFactor<>(
                        factor.polynomial(),
                        Math.addExact(
                            previous.multiplicity(),
                            factor.multiplicity())));
                } else {
                    merged.add(factor);
                }
            }
            return List.copyOf(merged);
        }
    }

    /** Deterministic stage-separated algorithmic work accounting. */
    record WorkLedger(Map<String, Long> stages) {
        public WorkLedger {
            Objects.requireNonNull(stages, "stages");
            TreeMap<String, Long> canonical = new TreeMap<>();
            stages.forEach((stage, units) -> {
                if (stage == null
                        || stage.isBlank()
                        || units == null
                        || units < 0) {
                    throw new IllegalArgumentException(
                        "factorization work ledger is invalid");
                }
                canonical.merge(stage, units, Math::addExact);
            });
            checkedTotal(canonical.values());
            stages = Collections.unmodifiableMap(
                new LinkedHashMap<>(canonical));
        }

        public long totalWorkUnits() {
            return checkedTotal(stages.values());
        }

        public long units(String stage) {
            return stages.getOrDefault(stage, 0L);
        }

        public boolean within(long maximum) {
            return maximum >= 0 && totalWorkUnits() <= maximum;
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            stages.forEach((stage, units) -> {
                append(result, stage);
                append(result, Long.toString(units));
            });
            append(result, Long.toString(totalWorkUnits()));
            return result.toString();
        }

        public static WorkLedger empty() {
            return new WorkLedger(Map.of());
        }

        private static long checkedTotal(Iterable<Long> unitsByStage) {
            try {
                long total = 0;
                for (long units : unitsByStage) {
                    total = Math.addExact(total, units);
                }
                return total;
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                    "factorization work ledger total exceeds long range",
                    exception);
            }
        }
    }

    /** Complete raw engine output before independent verification. */
    record EngineResult<C>(
        String engineId,
        Outcome outcome,
        String detailCode,
        WorkLedger work,
        List<Proposal<C>> proposals,
        BackendClaim backendClaim,
        String engineResultHash
    ) {
        public EngineResult {
            if (engineId == null
                    || engineId.isBlank()
                    || outcome == null
                    || detailCode == null
                    || detailCode.isBlank()
                    || work == null
                    || backendClaim == null
                    || engineResultHash == null
                    || !engineResultHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "factorization engine result is invalid");
            }
            proposals = canonicalProposals(
                Objects.requireNonNull(proposals, "proposals"));
            if ((outcome == Outcome.CANDIDATES)
                    != !proposals.isEmpty()) {
                throw new IllegalArgumentException(
                    "factorization engine outcome/proposal mismatch");
            }
            if (outcome == Outcome.CANDIDATES
                    && backendClaim == BackendClaim.IRREDUCIBLE) {
                throw new IllegalArgumentException(
                    "candidate result cannot claim irreducibility");
            }
            if (backendClaim == BackendClaim.COMPLETE_FACTORIZATION
                    && proposals.stream().anyMatch(proposal ->
                        !proposal.unresolvedRemainder().isOne())) {
                throw new IllegalArgumentException(
                    "complete backend claim cannot retain an unresolved remainder");
            }
            if (outcome != Outcome.CANDIDATES
                    && outcome != Outcome.NO_CANDIDATE
                    && backendClaim != BackendClaim.NONE) {
                throw new IllegalArgumentException(
                    "failed engine result cannot retain a mathematical claim");
            }
            if (outcome == Outcome.NO_CANDIDATE
                    && backendClaim
                        == BackendClaim.COMPLETE_FACTORIZATION) {
                throw new IllegalArgumentException(
                    "empty engine result cannot claim complete factorization");
            }
        }

        public String canonicalMaterial() {
            StringBuilder result = new StringBuilder();
            append(result, engineId);
            append(result, outcome.name());
            append(result, detailCode);
            append(result, work.canonicalMaterial());
            append(result, backendClaim.name());
            proposals.forEach(proposal -> append(
                result,
                proposal.canonicalMaterial()));
            append(result, engineResultHash);
            return result.toString();
        }

        private static <C> List<Proposal<C>> canonicalProposals(
            List<Proposal<C>> proposals
        ) {
            LinkedHashMap<String, Proposal<C>> unique =
                new LinkedHashMap<>();
            proposals.stream()
                .sorted(Comparator.comparing(
                    Proposal::canonicalMaterial))
                .forEach(proposal -> unique.putIfAbsent(
                    proposal.canonicalMaterial(),
                    proposal));
            return List.copyOf(unique.values());
        }
    }

    private static void append(
        StringBuilder target,
        String value
    ) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }
}
