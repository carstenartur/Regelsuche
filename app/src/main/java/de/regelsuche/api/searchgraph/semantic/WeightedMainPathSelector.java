package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class WeightedMainPathSelector implements MainPathSelector {

    private final RewriteSignalClassifier classifier;

    public WeightedMainPathSelector() {
        this(new RewriteSignalClassifier());
    }

    public WeightedMainPathSelector(RewriteSignalClassifier classifier) {
        this.classifier = Objects.requireNonNull(classifier);
    }

    @Override
    public Optional<DiscoveredTransformation> selectMainPath(
        List<DiscoveredTransformation> paths,
        SearchGraphDto rawGraph,
        MainPathCriteria criteria
    ) {
        if (paths == null || paths.isEmpty()) {
            return Optional.empty();
        }
        MainPathCriteria c = criteria == null ? MainPathCriteria.defaults() : criteria;
        List<DiscoveredTransformation> candidates = paths.stream()
            .filter(this::isNonDegenerate)
            .toList();
        if (candidates.isEmpty()) {
            candidates = paths;
        }
        return candidates.stream()
            .max(Comparator.comparingDouble((DiscoveredTransformation p) -> score(p, rawGraph, c))
                .thenComparingInt(DiscoveredTransformation::totalImprovement)
                .thenComparing(p -> p.id() == null ? "" : p.id()));
    }

    private boolean isNonDegenerate(DiscoveredTransformation path) {
        return path != null
            && !Objects.equals(path.originalExpression(), path.improvedExpression())
            && !path.steps().isEmpty();
    }

    private double score(DiscoveredTransformation path, SearchGraphDto rawGraph, MainPathCriteria c) {
        int lowSignal = countLowSignal(path, rawGraph);
        int assumptions = path.steps().stream().mapToInt(s -> s.assumptions().size()).sum();
        long macroSteps = path.steps().stream().filter(this::isMacroLike).count();
        return c.complexityReductionWeight() * path.totalImprovement()
            + c.proofConfidenceWeight() * path.validationStatus().ordinal()
            + c.macroCompressionWeight() * macroSteps
            + 0.9 * explanatoryStepCount(path, rawGraph)
            + c.teachingScoreWeight() * Math.max(0, path.totalImprovement() - path.steps().size())
            - c.lowSignalPenalty() * lowSignal
            - c.lengthPenalty() * path.steps().size()
            - c.assumptionPenalty() * assumptions;
    }

    private int explanatoryStepCount(DiscoveredTransformation path, SearchGraphDto rawGraph) {
        int count = 0;
        for (TransformationStep step : path.steps()) {
            if (step.ruleKind() == de.regelsuche.transform.RewriteKind.EXPAND || step.ruleKind() == de.regelsuche.transform.RewriteKind.SIMPLIFY) {
                count++;
                continue;
            }
            boolean highOrMedium = false;
            if (rawGraph != null) {
                for (SearchGraphEdgeDto edge : rawGraph.edges()) {
                    if (edge.from().equals(step.beforeExpression())
                        && edge.to().equals(step.afterExpression())
                        && edge.ruleId().equals(step.ruleId())
                        && classifier.classify(edge) != RewriteSignal.LOW_SIGNAL) {
                        highOrMedium = true;
                        break;
                    }
                }
            }
            if (highOrMedium) {
                count++;
            }
        }
        return count;
    }

    private int countLowSignal(DiscoveredTransformation path, SearchGraphDto rawGraph) {
        if (rawGraph == null) {
            return 0;
        }
        int low = 0;
        for (TransformationStep step : path.steps()) {
            for (SearchGraphEdgeDto edge : rawGraph.edges()) {
                if (edge.from().equals(step.beforeExpression())
                    && edge.to().equals(step.afterExpression())
                    && edge.ruleId().equals(step.ruleId())
                    && classifier.classify(edge) == RewriteSignal.LOW_SIGNAL) {
                    low++;
                    break;
                }
            }
        }
        return low;
    }

    private boolean isMacroLike(TransformationStep step) {
        String id = step.ruleId().toLowerCase(java.util.Locale.ROOT);
        return id.startsWith("macro") || id.contains("binomial") || id.contains("expand");
    }
}
