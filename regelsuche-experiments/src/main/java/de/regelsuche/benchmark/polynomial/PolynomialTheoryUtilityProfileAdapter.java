package de.regelsuche.benchmark.polynomial;

import java.util.List;
import java.util.Objects;

/**
 * Target-blind execution boundary for one preregistered utility-study profile.
 *
 * <p>An adapter receives only one frozen execution input, its visible formation
 * case and run-local mutable state. Qualification, other profile results and a
 * product decision are deliberately absent from this API.</p>
 */
public interface PolynomialTheoryUtilityProfileAdapter {
    String profileId();

    String adapterId();

    Run openRun(RunDescriptor descriptor);

    interface Run extends AutoCloseable {
        Outcome execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        );

        @Override
        default void close() {
            // Most adapters own no external resource.
        }
    }

    record RunDescriptor(
        String runId,
        String profileId,
        String checkpointId,
        String adapterId,
        int expectedCaseCount
    ) {
        public RunDescriptor {
            runId = requireText(runId, "runId");
            profileId = requireText(profileId, "profileId");
            checkpointId = requireText(checkpointId, "checkpointId");
            adapterId = requireText(adapterId, "adapterId");
            if (expectedCaseCount < 1) {
                throw new IllegalArgumentException(
                    "expectedCaseCount must be positive"
                );
            }
        }
    }

    enum TerminalStatus {
        NO_TRANSITION,
        TRANSITION,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }

    record Outcome(
        TerminalStatus terminalStatus,
        String detailCode,
        String generatedExpression,
        String transformationId,
        String verifierStatus,
        long primitiveWork,
        long sourceValidationWork,
        long factorizationWork,
        long renderReparseWork,
        long cacheLookupWork,
        long cacheReplayWork,
        long otherMechanicalWork,
        int factorizationRequests,
        int factorizationCandidates,
        int generatedTransitions,
        int pathDepth,
        int primitiveExpansionLength,
        int sourceAstNodes,
        int transformedAstNodes,
        int cacheHits,
        int cacheMisses,
        int cacheInsertions,
        int cacheEvictions,
        List<String> primitiveRuleIds,
        List<String> lineageIds
    ) {
        public Outcome {
            terminalStatus = Objects.requireNonNull(
                terminalStatus,
                "terminalStatus"
            );
            detailCode = requireText(detailCode, "detailCode");
            generatedExpression = Objects.requireNonNull(
                generatedExpression,
                "generatedExpression"
            );
            transformationId = Objects.requireNonNull(
                transformationId,
                "transformationId"
            );
            verifierStatus = requireText(verifierStatus, "verifierStatus");
            primitiveRuleIds = requireTexts(
                primitiveRuleIds,
                "primitiveRuleIds"
            );
            lineageIds = requireTexts(lineageIds, "lineageIds");
            if (primitiveWork < 0
                    || sourceValidationWork < 0
                    || factorizationWork < 0
                    || renderReparseWork < 0
                    || cacheLookupWork < 0
                    || cacheReplayWork < 0
                    || otherMechanicalWork < 0
                    || factorizationRequests < 0
                    || factorizationCandidates < 0
                    || generatedTransitions < 0
                    || pathDepth < 0
                    || primitiveExpansionLength < 0
                    || sourceAstNodes < 0
                    || transformedAstNodes < 0
                    || cacheHits < 0
                    || cacheMisses < 0
                    || cacheInsertions < 0
                    || cacheEvictions < 0) {
                throw new IllegalArgumentException(
                    "outcome counters must not be negative"
                );
            }
            boolean transition = terminalStatus == TerminalStatus.TRANSITION;
            if (transition) {
                if (generatedTransitions < 1
                        || generatedExpression.isBlank()
                        || transformationId.isBlank()) {
                    throw new IllegalArgumentException(
                        "transition outcome lacks transition evidence"
                    );
                }
            } else if (generatedTransitions != 0
                    || !generatedExpression.isBlank()
                    || !transformationId.isBlank()) {
                throw new IllegalArgumentException(
                    "non-transition outcome retains transition evidence"
                );
            }
            if (factorizationCandidates > 0 && factorizationRequests == 0) {
                throw new IllegalArgumentException(
                    "factorization candidates require a request"
                );
            }
            if (cacheEvictions > cacheInsertions) {
                throw new IllegalArgumentException(
                    "cache evictions exceed insertions for one input"
                );
            }
        }

        public static Outcome noTransition(String detailCode) {
            return empty(
                TerminalStatus.NO_TRANSITION,
                detailCode,
                "NOT_REQUESTED"
            );
        }

        public static Outcome technicalFailure(String detailCode) {
            return empty(
                TerminalStatus.TECHNICAL_FAILURE,
                detailCode,
                "TECHNICAL_FAILURE"
            );
        }

        public long totalMechanicalWork() {
            long total = Math.addExact(
                sourceValidationWork,
                factorizationWork
            );
            total = Math.addExact(total, renderReparseWork);
            total = Math.addExact(total, cacheLookupWork);
            total = Math.addExact(total, cacheReplayWork);
            return Math.addExact(total, otherMechanicalWork);
        }

        public void requireWithin(
            PolynomialTheoryUtilityExecutionInput input
        ) {
            Objects.requireNonNull(input, "input");
            if (primitiveWork > input.admittedPrimitiveWork()) {
                throw new IllegalArgumentException(
                    "adapter primitive work exceeds frozen input authority"
                );
            }
            if (factorizationWork > input.factorizationWork()) {
                throw new IllegalArgumentException(
                    "adapter factorization work exceeds frozen input authority"
                );
            }
            if (totalMechanicalWork() > input.totalMechanicalWork()) {
                throw new IllegalArgumentException(
                    "adapter mechanical work exceeds frozen input authority"
                );
            }
        }

        private static Outcome empty(
            TerminalStatus status,
            String detailCode,
            String verifierStatus
        ) {
            return new Outcome(
                status,
                detailCode,
                "",
                "",
                verifierStatus,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of()
            );
        }
    }

    private static List<String> requireTexts(
        List<String> values,
        String name
    ) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, name));
        copy.forEach(value -> requireText(value, name + " entry"));
        return copy;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
