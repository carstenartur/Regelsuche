package de.regelsuche.experiments.autopilot;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.StageBudget;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Pinned, target-free input for the production campaign in issue #348. */
public final class PinnedAutonomousProductionCampaign {
    public static final String CAMPAIGN_ID = "autopilot-production-campaign-348";
    public static final String GAP_TWO_FACTOR_GENERATOR =
        "factor-common-gap-two-generator/v1";
    public static final String TWIN_PRIME_FACTOR_GENERATOR =
        "factor-common-twin-prime-generator/v1";
    public static final String DOMAIN = "polynomial-factorization";
    public static final int REQUIRED_OBSERVATIONS = 12;

    private static final String GAP_TWO_REJECTION_SEED =
        "gap-two-factor-01";
    private static final String TWIN_PRIME_REJECTION_SEED =
        "twin-prime-factor-01";
    private static final SearchHeuristic SEARCH_HEURISTIC =
        new SearchHeuristic(3, 80, 1, 2, 40, 16);

    private PinnedAutonomousProductionCampaign() {
    }

    public static AutonomousResearchBriefV2 brief() {
        return AutonomousResearchBriefV2.create(
            CAMPAIGN_ID,
            List.of(DOMAIN),
            List.of(GAP_TWO_FACTOR_GENERATOR, TWIN_PRIME_FACTOR_GENERATOR),
            inventoryHash(),
            AutonomousResearchBriefV2.hash(
                "core-ast-default-rules/v1|" + inventoryHash()),
            AutonomousResearchBriefV2.hash(
                "best-first-search/v1|expression-scorer/v1|"
                    + "expression-canonicalizer/v1|" + SEARCH_HEURISTIC),
            348_202_607_15L,
            2,
            2,
            2,
            "autopilot/production-candidate",
            stageBudgets());
    }

    public static SearchHeuristic searchHeuristic() {
        return SEARCH_HEURISTIC;
    }

    public static String inventoryHash() {
        List<String> rules = AstRewriteTransformationEngine.defaultRules().stream()
            .map(rule -> {
                var descriptor = rule.descriptor();
                return rule.id()
                    + '|' + rule.kind().name()
                    + '|' + rule.mayIncreaseComplexity()
                    + '|' + rule.estimatedCostDelta()
                    + '|' + rule.isEquivalencePreservingByConstruction()
                    + '|' + descriptor.packId()
                    + '|' + descriptor.license()
                    + '|' + descriptor.sourceVersion()
                    + '|' + descriptor.status().name();
            })
            .sorted()
            .toList();
        return AutonomousResearchBriefV2.hash(
            "regelsuche.ast-default-rule-inventory/v2|" + rules);
    }

    public static List<SeedExpression> seeds() {
        return List.of(
            factorSeed(
                GAP_TWO_REJECTION_SEED,
                GAP_TWO_FACTOR_GENERATOR,
                "x", 3, 5, "gap-two-parameters"),
            factorSeed(
                "gap-two-factor-02",
                GAP_TWO_FACTOR_GENERATOR,
                "a", 4, 6, "gap-two-parameters"),
            factorSeed(
                "gap-two-factor-03",
                GAP_TWO_FACTOR_GENERATOR,
                "b", 6, 8, "gap-two-parameters"),
            factorSeed(
                "gap-two-factor-04",
                GAP_TWO_FACTOR_GENERATOR,
                "c", 8, 10, "gap-two-parameters"),
            factorSeed(
                "gap-two-factor-05",
                GAP_TWO_FACTOR_GENERATOR,
                "d", 10, 12, "gap-two-parameters"),
            factorSeed(
                "gap-two-factor-06",
                GAP_TWO_FACTOR_GENERATOR,
                "e", 12, 14, "gap-two-parameters"),
            factorSeed(
                TWIN_PRIME_REJECTION_SEED,
                TWIN_PRIME_FACTOR_GENERATOR,
                "y", 3, 5, "twin-prime-parameters"),
            factorSeed(
                "twin-prime-factor-02",
                TWIN_PRIME_FACTOR_GENERATOR,
                "f", 5, 7, "twin-prime-parameters"),
            factorSeed(
                "twin-prime-factor-03",
                TWIN_PRIME_FACTOR_GENERATOR,
                "g", 11, 13, "twin-prime-parameters"),
            factorSeed(
                "twin-prime-factor-04",
                TWIN_PRIME_FACTOR_GENERATOR,
                "h", 17, 19, "twin-prime-parameters"),
            factorSeed(
                "twin-prime-factor-05",
                TWIN_PRIME_FACTOR_GENERATOR,
                "i", 29, 31, "twin-prime-parameters"),
            factorSeed(
                "twin-prime-factor-06",
                TWIN_PRIME_FACTOR_GENERATOR,
                "j", 41, 43, "twin-prime-parameters"));
    }

    /**
     * Predeclared cross-family pair with the same constants and only an alpha
     * renaming. It is used to retain an honest zero-output rejection batch.
     */
    public static List<String> alphaRejectionSeedIds() {
        return List.of(GAP_TWO_REJECTION_SEED, TWIN_PRIME_REJECTION_SEED);
    }

    private static SeedExpression factorSeed(
        String id,
        String generator,
        String common,
        int left,
        int right,
        String familyTag
    ) {
        return new SeedExpression(
            id,
            common + " * " + left + " + " + common + " * " + right,
            generator,
            DOMAIN,
            List.of("autopilot", "production", "factor-common", familyTag),
            List.of());
    }

    private static Map<EvidenceStage, StageBudget> stageBudgets() {
        EnumMap<EvidenceStage, StageBudget> budgets = new EnumMap<>(EvidenceStage.class);
        budgets.put(EvidenceStage.GENERATION, new StageBudget(Map.of(
            ResourceKind.GENERATED_STATES, 100_000L,
            ResourceKind.EXPLORED_STATES, 10_000L,
            ResourceKind.OBSERVATIONS, (long) REQUIRED_OBSERVATIONS)));
        budgets.put(EvidenceStage.CANDIDATE_FORMATION, new StageBudget(Map.of(
            ResourceKind.MINING_BATCHES, 2L,
            ResourceKind.CANDIDATES, 12L)));
        budgets.put(EvidenceStage.VALIDATION, new StageBudget(Map.of(
            ResourceKind.VALIDATION_CHECKS, 120L)));
        budgets.put(EvidenceStage.COUNTEREXAMPLE_SEARCH, new StageBudget(Map.of(
            ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 1_200L)));
        budgets.put(EvidenceStage.PROJECT_NOVELTY, new StageBudget(Map.of(
            ResourceKind.NOVELTY_COMPARISONS, 1_200L)));
        budgets.put(EvidenceStage.PROOF, new StageBudget(Map.of(
            ResourceKind.PROOF_ATTEMPTS, 12L)));
        budgets.put(EvidenceStage.LIFECYCLE_HANDOFF, new StageBudget(Map.of(
            ResourceKind.LIFECYCLE_HANDOFFS, 12L)));
        return Map.copyOf(budgets);
    }
}
