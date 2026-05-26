package de.regelsuche.math.algorithms.equivalence;

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
                "rational-coefficients",
                "0.5*x + 0.5*x - 1",
                List.of("x - 1"),
                Kind.RATIONAL_SIMPLIFICATION,
                true
            )
        );
    }

    public enum Kind {
        IDENTITY,
        FACTORIZATION,
        ELIMINATION,
        IDEAL_MEMBERSHIP,
        RATIONAL_SIMPLIFICATION,
        COUNTEREXAMPLE_TRAP
    }

    public record Example(
        String id,
        String polynomial,
        List<String> generators,
        Kind kind,
        boolean expectedMember
    ) {
        public Example {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (polynomial == null || polynomial.isBlank()) {
                throw new IllegalArgumentException("polynomial must not be blank");
            }
            generators = generators == null ? List.of() : List.copyOf(generators);
            kind = kind == null ? Kind.IDENTITY : kind;
        }
    }
}
