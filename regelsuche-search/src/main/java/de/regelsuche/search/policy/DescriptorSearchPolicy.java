package de.regelsuche.search.policy;

import de.regelsuche.search.learning.ExpressionFingerprint;
import de.regelsuche.search.learning.SearchExperienceRepository;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.DescriptorStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.transform.Transformation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transparent ranking policy that transfers through transformation descriptors
 * instead of concrete rule IDs. It only scores transformations that the engine
 * already reported as applicable and falls back safely when descriptor evidence
 * is unavailable, incompatible or insufficient.
 */
public final class DescriptorSearchPolicy implements SearchPolicy {
    private static final int TARGET_DISTANCE_WEIGHT = 20;
    private static final int UNKNOWN_DESCRIPTOR_PENALTY = 2_000;

    private final DescriptorPolicyModel model;
    private final SearchExperienceRepository experienceRepository;
    private final String experienceFamily;

    public DescriptorSearchPolicy(DescriptorPolicyModel model) {
        this(model, null, "");
    }

    public DescriptorSearchPolicy(
        DescriptorPolicyModel model,
        SearchExperienceRepository experienceRepository,
        String experienceFamily
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.experienceRepository = experienceRepository;
        this.experienceFamily = experienceFamily == null ? "" : experienceFamily;
    }

    @Override
    public String id() {
        String experience = experienceRepository == null ? "" : "+experience";
        return "descriptor-" + model.mode().name().toLowerCase()
            + experience + "/" + model.modelVersion();
    }

    @Override
    public PolicyDecision score(PolicyContext context, Transformation transformation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(transformation, "transformation");
        if (!model.compatible()) {
            return fallback(context, "incompatible descriptor schema");
        }
        TransformationDescriptor descriptor = context.transformationDescriptor();
        if (descriptor == null || !descriptor.available()) {
            return fallback(context, "descriptor unavailable");
        }
        return switch (model.mode()) {
            case FREQUENCY -> frequency(context, transformation, descriptor);
            case LINEAR -> linear(context, transformation, descriptor);
        };
    }

    public DescriptorPolicyModel model() {
        return model;
    }

    private PolicyDecision frequency(
        PolicyContext context,
        Transformation transformation,
        TransformationDescriptor descriptor
    ) {
        DescriptorStatistics statistics = model.descriptors().get(
            descriptor.predictiveFingerprint());
        if (statistics == null || statistics.observations() < model.minimumObservations()) {
            return fallback(context, "missing or low-confidence exact descriptor statistics");
        }
        Map<String, Integer> contributions = baseContributions(context);
        contributions.put("descriptorFailure", 1000 - statistics.successPermille());
        contributions.put("descriptorMeanScoreDelta", clamp(statistics.meanScoreDelta(), -200, 200));
        addExperience(contributions, context, transformation);
        return decision(
            contributions,
            Math.min(1000, statistics.observations() * 250),
            "exact rule-independent descriptor frequency evidence");
    }

    private PolicyDecision linear(
        PolicyContext context,
        Transformation transformation,
        TransformationDescriptor descriptor
    ) {
        Map<String, Integer> descriptorFeatures = descriptor.featureVector();
        Map<String, Integer> contributions = baseContributions(context);
        int informative = 0;
        int outOfRange = 0;
        int minimumEvidence = Integer.MAX_VALUE;
        for (Map.Entry<String, FeatureStatistics> entry : model.features().entrySet()) {
            String name = entry.getKey();
            FeatureStatistics statistics = entry.getValue();
            if (statistics.observations() < model.minimumObservations()
                    || statistics.coefficientPermille() == 0) {
                continue;
            }
            int value = descriptorFeatures.getOrDefault(name, 0);
            if (value < statistics.minimumValue() || value > statistics.maximumValue()) {
                outOfRange++;
                continue;
            }
            int span = statistics.maximumValue() - statistics.minimumValue();
            if (span == 0) {
                continue;
            }
            long centered = 2L * value
                - statistics.minimumValue()
                - statistics.maximumValue();
            int contribution = clamp((int) (statistics.coefficientPermille()
                * centered / span), -1000, 1000);
            if (contribution != 0) {
                contributions.put("descriptor." + name, contribution);
            }
            informative++;
            minimumEvidence = Math.min(minimumEvidence, statistics.observations());
        }
        if (informative == 0) {
            String reason = outOfRange > 0
                ? "all informative descriptor features are out of TRAIN range"
                : "missing or under-observed shared descriptor features";
            return fallback(context, reason);
        }
        addExperience(contributions, context, transformation);
        int confidence = Math.min(1000,
            minimumEvidence * 100 + informative * 50);
        return decision(
            contributions,
            confidence,
            "transparent linear descriptor evidence from " + informative
                + " shared features; ignoredOutOfRange=" + outOfRange);
    }

    private Map<String, Integer> baseContributions(PolicyContext context) {
        Map<String, Integer> contributions = new LinkedHashMap<>();
        contributions.put("targetDistance",
            context.targeted() ? context.targetDistance() * TARGET_DISTANCE_WEIGHT : 0);
        return contributions;
    }

    private void addExperience(
        Map<String, Integer> contributions,
        PolicyContext context,
        Transformation transformation
    ) {
        int experience = experienceContribution(context, transformation);
        if (experience != 0) {
            contributions.put("structuralExperience", experience);
        }
    }

    private int experienceContribution(
        PolicyContext context,
        Transformation transformation
    ) {
        if (experienceRepository == null || experienceFamily.isBlank()) {
            return 0;
        }
        ExpressionFingerprint parent = ExpressionFingerprint.of(
            context.parentExpression(), context.canonicalizer());
        ExpressionFingerprint child = ExpressionFingerprint.of(
            transformation.transformedExpression(), context.canonicalizer());
        List<SearchExperience> experiences = experienceRepository.findByShape(
            experienceFamily, parent.alphaShapeHash(), 100);
        int contribution = 0;
        for (SearchExperience experience : experiences) {
            if (!experience.childAlphaShapeHash().equals(child.alphaShapeHash())) {
                continue;
            }
            contribution += experience.successfulChoice() ? -100 : 60;
        }
        return clamp(contribution, -300, 300);
    }

    private PolicyDecision decision(
        Map<String, Integer> contributions,
        int confidence,
        String evidence
    ) {
        int priority = contributions.values().stream().mapToInt(Integer::intValue).sum();
        String explanation = contributions.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
        return new PolicyDecision(
            id(), priority, confidence, false, contributions,
            evidence + ": " + explanation);
    }

    private PolicyDecision fallback(PolicyContext context, String reason) {
        int target = context.targeted() ? context.targetDistance() * TARGET_DISTANCE_WEIGHT : 0;
        Map<String, Integer> contributions = Map.of(
            "targetDistance", target,
            "unknownDescriptor", UNKNOWN_DESCRIPTOR_PENALTY);
        return new PolicyDecision(
            id(), target + UNKNOWN_DESCRIPTOR_PENALTY, 0, true, contributions,
            "fallback to deterministic static ordering: " + reason);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
