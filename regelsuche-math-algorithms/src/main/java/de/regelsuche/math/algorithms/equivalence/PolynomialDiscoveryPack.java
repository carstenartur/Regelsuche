package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;

/** Curated polynomial examples used to exercise exact algebraic discovery and reporting. */
public final class PolynomialDiscoveryPack {
    private PolynomialDiscoveryPack() {
    }

    public static List<Example> examples() {
        return List.of(
            new Example(
                "binomial-square",
                "(x + a)^2 - (x^2 + 2*a*x + a^2)",
                List.of("1"),
                Kind.IDENTITY,
                true
            ),
            new Example(
                "difference-of-squares",
                "x^2 - a^2",
                List.of("x - a", "x + a"),
                Kind.FACTORIZATION,
                true
            ),
            new Example(
                "cubic-factorization",
                "x^3 - 1",
                List.of("x - 1", "x^2 + x + 1"),
                Kind.FACTORIZATION,
                true
            ),
            new Example(
                "elimination-consequence",
                "2*y - 1",
                List.of("x + y - 1", "x - y"),
                Kind.IDEAL_MEMBERSHIP,
                true
            ),
            new Example(
                "non-member-counterexample-trap",
                "x + y",
                List.of("x*y - 1"),
                Kind.COUNTEREXAMPLE_TRAP,
                false
            ),
            new Example(
                "non-member-ideal",
                "x",
                List.of("x^2"),
                Kind.IDEAL_MEMBERSHIP,
                false
            ),
            new Example(
                "rational-coefficients",
                "0.5*x + 0.5*x - 1",
                List.of("x - 1"),
                Kind.RATIONAL_SIMPLIFICATION,
                true
            ),
            new Example(
                "rational-coefficient-ideal",
                "0.5*x - 0.5",
                List.of("x - 1"),
                Kind.RATIONAL_SIMPLIFICATION,
                true
            ),
            new Example(
                "unsupported-trig-radical-division",
                "sin(x) + sqrt(y) + x / y",
                List.of("x"),
                Kind.UNSUPPORTED_DOMAIN,
                false,
                MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN,
                null
            ),
            new Example(
                "budget-limit",
                "x*y",
                List.of("x", "y"),
                Kind.BUDGET_LIMIT,
                false,
                MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED,
                MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(0, 1, 0, 0.0, 256, 20, 8)
            )
        );
    }

    public static String renderReportJson(List<EvaluationResult> results) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.polynomial-discovery-pack-report/v1");
        writer.array("cases", cases -> (results == null ? List.<EvaluationResult>of() : results).forEach(result ->
            cases.objectValue(object -> {
                object.property("id", result.example().id());
                object.property("kind", result.example().kind().name());
                object.property("expectedMember", result.example().expectedMember());
                object.property("actualMember", result.actualMember());
                object.property("actualResult", result.actualResult());
                object.property("status", result.status().name());
            })
        ));
        writer.endObject();
        return writer.toString();
    }

    public enum Kind {
        IDENTITY,
        FACTORIZATION,
        ELIMINATION,
        IDEAL_MEMBERSHIP,
        RATIONAL_SIMPLIFICATION,
        COUNTEREXAMPLE_TRAP,
        UNSUPPORTED_DOMAIN,
        BUDGET_LIMIT
    }

    public record Example(
        String id,
        String polynomial,
        List<String> generators,
        Kind kind,
        boolean expectedMember,
        MathematicalAlgorithmRegistry.ExecutionStatus expectedStatus,
        MathematicalAlgorithmRegistry.AlgorithmBudget budget
    ) {
        public Example(String id, String polynomial, List<String> generators, Kind kind, boolean expectedMember) {
            this(id, polynomial, generators, kind, expectedMember, MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, null);
        }

        public Example {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (polynomial == null || polynomial.isBlank()) {
                throw new IllegalArgumentException("polynomial must not be blank");
            }
            generators = generators == null ? List.of() : List.copyOf(generators);
            kind = kind == null ? Kind.IDENTITY : kind;
            expectedStatus = expectedStatus == null ? MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS : expectedStatus;
        }
    }

    public record EvaluationResult(
        Example example,
        boolean actualMember,
        String actualResult,
        MathematicalAlgorithmRegistry.ExecutionStatus status
    ) {
        public EvaluationResult {
            if (example == null) {
                throw new IllegalArgumentException("example must not be null");
            }
            actualResult = actualResult == null ? "" : actualResult;
            status = status == null ? MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN : status;
        }
    }
}
