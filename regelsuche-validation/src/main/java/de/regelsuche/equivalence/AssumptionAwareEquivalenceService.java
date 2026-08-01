package de.regelsuche.equivalence;

import java.util.List;
import java.util.Objects;

/**
 * Port for exact or fail-closed equivalence checks under declared assumptions.
 *
 * <p>The validation boundary owns the result vocabulary so learning and search
 * code can request an audit without depending on a concrete computer-algebra
 * implementation. Implementations may expose richer internal evidence, but
 * they must map it to this deterministic contract.</p>
 */
@FunctionalInterface
public interface AssumptionAwareEquivalenceService {

    Evaluation evaluate(
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    );

    enum Status {
        CONFIRMED,
        REFUTED,
        MISSING_ASSUMPTION,
        UNSUPPORTED
    }

    record Evaluation(
        Status status,
        boolean equivalent,
        String leftNormalForm,
        String rightNormalForm,
        List<String> requiredAssumptions,
        List<String> providedAssumptions,
        List<String> missingAssumptions,
        List<String> unsupportedAssumptions,
        String detail
    ) {
        public Evaluation {
            Objects.requireNonNull(status, "status");
            if (equivalent != (status == Status.CONFIRMED)) {
                throw new IllegalArgumentException(
                    "equivalent must be true exactly for CONFIRMED");
            }
            leftNormalForm = leftNormalForm == null ? "" : leftNormalForm;
            rightNormalForm = rightNormalForm == null ? "" : rightNormalForm;
            requiredAssumptions = immutable(
                requiredAssumptions, "requiredAssumptions");
            providedAssumptions = immutable(
                providedAssumptions, "providedAssumptions");
            missingAssumptions = immutable(
                missingAssumptions, "missingAssumptions");
            unsupportedAssumptions = immutable(
                unsupportedAssumptions, "unsupportedAssumptions");
            detail = detail == null ? "" : detail;
        }

        public static Evaluation confirmed() {
            return new Evaluation(
                Status.CONFIRMED,
                true,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "confirmed by assumption-aware equivalence service");
        }

        public static Evaluation refuted() {
            return new Evaluation(
                Status.REFUTED,
                false,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "refuted by assumption-aware equivalence service");
        }

        public static Evaluation unsupported(String detail) {
            return new Evaluation(
                Status.UNSUPPORTED,
                false,
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                detail);
        }

        private static List<String> immutable(
            List<String> values,
            String name
        ) {
            Objects.requireNonNull(values, name);
            if (values.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                    name + " must not contain null");
            }
            return List.copyOf(values);
        }
    }
}
