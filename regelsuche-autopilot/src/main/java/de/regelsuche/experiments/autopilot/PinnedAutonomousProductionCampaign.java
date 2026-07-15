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

/** Pinned, target-free input for the first production campaign in issue #348. */
public final class PinnedAutonomousProductionCampaign {
    public static final String CAMPAIGN_ID = "autopilot-production-campaign-348";
    public static final String LEFT_FACTOR_GENERATOR =
        "factor-common-left-generator/v1";
    public static final String RIGHT_FACTOR_GENERATOR =
        "factor-common-right-generator/v1";
    public static final String DOMAIN = "polynomial-factorization";
    public static final int REQUIRED_OBSERVATIONS = 12;

    private static final String PREDECESSOR_V1_BRIEF_HASH =
        "sha256:b1aa8dce6924467390e2a89687678abcd54ba70925e650370faa1b151ae84359";
    private static final SearchHeuristic SEARCH_HEURISTIC =
        new SearchHeuristic(3, 80, 1, 2, 40, 16);

    private PinnedAutonomousProductionCampaign() {
    }

    public static AutonomousResearchBriefV2 brief() {
        return AutonomousResearchBriefV2.create(
            CAMPAIGN_ID,
            PREDECESSOR_V1_BRIEF_HASH,
            List.of(DOMAIN),
            List.of(LEFT_FACTOR_GENERATOR, RIGHT_FACTOR_GENERATOR),
            inventoryHash(),
            AutonomousResearchBrief.hash(
                "core-ast-default-rules/v1|" + inventoryHash()),
            AutonomousResearchBrief.hash(
                "best-first-search/v1|expression-scorer/v1|"
                    + SEARCH_HEURISTIC),
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
        return AutonomousResearchBrief.hash(
            "regelsuche.ast-default-rule-inventory/v2|" + rules);
    }

    public static List<SeedExpression> seeds() {
        return List.of(
            leftSeed("left-factor-01", "x", 2, 3),
            leftSeed("left-factor-02", "a", 4, 5),
            leftSeed("left-factor-03", "b", 6, 7),
            leftSeed("left-factor-04", "c", 8, 9),
            leftSeed("left-factor-05", "d", 10, 11),
            leftSeed("left-factor-06", "e", 12, 13),
            rightSeed("right-factor-01", "y", 2, 3),
            rightSeed("right-factor-02", "f", 4, 5),
            rightSeed("right-factor-03", "g", 6, 7),
            rightSeed("right-factor-04", "h", 8, 9),
            rightSeed("right-factor-05", "i", 10, 11),
            rightSeed("right-factor-06", "j", 12, 13));
    }

    private static SeedExpression leftSeed(
        String id,
        String common,
        int left,
        int right
    ) {
        return new SeedExpression(
            id,
            common + " * " + left + " + " + common + " * " + right,
            LEFT_FACTOR_GENERATOR,
            DOMAIN,
            List.of("autopilot", "production", "factor-common-left"),
            List.of());
    }

    private static SeedExpression rightSeed(
        String id,
        String common,
        int left,
        int right
    ) {
        return new SeedExpression(
            id,
            left + " * " + common + " + " + right + " * " + common,
            RIGHT_FACTOR_GENERATOR,
            DOMAIN,
            List.of("autopilot", "production", "factor-common-right"),
            List.of());
    }

    private static Map<EvidenceStage, StageBudget> stageBudgets() {
        EnumMap<EvidenceStage, StageBudget> budgets =
            new EnumMap<>(EvidenceStage.class);
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
