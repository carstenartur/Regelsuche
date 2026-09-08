package de.regelsuche.export;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.transform.RecordedExecution;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Fresh work-budgeted replay of an imported path, never hydration of executable JSON evidence. */
public final class RecordedPathReplay {
    private RecordedPathReplay() { }

    public static Result verify(DiscoveredTransformation artifact, Supplier<Problem> independentlyVerifiedProblem) {
        Objects.requireNonNull(artifact, "artifact");
        String current = artifact.originalExpression();
        for (int i = 0; i < artifact.steps().size(); i++) {
            var step = artifact.steps().get(i);
            if (step.index() != i || step.execution() == null || !step.beforeExpression().equals(current)) {
                throw new IllegalArgumentException("stored path lacks complete, continuous execution observations");
            }
            current = step.afterExpression();
        }
        if (!current.equals(artifact.improvedExpression())) throw new IllegalArgumentException("stored path endpoint differs");
        Problem problem = Objects.requireNonNull(independentlyVerifiedProblem, "verified problem source").get();
        if (!problem.inputExpression().equals(artifact.originalExpression())
                || !problem.targetExpression().equals(artifact.improvedExpression())) {
            throw new IllegalArgumentException("replay problem differs from stored path endpoints");
        }
        Result replay = new WorkBudgetBestFirstSearchStrategy().search(problem);
        if (!replay.reached() || replay.reachedState().transformations().size() != artifact.steps().size()) {
            throw new IllegalArgumentException("verified search did not reproduce the stored path");
        }
        for (int i = 0; i < artifact.steps().size(); i++) {
            var recorded = artifact.steps().get(i);
            var actual = replay.reachedState().transformations().get(i);
            if (!recorded.execution().equals(RecordedExecution.capture(recorded.beforeExpression(), List.of(actual)))
                    || recorded.scoreBefore() != problem.scorer().score(recorded.beforeExpression()).weightedTotal()
                    || recorded.scoreAfter() != problem.scorer().score(recorded.afterExpression()).weightedTotal()) {
                throw new IllegalArgumentException("stored step differs in provenance, evidence, work or score");
            }
        }
        return replay;
    }
}
