package de.regelsuche.evolution;

import de.regelsuche.search.SearchHeuristic;
import java.util.List;

/**
 * Open, reviewable TRAIN corpus for the proof-carrying rewrite-program study.
 *
 * <p>All cases are visible by design and may be used for mutation and fitness.
 * No VALIDATION or FINAL TEST material is referenced from this class.</p>
 */
public final class FlagshipRewriteProgramTrainCorpus {
    public static final String SUITE_ID =
        "flagship_rational_polynomial_train_v1";

    private FlagshipRewriteProgramTrainCorpus() {
    }

    public static EvolutionRewriteProgramTrainSuite create() {
        SearchHeuristic heuristic = new SearchHeuristic(
            6,
            512,
            1,
            4,
            40,
            8);
        EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget workBudget =
            new EvolutionRewriteProgramTrainSuite.PrimitiveWorkBudget(
                6,
                512,
                40,
                4,
                20_000L);
        return EvolutionRewriteProgramTrainSuite.create(
            SUITE_ID,
            EvolutionRewriteProgramTrainSuite.EvaluatorProfile
                .EXACT_RATIONAL_NORMAL_FORM_WITH_DECLARED_ASSUMPTIONS,
            cases(),
            heuristic,
            workBudget);
    }

    public static List<EvolutionRewriteProgramTrainSuite.TrainCase> cases() {
        return List.of(
            trainCase(
                "train_affine_factor_cancellation",
                "affine_factor_cancellation",
                "((u + 2) * p) / ((u + 2) * q)",
                "p / q",
                List.of("u + 2 != 0", "q != 0")),
            trainCase(
                "train_common_denominator_difference",
                "common_denominator_arithmetic",
                "a / x - a / y",
                "a * (y - x) / (x * y)",
                List.of("x != 0", "y != 0")),
            trainCase(
                "train_direct_factor_cancellation",
                "direct_factor_cancellation",
                "(x * a) / (x * b)",
                "a / b",
                List.of("x != 0", "b != 0")),
            trainCase(
                "train_equal_denominator_collection",
                "common_denominator_arithmetic",
                "a / x + b / x",
                "(a + b) / x",
                List.of("x != 0")),
            trainCase(
                "train_identity_then_cancellation",
                "normalization_before_cancellation",
                "((z + 1) * 1) / ((z + 1) * t)",
                "1 / t",
                List.of("z + 1 != 0", "t != 0")),
            trainCase(
                "train_nested_shared_denominator_division",
                "nested_rational_composition",
                "(m / n) / (p / n)",
                "m / p",
                List.of("n != 0", "p != 0")),
            trainCase(
                "train_scaled_linear_collection",
                "polynomial_collection",
                "(2 * x + 2 * y) / 2",
                "x + y",
                List.of()),
            trainCase(
                "train_square_difference_bridge",
                "polynomial_factor_bridge",
                "(r^2 - s^2) / (r - s)",
                "r + s",
                List.of("r - s != 0")));
    }

    private static EvolutionRewriteProgramTrainSuite.TrainCase trainCase(
        String caseId,
        String familyId,
        String input,
        String target,
        List<String> assumptions
    ) {
        return new EvolutionRewriteProgramTrainSuite.TrainCase(
            caseId,
            familyId,
            input,
            target,
            assumptions);
    }
}
