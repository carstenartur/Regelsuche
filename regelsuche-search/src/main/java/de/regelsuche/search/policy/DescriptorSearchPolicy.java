package de.regelsuche.search.policy;

import de.regelsuche.search.learning.ExpressionFingerprint;
import de.regelsuche.search.learning.SearchExperienceRepository;
import de.regelsuche.search.learning.SearchExperienceRepository.SearchExperience;
import de.regelsuche.search.learning.TransformationDescriptor;
import de.regelsuche.search.policy.DescriptorPolicyModel.DescriptorStatistics;
import de.regelsuche.search.policy.DescriptorPolicyModel.FeatureStatistics;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transparent policy for already-applicable transformations using only
 * rule-independent descriptor evidence learned from TRAIN decisions.
 */
public final class DescriptorSearchPolicy implements SearchPolicy, AutoCloseable {
    private static final int TARGET_DISTANCE_WEIGHT = 20;
    private static final int FALLBACK_PENALTY = 1000;
    private static final int PRIORITY_LIMIT = Integer.MAX_VALUE / 4;

    private final DescriptorPolicyModel model;
    private final TransformationDescriptor.Factory descriptorFactory;
    private final boolean targeted;
    private final SearchExperienceRepository experiences;
    private final List<String> experienceFamilies;
    private final Map<ExperienceKey, List<SearchExperience>> experienceCache = new LinkedHashMap<>();
    private boolean closed;

    public DescriptorSearchPolicy(DescriptorPolicyModel model, SearchProblem problem) {
        this(model, problem, null, List.of());
    }

    public DescriptorSearchPolicy(
        DescriptorPolicyModel model,
        SearchProblem problem,
        SearchExperienceRepository experiences,
        Collection<String> experienceFamilies
    ) {
        this.model = Objects.requireNonNull(model, "model");
        Objects.requireNonNull(problem, "problem");
        descriptorFactory = new TransformationDescriptor.Factory(
            problem.target(), problem.canonicalizer());
        targeted = problem.target() != null;
        this.experiences = experiences;
        List<String> sortedFamilies = new ArrayList<>(
            experienceFamilies == null ? List.of() : experienceFamilies);
        sortedFamilies.removeIf(family -> family == null || family.isBlank());
        sortedFamilies.sort(String::compareTo);
        this.experienceFamilies = List.copyOf(sortedFamilies);
    }

    @Override
    public String id() {
        String suffix = experiences == null || experienceFamilies.isEmpty()
            ? ""
            : "+structural-experience";
        return "descriptor-" + model.mode().name().toLowerCase()
            + suffix + "/" + model.modelVersion();
    }

    @Override
    public PolicyDecision score(PolicyContext context, Transformation transformation) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(transformation, "transformation");
        if (closed) {
            throw new IllegalStateException("descriptor policy is closed");
        }
        if (!model.compatible()) {
            return fallback(context, "incompatible descriptor schema");
        }
        if (context.targeted() != targeted) {
            return fallback(context, "policy target context does not match the search problem");
        }

