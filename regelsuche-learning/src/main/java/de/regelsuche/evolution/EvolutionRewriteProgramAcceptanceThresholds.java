package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/**
 * Numerical success and transparent-null-result thresholds frozen before any
 * evaluated flagship rewrite-program search begins.
 */
public record EvolutionRewriteProgramAcceptanceThresholds(
    String schema,
    int minimumImprovedFinalTestCases,
    int minimumDistinctImprovedFamilies,
    int minimumNewlyReachedCases,
    boolean allowMaterialWorkReductionRoute,
    int minimumMechanicalWorkReductionPermille,
    int minimumRetainedProgramPrimitiveSteps,
    boolean requireCompositionTopology,
    boolean requireDecisionTopology,
    int maximumCorrectnessRegressions,
    int maximumHiddenAssumptionRegressions,
    int maximumUnexpectedUnsupportedCases,
    int maximumTechnicalFailures,
    int maximumProgramNodes,
    int maximumGenomeRules,
    int requiredCleanReproductions,
    boolean requirePinnedContainerReproduction,
    SuccessRoute successRoute,
    NullResultPolicy nullResultPolicy,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-acceptance-thresholds/v1";

    public EvolutionRewriteProgramAcceptanceThresholds {
        validate(
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            requireCompositionTopology,
            requireDecisionTopology,
            maximumCorrectnessRegressions,
            maximumHiddenAssumptionRegressions,
            maximumUnexpectedUnsupportedCases,
            maximumTechnicalFailures,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            requirePinnedContainerReproduction,
            successRoute,
            nullResultPolicy);
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported rewrite-program acceptance-threshold schema");
        }
        EvolutionGenome.requireSha256(contentHash, "contentHash");
        String expected = EvolutionGenome.hash(render(
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            requireCompositionTopology,
            requireDecisionTopology,
            maximumCorrectnessRegressions,
            maximumHiddenAssumptionRegressions,
            maximumUnexpectedUnsupportedCases,
            maximumTechnicalFailures,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            requirePinnedContainerReproduction,
            successRoute,
            nullResultPolicy,
            null));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "acceptance-threshold contentHash mismatch");
        }
    }

    public static EvolutionRewriteProgramAcceptanceThresholds create(
        int minimumImprovedFinalTestCases,
        int minimumDistinctImprovedFamilies,
        int minimumNewlyReachedCases,
        boolean allowMaterialWorkReductionRoute,
        int minimumMechanicalWorkReductionPermille,
        int minimumRetainedProgramPrimitiveSteps,
        int maximumUnexpectedUnsupportedCases,
        int maximumProgramNodes,
        int maximumGenomeRules,
        int requiredCleanReproductions
    ) {
        SuccessRoute successRoute = allowMaterialWorkReductionRoute
            ? SuccessRoute.NEWLY_REACHED_OR_MATERIAL_WORK_REDUCTION
            : SuccessRoute.NEWLY_REACHED_REQUIRED;
        validate(
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            true,
            true,
            0,
            0,
            maximumUnexpectedUnsupportedCases,
            0,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            true,
            successRoute,
            NullResultPolicy.TRANSPARENT_COMPLETE_NULL_RESULT);
        String hash = EvolutionGenome.hash(render(
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            true,
            true,
            0,
            0,
            maximumUnexpectedUnsupportedCases,
            0,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            true,
            successRoute,
            NullResultPolicy.TRANSPARENT_COMPLETE_NULL_RESULT,
            null));
        return new EvolutionRewriteProgramAcceptanceThresholds(
            SCHEMA,
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            true,
            true,
            0,
            0,
            maximumUnexpectedUnsupportedCases,
            0,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            true,
            successRoute,
            NullResultPolicy.TRANSPARENT_COMPLETE_NULL_RESULT,
            hash);
    }

    public String toCanonicalJson() {
        return render(
            minimumImprovedFinalTestCases,
            minimumDistinctImprovedFamilies,
            minimumNewlyReachedCases,
            allowMaterialWorkReductionRoute,
            minimumMechanicalWorkReductionPermille,
            minimumRetainedProgramPrimitiveSteps,
            requireCompositionTopology,
            requireDecisionTopology,
            maximumCorrectnessRegressions,
            maximumHiddenAssumptionRegressions,
            maximumUnexpectedUnsupportedCases,
            maximumTechnicalFailures,
            maximumProgramNodes,
            maximumGenomeRules,
            requiredCleanReproductions,
            requirePinnedContainerReproduction,
            successRoute,
            nullResultPolicy,
            contentHash);
    }

    private static void validate(
        int minimumImprovedFinalTestCases,
        int minimumDistinctImprovedFamilies,
        int minimumNewlyReachedCases,
        boolean allowMaterialWorkReductionRoute,
        int minimumMechanicalWorkReductionPermille,
        int minimumRetainedProgramPrimitiveSteps,
        boolean requireCompositionTopology,
        boolean requireDecisionTopology,
        int maximumCorrectnessRegressions,
        int maximumHiddenAssumptionRegressions,
        int maximumUnexpectedUnsupportedCases,
        int maximumTechnicalFailures,
        int maximumProgramNodes,
        int maximumGenomeRules,
        int requiredCleanReproductions,
        boolean requirePinnedContainerReproduction,
        SuccessRoute successRoute,
        NullResultPolicy nullResultPolicy
    ) {
        if (minimumImprovedFinalTestCases < 2
                || minimumDistinctImprovedFamilies < 2) {
            throw new IllegalArgumentException(
                "flagship success requires multiple cases and families");
        }
        if (minimumNewlyReachedCases < 0) {
            throw new IllegalArgumentException(
                "minimumNewlyReachedCases must not be negative");
        }
        if (!allowMaterialWorkReductionRoute
                && minimumNewlyReachedCases < 1) {
            throw new IllegalArgumentException(
                "newly-reached route requires at least one case");
        }
        if (allowMaterialWorkReductionRoute
                && (minimumMechanicalWorkReductionPermille < 50
                    || minimumMechanicalWorkReductionPermille > 1000)) {
            throw new IllegalArgumentException(
                "material-work route requires a reduction in [50,1000] permille");
        }
        if (!allowMaterialWorkReductionRoute
                && minimumMechanicalWorkReductionPermille != 0) {
            throw new IllegalArgumentException(
                "disabled material-work route requires zero reduction threshold");
        }
        if (minimumRetainedProgramPrimitiveSteps < 3
                || !requireCompositionTopology
                || !requireDecisionTopology) {
            throw new IllegalArgumentException(
                "flagship program must use multi-step composition and decision topology");
        }
        if (maximumCorrectnessRegressions != 0
                || maximumHiddenAssumptionRegressions != 0
                || maximumTechnicalFailures != 0) {
            throw new IllegalArgumentException(
                "positive flagship claim permits no correctness, assumption or technical failures");
        }
        if (maximumUnexpectedUnsupportedCases < 0
                || maximumProgramNodes < 1
                || maximumGenomeRules < 1) {
            throw new IllegalArgumentException(
                "unsupported and complexity thresholds are invalid");
        }
        if (requiredCleanReproductions < 2
                || !requirePinnedContainerReproduction) {
            throw new IllegalArgumentException(
                "flagship evidence requires two clean and one pinned reproduction");
        }
        Objects.requireNonNull(successRoute, "successRoute");
        Objects.requireNonNull(nullResultPolicy, "nullResultPolicy");
        SuccessRoute expected = allowMaterialWorkReductionRoute
            ? SuccessRoute.NEWLY_REACHED_OR_MATERIAL_WORK_REDUCTION
            : SuccessRoute.NEWLY_REACHED_REQUIRED;
        if (successRoute != expected
                || nullResultPolicy
                    != NullResultPolicy.TRANSPARENT_COMPLETE_NULL_RESULT) {
            throw new IllegalArgumentException(
                "success or null-result policy is inconsistent");
        }
    }

    private static String render(
        int minimumImprovedFinalTestCases,
        int minimumDistinctImprovedFamilies,
        int minimumNewlyReachedCases,
        boolean allowMaterialWorkReductionRoute,
        int minimumMechanicalWorkReductionPermille,
        int minimumRetainedProgramPrimitiveSteps,
        boolean requireCompositionTopology,
        boolean requireDecisionTopology,
        int maximumCorrectnessRegressions,
        int maximumHiddenAssumptionRegressions,
        int maximumUnexpectedUnsupportedCases,
        int maximumTechnicalFailures,
        int maximumProgramNodes,
        int maximumGenomeRules,
        int requiredCleanReproductions,
        boolean requirePinnedContainerReproduction,
        SuccessRoute successRoute,
        NullResultPolicy nullResultPolicy,
        String contentHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("minimumImprovedFinalTestCases",
                minimumImprovedFinalTestCases)
            .property("minimumDistinctImprovedFamilies",
                minimumDistinctImprovedFamilies)
            .property("minimumNewlyReachedCases", minimumNewlyReachedCases)
            .property("allowMaterialWorkReductionRoute",
                allowMaterialWorkReductionRoute)
            .property("minimumMechanicalWorkReductionPermille",
                minimumMechanicalWorkReductionPermille)
            .property("minimumRetainedProgramPrimitiveSteps",
                minimumRetainedProgramPrimitiveSteps)
            .property("requireCompositionTopology", requireCompositionTopology)
            .property("requireDecisionTopology", requireDecisionTopology)
            .property("maximumCorrectnessRegressions",
                maximumCorrectnessRegressions)
            .property("maximumHiddenAssumptionRegressions",
                maximumHiddenAssumptionRegressions)
            .property("maximumUnexpectedUnsupportedCases",
                maximumUnexpectedUnsupportedCases)
            .property("maximumTechnicalFailures", maximumTechnicalFailures)
            .property("maximumProgramNodes", maximumProgramNodes)
            .property("maximumGenomeRules", maximumGenomeRules)
            .property("requiredCleanReproductions",
                requiredCleanReproductions)
            .property("requirePinnedContainerReproduction",
                requirePinnedContainerReproduction)
            .property("successRoute", successRoute.name())
            .property("nullResultPolicy", nullResultPolicy.name());
        if (contentHash != null) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    public enum SuccessRoute {
        NEWLY_REACHED_REQUIRED,
        NEWLY_REACHED_OR_MATERIAL_WORK_REDUCTION
    }

    public enum NullResultPolicy {
        TRANSPARENT_COMPLETE_NULL_RESULT
    }
}
