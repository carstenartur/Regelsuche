package de.regelsuche.paths;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.search.TeachingPathScorer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares two {@link DiscoveredTransformation transformation paths}.
 *
 * <p>Surfaces shared nodes and rules, distinct steps, the score progression,
 * a didactic teaching score per side and a qualitative ranking
 * ({@code shorterPath}, {@code teachingPreferredPath}, {@code fewerAssumptionsPath}).
 * Used by {@code GET /api/paths/compare?left=...&right=...} and by the
 * side-by-side view of the workbench UI.</p>
 */
public final class PathComparisonService {

    private final TeachingPathScorer teachingScorer;

    public PathComparisonService() {
        this(new TeachingPathScorer());
    }

    public PathComparisonService(TeachingPathScorer teachingScorer) {
        this.teachingScorer = teachingScorer;
    }

    public PathComparisonDto compare(DiscoveredTransformation left, DiscoveredTransformation right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException("left and right paths are required");
        }
        Set<String> leftNodes = collectNodes(left);
        Set<String> rightNodes = collectNodes(right);
        Set<String> sharedNodes = intersection(leftNodes, rightNodes);

        Set<String> leftRules = collectRules(left);
        Set<String> rightRules = collectRules(right);
        Set<String> sharedRules = intersection(leftRules, rightRules);

        List<String> leftOnlySteps = stepsNotIn(left, right);
        List<String> rightOnlySteps = stepsNotIn(right, left);

        List<Integer> leftScores = scoreSeries(left);
        List<Integer> rightScores = scoreSeries(right);

        int leftAssumptions = countAssumptionSteps(left);
        int rightAssumptions = countAssumptionSteps(right);

        double leftTeaching = teachingScorer.score(left);
        double rightTeaching = teachingScorer.score(right);

        String shorterPath = pickShorter(left, right);
        String teachingPreferredPath = leftTeaching == rightTeaching
            ? "" : (leftTeaching > rightTeaching ? left.id() : right.id());
        String fewerAssumptionsPath = leftAssumptions == rightAssumptions
            ? "" : (leftAssumptions < rightAssumptions ? left.id() : right.id());

        return new PathComparisonDto(
            left.id(),
            right.id(),
            new ArrayList<>(sharedNodes),
            new ArrayList<>(sharedRules),
            leftOnlySteps,
            rightOnlySteps,
            leftScores,
            rightScores,
            leftTeaching,
            rightTeaching,
            left.validationStatus(),
            right.validationStatus(),
            leftAssumptions,
            rightAssumptions,
            shorterPath,
            teachingPreferredPath,
            fewerAssumptionsPath
        );
    }

    private static Set<String> collectNodes(DiscoveredTransformation path) {
        Set<String> result = new LinkedHashSet<>();
        result.add(path.originalExpression());
        for (TransformationStep step : path.steps()) {
            result.add(step.beforeExpression());
            result.add(step.afterExpression());
        }
        result.add(path.improvedExpression());
        return result;
    }

    private static Set<String> collectRules(DiscoveredTransformation path) {
        Set<String> rules = new LinkedHashSet<>();
        for (TransformationStep step : path.steps()) {
            rules.add(step.ruleId());
        }
        return rules;
    }

    private static List<String> stepsNotIn(DiscoveredTransformation source, DiscoveredTransformation other) {
        Set<String> otherKeys = new LinkedHashSet<>();
        for (TransformationStep step : other.steps()) {
            otherKeys.add(stepKey(step));
        }
        List<String> result = new ArrayList<>();
        for (TransformationStep step : source.steps()) {
            String key = stepKey(step);
            if (!otherKeys.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private static String stepKey(TransformationStep step) {
        return step.beforeExpression() + " --[" + step.ruleId() + "]--> " + step.afterExpression();
    }

    private static List<Integer> scoreSeries(DiscoveredTransformation path) {
        List<Integer> scores = new ArrayList<>();
        if (!path.steps().isEmpty()) {
            scores.add(path.steps().get(0).scoreBefore());
        } else {
            scores.add(path.originalScore().weightedTotal());
        }
        for (TransformationStep step : path.steps()) {
            scores.add(step.scoreAfter());
        }
        return scores;
    }

    private static int countAssumptionSteps(DiscoveredTransformation path) {
        int count = 0;
        for (TransformationStep step : path.steps()) {
            if (!step.equivalencePreserving()) {
                count++;
            }
        }
        return count;
    }

    private static <T> Set<T> intersection(Set<T> a, Set<T> b) {
        Set<T> shared = new LinkedHashSet<>(a);
        shared.retainAll(b);
        return shared;
    }

    private static String pickShorter(DiscoveredTransformation left, DiscoveredTransformation right) {
        int leftLength = left.steps().size();
        int rightLength = right.steps().size();
        if (leftLength == rightLength) {
            return "";
        }
        return leftLength < rightLength ? left.id() : right.id();
    }
}
