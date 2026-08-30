package de.regelsuche.benchmark.polynomial;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executes frozen inputs while preserving exact run-local state boundaries. */
public final class PolynomialTheoryUtilityTargetBlindRunner {
    public PolynomialTheoryUtilityCandidateFreeze.Artifact execute(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        PolynomialTheoryUtilityAdapterRegistry registry
    ) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(registry, "registry");
        var formation = PolynomialTheoryUtilityCaseCorpus.load();
        List<PolynomialTheoryUtilityCaseCorpus.FormationCase> orderedCases =
            formation.cases();

        List<PolynomialTheoryUtilityCandidateFreeze.Row> rows =
            new ArrayList<>(inputs.inputs().size());
        Set<String> openedRunIds = new HashSet<>();
        PolynomialTheoryUtilityProfileAdapter.Run run = null;
        String currentRunId = null;
        String currentProfileId = null;
        String currentCheckpointId = null;
        String currentAdapterId = null;
        int runCaseCount = 0;
        int completedRuns = 0;
        try {
            for (var input : inputs.inputs()) {
                if (!input.runId().equals(currentRunId)) {
                    if (run != null) {
                        requireCompleteRun(runCaseCount, orderedCases.size());
                        var closing = run;
                        run = null;
                        closing.close();
                        completedRuns++;
                    }
                    if (!openedRunIds.add(input.runId())) {
                        throw new IllegalStateException(
                            "runner observed a non-contiguous repeated run"
                        );
                    }
                    currentRunId = input.runId();
                    currentProfileId = input.profileId();
                    currentCheckpointId = input.checkpointId();
                    currentAdapterId = input.adapterId();
                    var adapter = registry.require(
                        currentProfileId,
                        currentAdapterId
                    );
                    run = Objects.requireNonNull(
                        adapter.openRun(
                            new PolynomialTheoryUtilityProfileAdapter
                                .RunDescriptor(
                                    currentRunId,
                                    currentProfileId,
                                    currentCheckpointId,
                                    currentAdapterId,
                                    orderedCases.size()
                                )
                        ),
                        "adapter run"
                    );
                    runCaseCount = 0;
                } else if (!currentProfileId.equals(input.profileId())
                        || !currentCheckpointId.equals(input.checkpointId())
                        || !currentAdapterId.equals(input.adapterId())) {
                    throw new IllegalStateException(
                        "one run contains multiple frozen profile policies"
                    );
                }

                if (runCaseCount >= orderedCases.size()) {
                    throw new IllegalStateException(
                        "runner observed more cases than the frozen run"
                    );
                }
                var studyCase = orderedCases.get(runCaseCount);
                if (!studyCase.caseId().equals(input.caseId())) {
                    throw new IllegalStateException(
                        "execution input differs from frozen formation order: "
                            + input.caseId()
                    );
                }

                PolynomialTheoryUtilityProfileAdapter.Outcome outcome;
                try {
                    outcome = run.execute(input, studyCase);
                } catch (IllegalArgumentException exception) {
                    throw exception;
                } catch (RuntimeException exception) {
                    outcome = PolynomialTheoryUtilityProfileAdapter.Outcome
                        .technicalFailure(technicalDetail(exception));
                }
                outcome = Objects.requireNonNull(outcome, "adapter outcome");
                outcome.requireWithin(input);
                rows.add(PolynomialTheoryUtilityCandidateFreeze.row(
                    input,
                    outcome
                ));
                runCaseCount++;
            }
            if (run != null) {
                requireCompleteRun(runCaseCount, orderedCases.size());
                var closing = run;
                run = null;
                closing.close();
                completedRuns++;
            }
        } finally {
            if (run != null) {
                run.close();
            }
        }
        int expectedRuns =
            PolynomialTheoryUtilityExecutionPlan.PROFILES.size()
                * PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size();
        if (completedRuns != expectedRuns
                || openedRunIds.size() != expectedRuns) {
            throw new IllegalStateException(
                "runner did not execute exactly 30 isolated runs"
            );
        }
        return PolynomialTheoryUtilityCandidateFreeze.create(inputs, rows);
    }

    private static void requireCompleteRun(int count, int expected) {
        if (count != expected) {
            throw new IllegalStateException(
                "runner observed a partial run: expected=" + expected
                    + ", actual=" + count
            );
        }
    }

    private static String technicalDetail(RuntimeException exception) {
        String name = exception.getClass().getSimpleName()
            .replaceAll("[^A-Za-z0-9]", "_")
            .toUpperCase(java.util.Locale.ROOT);
        return "ADAPTER_EXCEPTION_" + (name.isBlank() ? "RUNTIME" : name);
    }
}
