package de.regelsuche.release;

import de.regelsuche.mining.OpenTargetConjectureEvaluator.NegativeHoldout;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.PositiveHoldout;
import java.util.List;

/** Predeclared qualification cases for the exact retained production candidate. */
public final class ProductionCandidateQualificationCatalog {
    public static final String REVISION =
        "production-candidate-composite-factor-qualification/v1";
    public static final String HELD_OUT_CLUSTER_ID =
        "composite-common-factor-gap-two/v1";

    private ProductionCandidateQualificationCatalog() {
    }

    public static List<PositiveCase> positives() {
        return List.of(
            positive("qualified-product-14", 14, "a * b"),
            positive("qualified-power-15", 15, "c^2"),
            positive("qualified-three-factor-16", 16, "d * e * f"),
            positive("qualified-squared-sum-18", 18, "(g + h)^2"),
            positive("qualified-affine-factor-20", 20, "i * j + k"),
            positive("qualified-cube-21", 21, "l^3"),
            positive("qualified-nested-product-22", 22, "m * (n + o)"),
            positive("qualified-conjugate-product-24", 24, "(p - q) * (p + q)"),
            positive("qualified-sum-of-squares-25", 25, "r^2 + s^2"),
            positive("qualified-product-difference-26", 26, "t * (u - v)"),
            positive("qualified-two-products-27", 27, "(w + x) * (y + z)"),
            positive("qualified-power-product-28", 28, "a^2 * b"));
    }

    public static List<NegativeHoldout> negatives() {
        return List.of(
            negative("negative-gap-three-product", "(14 + 3) * (a * b) + 14 * (a * b)"),
            negative("negative-second-coefficient-power", "(15 + 2) * (c^2) + 16 * (c^2)"),
            negative("negative-distinct-three-factor", "(16 + 2) * (d * e * f) + 16 * (d * e)"),
            negative("negative-subtraction-squared-sum", "(18 + 2) * ((g + h)^2) - 18 * ((g + h)^2)"),
            negative("negative-distinct-affine-factor", "(20 + 2) * (i * j + k) + 20 * (i * j - k)"),
            negative("negative-gap-four-cube", "(21 + 4) * (l^3) + 21 * (l^3)"),
            negative("negative-shifted-coefficient-nested", "(22 + 2) * (m * (n + o)) + 23 * (m * (n + o))"),
            negative("negative-distinct-conjugate", "(24 + 2) * ((p - q) * (p + q)) + 24 * ((p - q)^2)"),
            negative("negative-distinct-square-sum", "(25 + 2) * (r^2 + s^2) + 25 * (r^2 - s^2)"),
            negative("negative-distinct-product-difference", "(26 + 2) * (t * (u - v)) + 26 * (t * (u + v))"),
            negative("negative-gap-one-two-products", "(27 + 1) * ((w + x) * (y + z)) + 27 * ((w + x) * (y + z))"),
            negative("negative-distinct-power-product", "(28 + 2) * (a^2 * b) + 28 * (a^2 * c)"));
    }

    public static List<String> developmentExpressions() {
        return List.of(
            "(53 + 2) * q + 53 * q", "(2 * 53 + 2) * q",
            "(67 + 2) * r + 67 * r", "(2 * 67 + 2) * r",
            "(71 + 2) * (u + v) + 71 * (u + v)",
            "(2 * 71 + 2) * (u + v)",
            "(53 + 3) * q + 53 * q",
            "(67 + 2) * r + 68 * r",
            "(71 + 2) * (u + v) + 71 * (u - v)");
    }

    private static PositiveCase positive(String id, int parameter, String factor) {
        String grouped = '(' + factor + ')';
        return new PositiveCase(
            id,
            factor,
            "(" + parameter + " + 2) * " + grouped
                + " + " + parameter + " * " + grouped,
            "(2 * " + parameter + " + 2) * " + grouped);
    }

    private static NegativeHoldout negative(String id, String expression) {
        return new NegativeHoldout(id, expression);
    }

    public record PositiveCase(
        String id,
        String factorExpression,
        String inputExpression,
        String targetExpression
    ) {
        public PositiveCase {
            require(id, "id");
            require(factorExpression, "factorExpression");
            require(inputExpression, "inputExpression");
            require(targetExpression, "targetExpression");
        }

        public PositiveHoldout asHoldout() {
            return new PositiveHoldout(id, inputExpression, targetExpression);
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
