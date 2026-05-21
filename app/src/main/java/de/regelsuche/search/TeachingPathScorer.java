package de.regelsuche.search;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleStatus;
import java.util.List;
import java.util.Objects;

/**
 * Scores a {@link DiscoveredTransformation transformation path} for didactic
 * suitability (used by {@code sort=teaching}).
 *
 * <p>A higher score indicates a better teaching path. All component metrics
 * are derived from data already available on {@link TransformationStep}; no
 * new persistence is introduced.</p>
 *
 * <ul>
 *   <li><strong>stepCount</strong> – shorter is better.</li>
 *   <li><strong>usesKnownRulesRatio</strong> – fraction of steps whose rule
 *       id corresponds to a {@link RuleStatus#MATCHES_KNOWN_RULE known rule}
 *       textbook formula.</li>
 *   <li><strong>expansionPenalty</strong> – penalises steps that increase
 *       complexity ({@code scoreAfter &gt; scoreBefore}).</li>
 *   <li><strong>monotonicityScore</strong> – rewards a strictly non-increasing
 *       complexity sequence (no detours).</li>
 *   <li><strong>justificationCoverage</strong> – fraction of steps with a
 *       non-empty {@code explanation} field.</li>
 * </ul>
 *
 * <p>See {@code docs/didactic-ranking.md} for the weighting rationale.</p>
 */
public final class TeachingPathScorer {

    /** Per-component weights – sum is intentionally 1.0 for interpretability. */
    public static final double W_STEP_COUNT = 0.30;
    public static final double W_KNOWN = 0.20;
    public static final double W_EXPANSION = 0.20;
    public static final double W_MONOTONIC = 0.20;
    public static final double W_JUSTIFICATION = 0.10;

    private final KnownRuleRepository knownRules;

    public TeachingPathScorer() {
        this(new KnownRuleRepository());
    }

    public TeachingPathScorer(KnownRuleRepository knownRules) {
        this.knownRules = Objects.requireNonNull(knownRules);
    }

    /**
     * @return a value in [0.0, 1.0]; higher means better teaching path.
     */
    public double score(DiscoveredTransformation transformation) {
        List<TransformationStep> steps = transformation.steps();
        if (steps.isEmpty()) {
            return 0.0;
        }
        double stepCountScore = 1.0 / (1.0 + steps.size());
        double knownRatio = computeKnownRatio(steps);
        double expansionRatio = computeExpansionRatio(steps);
        double monotonic = computeMonotonicity(steps);
        double coverage = computeJustificationCoverage(steps);

        return clamp(
            W_STEP_COUNT * stepCountScore
                + W_KNOWN * knownRatio
                + W_EXPANSION * (1.0 - expansionRatio)
                + W_MONOTONIC * monotonic
                + W_JUSTIFICATION * coverage
        );
    }

    private double computeKnownRatio(List<TransformationStep> steps) {
        long hits = steps.stream()
            .filter(step -> knownRules.statusFor(step.beforeExpression(), step.afterExpression())
                == RuleStatus.MATCHES_KNOWN_RULE)
            .count();
        return (double) hits / (double) steps.size();
    }

    private double computeExpansionRatio(List<TransformationStep> steps) {
        long expansions = steps.stream()
            .filter(step -> step.scoreAfter() > step.scoreBefore())
            .count();
        return (double) expansions / (double) steps.size();
    }

    private double computeMonotonicity(List<TransformationStep> steps) {
        if (steps.size() < 2) {
            return 1.0;
        }
        int monotonicTransitions = 0;
        int totalTransitions = 0;
        int previousAfter = steps.get(0).scoreAfter();
        for (int i = 1; i < steps.size(); i++) {
            int current = steps.get(i).scoreAfter();
            if (current <= previousAfter) {
                monotonicTransitions++;
            }
            totalTransitions++;
            previousAfter = current;
        }
        return totalTransitions == 0 ? 1.0 : (double) monotonicTransitions / (double) totalTransitions;
    }

    private double computeJustificationCoverage(List<TransformationStep> steps) {
        long withExplanation = steps.stream()
            .filter(step -> step.explanation() != null && !step.explanation().isBlank())
            .count();
        return (double) withExplanation / (double) steps.size();
    }

    private static double clamp(double value) {
        if (value < 0.0) return 0.0;
        if (value > 1.0) return 1.0;
        return value;
    }
}
