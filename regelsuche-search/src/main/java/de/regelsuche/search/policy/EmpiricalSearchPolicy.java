package de.regelsuche.search.policy;

import de.regelsuche.search.learning.ExpressionFingerprint;
import de.regelsuche.search.learning.SearchExperienceRepository;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import de.regelsuche.search.policy.SearchPolicyModel.Mode;
import de.regelsuche.search.policy.SearchPolicyModel.RuleStatistics;
import de.regelsuche.transform.Transformation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transparent rule-ranking policy learned from trajectory frequencies and score
 * deltas. It never changes rule applicability and falls back per decision when a
 * model is incompatible or insufficiently observed.
 */
public final class EmpiricalSearchPolicy implements SearchPolicy {
    private static final int TARGET_DISTANCE_WEIGHT = 20;
    private static final int UNKNOWN_RULE_PENALTY = 500;

    private final SearchPolicyModel model;
    private final SearchExperienceRepository experienceRepository;
    private final String experienceFamily;

    public EmpiricalSearchPolicy(SearchPolicyModel model) {
        this(model, null, "");
    }

    public EmpiricalSearchPolicy(
        SearchPolicyModel model,
        SearchExperienceRepository experienceRepository,
        String experienceFamily
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.experienceRepository = experienceRepository;
        this.experienceFamily = experienceFamily == null ? "" : experienceFamily;
    }

    @Override
    public String id() {
        return "empirical-" + model.mode().name().toLowerCase() + "/" + model.modelVersion();
    }

    @Override
    public PolicyDecision score(PolicyContext context, Transformation transformation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(transformation, "transformation");
        RuleStatistics statistics = model.rules().get(transformation.rule());
        if (!model.compatible()) {
            return fallback(context, "incompatible feature schema");
        }
        if (statistics == null || statistics.observations() < model.minimumObservations()) {
            return fallback(context, "missing or low-confidence rule statistics");
        }

        Map<String, Integer> contributions = new LinkedHashMap<>();
        int target = context.targeted() ? context.targetDistance() * TARGET_DISTANCE_WEIGHT : 0;
        int empiricalFailure = 1000 - statistics.successPermille();
        contributions.put("targetDistance", target);
        contributions.put("empiricalFailure", empiricalFailure);

        if (model.mode() != Mode.FREQUENCY) {
            contributions.put("meanScoreDelta", clamp(statistics.meanScoreDelta(), -200, 200));
            contributions.put("complexityIncrease", transformation.mayIncreaseComplexity() ? 150 : 0);
            contributions.put("nonEquivalence", transformation.equivalencePreservingByConstruction() ? 0 : 300);
            contributions.put("estimatedCostDelta", clamp(transformation.estimatedCostDelta() * 10, -200, 200));
        }
        if (model.mode() == Mode.LINEAR_WITH_EXPERIENCE) {
            contributions.put("experience", experienceContribution(context, transformation.rule()));
        }

        int priority = contributions.values().stream().mapToInt(Integer::intValue).sum();
        int confidence = Math.min(1000, statistics.observations() * 250);
        String explanation = contributions.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
        return new PolicyDecision(
            id(), priority, confidence, false, contributions,
            "transparent empirical score: " + explanation);
    }

    public SearchPolicyModel model() {
        return model;
    }

    private PolicyDecision fallback(PolicyContext context, String reason) {
        int target = context.targeted() ? context.targetDistance() * TARGET_DISTANCE_WEIGHT : 0;
        Map<String, Integer> contributions = Map.of(
            "targetDistance", target,
            "unknownRule", UNKNOWN_RULE_PENALTY);
        return new PolicyDecision(
            id(),
            target + UNKNOWN_RULE_PENALTY,
            0,
            true,
            contributions,
            "fallback to deterministic static ordering: " + reason);
    }

    private int experienceContribution(PolicyContext context, String ruleId) {
        if (experienceRepository == null || experienceFamily.isBlank()) {
            return 0;
        }
        String shape = ExpressionFingerprint.of(
            context.parentExpression(), context.canonicalizer()).alphaShapeHash();
        List<SearchExperience> experiences = experienceRepository.findByShape(
            experienceFamily, shape, 100);
        int contribution = 0;
        for (SearchExperience experience : experiences) {
            if (!experience.ruleId().equals(ruleId)) {
                continue;
            }
            contribution += experience.successfulChoice() ? -100 : 60;
        }
        return clamp(contribution, -300, 300);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
