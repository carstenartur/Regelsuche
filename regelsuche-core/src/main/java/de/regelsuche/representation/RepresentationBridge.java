package de.regelsuche.representation;

import java.util.Objects;
import java.util.Optional;

/**
 * Converts one mathematical representation into another while retaining the
 * relation, finite work and independently checkable formation evidence.
 *
 * <p>A bridge is not an ordinary expression rewrite. For example, a list of
 * scalar equations and {@code A*x=b} are normally solution-set equivalent,
 * not identical syntax trees. Consumers must therefore select consequences by
 * {@link Relation} rather than treating every bridge as expression equality.</p>
 */
public interface RepresentationBridge<S, T, C> {

    Result<T, C> analyze(S source, Budget budget);

    /** Independently recomputes and checks one represented result. */
    boolean verify(S source, Result<T, C> result);

    enum Status {
        REPRESENTED,
        DIRECT_REPRESENTATION_AVAILABLE,
        NOT_APPLICABLE,
        NONLINEAR,
        DOMAIN_UNSUPPORTED,
        ASSUMPTION_REQUIRED,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    enum Relation {
        EXACT_EXPRESSION_EQUALITY,
        EQUALITY_UNDER_ASSUMPTIONS,
        SOLUTION_SET_EQUIVALENCE,
        LINEAR_MAP_REPRESENTATION_EQUIVALENCE,
        BASIS_CHANGE_EQUIVALENCE,
        SPECTRAL_OR_SIMILARITY_RELATION,
        MODEL_INTERPRETATION_CANDIDATE
    }

    record Budget(int maxWorkUnits) {
        public static final Budget DEFAULT = new Budget(10_000);

        public Budget {
            if (maxWorkUnits < 0) {
                throw new IllegalArgumentException(
                    "maxWorkUnits must not be negative");
            }
        }
    }

    record WorkLedger(
        int configuredWorkUnits,
        int consumedWorkUnits,
        int remainingWorkUnits
    ) {
        public WorkLedger {
            if (configuredWorkUnits < 0
                    || consumedWorkUnits < 0
                    || remainingWorkUnits < 0
                    || configuredWorkUnits
                        != consumedWorkUnits + remainingWorkUnits) {
                throw new IllegalArgumentException(
                    "representation work must be non-negative and balanced");
            }
        }

        public static WorkLedger of(int configured, int consumed) {
            if (consumed > configured) {
                throw new IllegalArgumentException(
                    "consumed work must not exceed configured work");
            }
            return new WorkLedger(configured, consumed, configured - consumed);
        }
    }

    record Result<T, C>(
        Status status,
        Optional<T> representation,
        Optional<C> certificate,
        Optional<Relation> relation,
        WorkLedger work,
        String detailCode
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            representation = Objects.requireNonNull(
                representation,
                "representation");
            certificate = Objects.requireNonNull(certificate, "certificate");
            relation = Objects.requireNonNull(relation, "relation");
            work = Objects.requireNonNull(work, "work");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            detailCode = detailCode.trim();

            boolean completePayload = representation.isPresent()
                && certificate.isPresent()
                && relation.isPresent();
            boolean anyPayload = representation.isPresent()
                || certificate.isPresent()
                || relation.isPresent();
            if ((status == Status.REPRESENTED) != completePayload) {
                throw new IllegalArgumentException(
                    "only REPRESENTED results require a complete payload");
            }
            if (status != Status.REPRESENTED && anyPayload) {
                throw new IllegalArgumentException(
                    "non-represented results must not retain a payload");
            }
        }

        public static <T, C> Result<T, C> represented(
            T representation,
            C certificate,
            Relation relation,
            WorkLedger work,
            String detailCode
        ) {
            return new Result<>(
                Status.REPRESENTED,
                Optional.of(Objects.requireNonNull(
                    representation,
                    "representation")),
                Optional.of(Objects.requireNonNull(
                    certificate,
                    "certificate")),
                Optional.of(Objects.requireNonNull(relation, "relation")),
                work,
                detailCode);
        }

        public static <T, C> Result<T, C> withoutRepresentation(
            Status status,
            WorkLedger work,
            String detailCode
        ) {
            if (status == Status.REPRESENTED) {
                throw new IllegalArgumentException(
                    "represented results require payloads");
            }
            return new Result<>(
                status,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                work,
                detailCode);
        }

        public boolean represented() {
            return status == Status.REPRESENTED;
        }
    }
}