        TransformationDescriptor descriptor;
        try {
            descriptor = descriptor(context, transformation);
        } catch (IllegalArgumentException exception) {
            return fallback(context, "descriptor derivation failed");
        }
        if (!descriptor.available()) {
            return fallback(context, "descriptor is unavailable");
        }
        return switch (model.mode()) {
            case FREQUENCY -> frequencyDecision(context, transformation, descriptor);
            case LINEAR -> linearDecision(context, transformation, descriptor);
        };
    }

    private PolicyDecision frequencyDecision(
        PolicyContext context,
        Transformation transformation,
        TransformationDescriptor descriptor
    ) {
        DescriptorStatistics statistics = model.descriptors().get(
            descriptor.predictiveFingerprint());
        if (statistics == null || statistics.observations() < model.minimumObservations()) {
            return fallback(context, "missing or under-observed descriptor frequency");
        }
        Map<String, Integer> contributions = new LinkedHashMap<>();
        contributions.put("targetDistance", targetContribution(context));
        contributions.put("descriptorFailure", 1000 - statistics.successPermille());
        int experience = experienceContribution(context, transformation);
        if (experience != 0) {
            contributions.put("structuralExperience", experience);
        }
        return decision(
            descriptor,
            contributions,
            Math.min(1000, statistics.observations() * 250),
            "exact descriptor frequency");
    }

    private PolicyDecision linearDecision(
        PolicyContext context,
        Transformation transformation,
        TransformationDescriptor descriptor
    ) {
        Map<String, Integer> vector = descriptor.featureVector();
        Map<String, Integer> contributions = new LinkedHashMap<>();
        contributions.put("targetDistance", targetContribution(context));
        int informative = 0;
        int supported = 0;
        int outOfRange = 0;
        int minimumEvidence = Integer.MAX_VALUE;

        for (Map.Entry<String, FeatureStatistics> entry : model.features().entrySet()) {
            FeatureStatistics statistics = entry.getValue();
            if (statistics.observations() < model.minimumObservations()
                    || statistics.coefficientPermille() == 0) {
                continue;
            }
            informative++;
            int value = vector.getOrDefault(entry.getKey(), 0);
            if (value < statistics.minimumValue() || value > statistics.maximumValue()) {
                outOfRange++;
                continue;
            }
            int normalized = normalizedPermille(
                value, statistics.minimumValue(), statistics.maximumValue());
            int contribution = (int) ((long) statistics.coefficientPermille()
                * normalized / 1000L);
            if (contribution != 0) {
                contributions.put("feature." + entry.getKey(), contribution);
            }
            supported++;
            minimumEvidence = Math.min(minimumEvidence, statistics.observations());
        }
        if (supported == 0) {
            return fallback(context, informative == 0
                ? "model contains no informative descriptor features"
                : "all informative descriptor features are out of range");
        }

        int experience = experienceContribution(context, transformation);
        if (experience != 0) {
            contributions.put("structuralExperience", experience);
        }
        int coverage = supported * 1000 / Math.max(1, informative);
        int evidence = Math.min(1000, minimumEvidence * 250);
        return decision(
            descriptor,
            contributions,
            Math.min(coverage, evidence),
            "linear descriptor evidence; supported=" + supported
                + ", informative=" + informative
                + ", outOfRange=" + outOfRange);
    }

    private PolicyDecision decision(
        TransformationDescriptor descriptor,
        Map<String, Integer> contributions,
        int confidence,
        String evidence
    ) {
        int priority = boundedSum(contributions.values());
        String explanation = "transparent descriptor score: fingerprint="
            + descriptor.predictiveFingerprint() + "; " + evidence;
        return new PolicyDecision(
            id(), priority, confidence, false, contributions, explanation);
    }

    private PolicyDecision fallback(PolicyContext context, String reason) {
        int target = targetContribution(context);
        return new PolicyDecision(
            id(),
            boundedSum(List.of(target, FALLBACK_PENALTY)),
            0,
            true,
            Map.of("descriptorFallback", FALLBACK_PENALTY, "targetDistance", target),
            "fallback to deterministic static ordering: " + reason);
    }

    private TransformationDescriptor descriptor(
        PolicyContext context,
        Transformation transformation
    ) {
        SearchEvent event = new SearchEvent(
            0,
            SearchEventType.TRANSFORMATION_GENERATED,
            transformation.transformedExpression(),
            "",
            0,
            0,
            "",
            context.parentExpression(),
            transformation.rule(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            transformation.assumptions(),
            0,
            0,
            0,
            "");
        return descriptorFactory.from(event);
    }

    private int experienceContribution(
        PolicyContext context,
        Transformation transformation
    ) {
        if (experiences == null || experienceFamilies.isEmpty()) {
            return 0;
        }
        ExpressionFingerprint parent;
        ExpressionFingerprint child;
        try {
            parent = ExpressionFingerprint.of(
                context.parentExpression(), context.canonicalizer());
            child = ExpressionFingerprint.of(
                transformation.transformedExpression(), context.canonicalizer());
        } catch (IllegalArgumentException exception) {
            return 0;
        }
        int contribution = 0;
        for (String family : experienceFamilies) {
            ExperienceKey key = new ExperienceKey(family, parent.alphaShapeHash());
            List<SearchExperience> matching = experienceCache.computeIfAbsent(
                key,
                ignored -> List.copyOf(experiences.findByShape(
                    family, parent.alphaShapeHash(), 100)));
            for (SearchExperience experience : matching) {
                if (!experience.childAlphaShapeHash().equals(child.alphaShapeHash())) {
                    continue;
                }
                contribution += experience.successfulChoice() ? -100 : 60;
            }
        }
        return Math.max(-300, Math.min(300, contribution));
    }

    private static int targetContribution(PolicyContext context) {
        return context.targeted()
            ? bounded((long) context.targetDistance() * TARGET_DISTANCE_WEIGHT)
            : 0;
    }

    private static int normalizedPermille(int value, int minimum, int maximum) {
        long span = (long) maximum - minimum;
        return span == 0L ? 0 : (int) (((long) value - minimum) * 1000L / span);
    }

    private static int boundedSum(Collection<Integer> values) {
        long sum = 0;
        for (int value : values) {
            sum += value;
        }
        return bounded(sum);
    }

    private static int bounded(long value) {
        return (int) Math.max(-PRIORITY_LIMIT, Math.min(PRIORITY_LIMIT, value));
    }

    @Override
    public void close() {
        if (!closed) {
            experienceCache.clear();
            descriptorFactory.close();
            closed = true;
        }
    }

    private record ExperienceKey(String family, String parentAlphaShapeHash) {
    }
}
