package de.regelsuche.search.program;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;

/** Result of interpreting one rewrite program for one input expression. */
public record RewriteExecution(
    List<RewriteCandidate> candidates,
    boolean complete,
    TransformationWorkMetrics workMetrics,
    List<SourceObservation> sourceObservations,
    BudgetedRewriteProgramExecution.PathBudget pathBudget
) {
    public RewriteExecution(List<RewriteCandidate> candidates, boolean complete, TransformationWorkMetrics workMetrics) {
        this(candidates, complete, workMetrics, List.of(), null);
    }
    public RewriteExecution(
        List<RewriteCandidate> candidates,
        boolean complete
    ) {
        this(candidates, complete, TransformationWorkMetrics.ZERO);
    }

    public RewriteExecution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        workMetrics = workMetrics == null
            ? TransformationWorkMetrics.ZERO
            : workMetrics;
        sourceObservations = List.copyOf(sourceObservations);
        if (pathBudget == null) {
            if (!sourceObservations.isEmpty()
                    || !workMetrics.candidateWork().equals(de.regelsuche.transform.ExecutionWork.ZERO)) {
                throw new IllegalArgumentException("mathematical work observations require an explicit path budget");
            }
            if (candidates.stream().flatMap(candidate -> candidate.steps().stream())
                    .anyMatch(step -> step.exactTheoryStepCount() != 0)) {
                throw new IllegalArgumentException("theory candidates require explicit path authority");
            }
        } else {
            var observedWork = sourceObservations.stream().map(value -> value.candidate().executionWork())
                .reduce(de.regelsuche.transform.ExecutionWork.ZERO, de.regelsuche.transform.ExecutionWork::plus);
            if (!observedWork.equals(workMetrics.candidateWork())
                    || candidates.stream().anyMatch(candidate -> !pathBudget.admits(candidate.executionWork()))
                    || (complete && sourceObservations.stream().anyMatch(value -> !value.admitted()))) {
                throw new IllegalArgumentException("execution budget, completeness or work differs from observations");
            }
            var admitted = new java.util.HashSet<BoundStep>();
            for (SourceObservation observation : sourceObservations) {
                if (observation.availableBudget().primitiveRewriteUnits() > pathBudget.primitiveRewriteUnits()
                        || observation.availableBudget().exactTheoryWorkUnits() > pathBudget.exactTheoryWorkUnits()) {
                    throw new IllegalArgumentException("source observation exceeds root authority");
                }
                if (observation.admitted()) admitted.add(new BoundStep(observation.candidate().inputExpression(),
                    observation.candidate().steps().getFirst()));
            }
            for (RewriteCandidate candidate : candidates) {
                String current = candidate.inputExpression();
                for (Transformation step : candidate.steps()) {
                    if (!admitted.contains(new BoundStep(current, step))) {
                        throw new IllegalArgumentException("candidate contains an unobserved or rejected source step");
                    }
                    current = step.transformedExpression();
                }
            }
        }
    }

    public List<Transformation> transformations() {
        return candidates.stream()
            .map(RewriteCandidate::toTransformation)
            .toList();
    }

    public String workRevision() {
        return pathBudget == null ? "regelsuche.rewrite-program-work/v1" : "regelsuche.rewrite-program-work/v2";
    }

    private record BoundStep(String inputExpression, Transformation transformation) {}

    /** Retains evidence and required work before any budget/filter/dedup/prune decision. */
    public record SourceObservation(RewriteCandidate candidate,
                                    BudgetedRewriteProgramExecution.PathBudget availableBudget,
                                    boolean admitted) {
        public SourceObservation {
            java.util.Objects.requireNonNull(candidate, "candidate");
            java.util.Objects.requireNonNull(availableBudget, "availableBudget");
            if (candidate.steps().size() != 1) {
                throw new IllegalArgumentException("a source observation retains exactly one emitted transformation");
            }
            if (admitted != availableBudget.admits(candidate.executionWork())) {
                throw new IllegalArgumentException("source admission differs from its bound path budget");
            }
        }
    }
}
