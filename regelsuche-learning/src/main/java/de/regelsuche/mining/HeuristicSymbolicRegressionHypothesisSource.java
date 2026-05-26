package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Heuristic evidence-only source that proposes repeated input/output shapes as hypotheses only. */
public final class HeuristicSymbolicRegressionHypothesisSource implements SymbolicRegressionHypothesisSource {
    private final boolean enabled;
    private final int minimumSupport;

    public HeuristicSymbolicRegressionHypothesisSource(boolean enabled, int minimumSupport) {
        this.enabled = enabled;
        this.minimumSupport = Math.max(2, minimumSupport);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<HypothesisCandidate> propose(List<SuccessfulTransformationPath> paths) {
        if (!enabled || paths == null || paths.size() < minimumSupport) {
            return List.of();
        }
        Map<String, List<SuccessfulTransformationPath>> grouped = paths.stream()
            .filter(path -> path != null && path.originalExpression() != null && path.targetExpression() != null)
            .collect(java.util.stream.Collectors.groupingBy(path ->
                shape(path.originalExpression()) + " -> " + shape(path.targetExpression())));
        return grouped.entrySet().stream()
            .filter(entry -> entry.getValue().size() >= minimumSupport)
            .sorted(Comparator.comparing(Map.Entry::getKey))
            .map(entry -> hypothesis(entry.getKey(), entry.getValue()))
            .toList();
    }

    private HypothesisCandidate hypothesis(String shape, List<SuccessfulTransformationPath> paths) {
        String id = "symreg-" + Integer.toHexString(shape.hashCode());
        List<String> supportingIds = paths.stream().map(SuccessfulTransformationPath::id).sorted().toList();
        List<HypothesisCandidate.ExpressionPair> witnesses = paths.stream()
            .map(path -> new HypothesisCandidate.ExpressionPair(path.originalExpression(), path.targetExpression()))
            .toList();
        return new HypothesisCandidate(
            id,
            shape.substring(0, shape.indexOf(" -> ")),
            shape.substring(shape.indexOf(" -> ") + 4),
            supportingIds,
            witnesses,
            List.of("symbolic-regression-evidence-only"),
            0.0,
            CandidateProofStatus.OBSERVED,
            null,
            List.of(),
            Map.of("source", List.of("heuristic-symbolic-regression")),
            Instant.now()
        );
    }

    private static String shape(String expression) {
        return expression.replaceAll("\\d+(?:\\.\\d+)?", "N")
            .replaceAll("[a-zA-Z][a-zA-Z0-9_]*", "v")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
