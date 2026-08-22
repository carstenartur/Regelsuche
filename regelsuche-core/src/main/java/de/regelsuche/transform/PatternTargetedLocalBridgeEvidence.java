package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable evidence emitted by {@link PatternTargetedLocalBridgePlanner}.
 */
public final class PatternTargetedLocalBridgeEvidence {
    private PatternTargetedLocalBridgeEvidence() {
    }

    public enum Status {
        DIRECT_MATCH_AVAILABLE,
        PREPARED,
        NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE,
        UNSUPPORTED,
        INVALID_CERTIFICATE
    }

    /** All finite local-search bounds are part of the retained identity. */
    public record Budget(
        int maxDepth,
        int maxVisitedStates,
        int maxGeneratedTransitions,
        int maxPrimitiveSteps,
        int maxExpressionNodes,
        int maxSuccessorsPerState,
        int maxMatchSteps,
        int maxPatternBranches
    ) {
        public Budget {
            if (maxDepth < 0
                    || maxVisitedStates < 1
                    || maxGeneratedTransitions < 0
                    || maxPrimitiveSteps < 0
                    || maxExpressionNodes < 1
                    || maxSuccessorsPerState < 0
                    || maxMatchSteps < 1
                    || maxPatternBranches < 1) {
                throw new IllegalArgumentException(
                    "local bridge budgets are invalid");
            }
        }

        public static Budget safeDefaults() {
            return new Budget(
                4,
                256,
                2_048,
                16,
                256,
                128,
                20_000,
                10_000);
        }
    }

    public record BridgeStep(
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        RewriteKind kind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreserving,
        String applicationKey,
        List<String> emittedAssumptions,
        AssumptionSignature resultingAssumptions,
        String packId,
        String license,
        List<String> primitiveRuleIds
    ) {
        public BridgeStep {
            expressionBefore = requireText(
                expressionBefore,
                "expressionBefore");
            expressionAfter = requireText(
                expressionAfter,
                "expressionAfter");
            ruleId = requireText(ruleId, "ruleId");
            kind = Objects.requireNonNull(kind, "kind");
            applicationKey = requireText(applicationKey, "applicationKey");
            emittedAssumptions = List.copyOf(Objects.requireNonNull(
                emittedAssumptions,
                "emittedAssumptions"));
            resultingAssumptions = Objects.requireNonNull(
                resultingAssumptions,
                "resultingAssumptions");
            packId = requireText(packId, "packId");
            license = requireText(license, "license");
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds,
                "primitiveRuleIds"));
            if (!equivalencePreserving
                    || primitiveRuleIds.isEmpty()
                    || primitiveRuleIds.stream().anyMatch(
                        id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException(
                    "bridge step must be equivalence preserving and retain "
                        + "non-empty primitive lineage");
            }
        }
    }

    /**
     * Every generated transition receives exactly one disposition. Technical
     * failures before a transition exists are counted separately.
     */
    public record WorkLedger(
        Budget budget,
        int expandedStates,
        int visitedStates,
        int generatedTransitions,
        int admittedTransitions,
        int duplicateTransitions,
        int principalRuleTransitions,
        int unsafeTransitions,
        int technicalFailureTransitions,
        int expressionLimitTransitions,
        int primitiveLimitTransitions,
        int successorLimitTransitions,
        int depthLimitTransitions,
        int visitedLimitTransitions,
        int terminalSelectionTransitions,
        int technicalFailures,
        int matchAnalyses,
        int maxFrontierSize,
        boolean generatedTransitionLimitReached,
        boolean visitedStateLimitReached,
        boolean depthLimitReached,
        boolean primitiveWorkLimitReached,
        boolean expressionNodeLimitReached,
        boolean successorLimitReached,
        boolean matchInconclusive,
        boolean technicalFailureReached
    ) {
        public WorkLedger {
            budget = Objects.requireNonNull(budget, "budget");
            if (expandedStates < 0
                    || visitedStates < 1
                    || generatedTransitions < 0
                    || admittedTransitions < 0
                    || duplicateTransitions < 0
                    || principalRuleTransitions < 0
                    || unsafeTransitions < 0
                    || technicalFailureTransitions < 0
                    || expressionLimitTransitions < 0
                    || primitiveLimitTransitions < 0
                    || successorLimitTransitions < 0
                    || depthLimitTransitions < 0
                    || visitedLimitTransitions < 0
                    || terminalSelectionTransitions < 0
                    || technicalFailures < 0
                    || matchAnalyses < 0
                    || maxFrontierSize < 1
                    || visitedStates != admittedTransitions + 1
                    || generatedTransitions
                        != admittedTransitions
                            + duplicateTransitions
                            + principalRuleTransitions
                            + unsafeTransitions
                            + technicalFailureTransitions
                            + expressionLimitTransitions
                            + primitiveLimitTransitions
                            + successorLimitTransitions
                            + depthLimitTransitions
                            + visitedLimitTransitions
                            + terminalSelectionTransitions) {
                throw new IllegalArgumentException(
                    "local bridge work ledger must be non-negative and "
                        + "exactly balanced");
            }
        }

        public boolean inconclusive() {
            return generatedTransitionLimitReached
                || visitedStateLimitReached
                || depthLimitReached
                || primitiveWorkLimitReached
                || expressionNodeLimitReached
                || successorLimitReached
                || matchInconclusive
                || technicalFailureReached
                || technicalFailures > 0;
        }
    }

    public record PreparedBridge(
        String plannerId,
        String sourceExpression,
        String terminalPreparedExpression,
        String resultExpression,
        AssumptionSignature initialAssumptions,
        AssumptionSignature finalAssumptions,
        String principalRuleId,
        String principalRuleHash,
        String preparationInventoryHash,
        Budget budget,
        PatternMatchAnalyzer.Analysis initialAnalysis,
        PatternMatchAnalyzer.Analysis terminalAnalysis,
        List<BridgeStep> preparationSteps,
        Transformation principalReplay,
        WorkLedger work,
        String certificateHash
    ) {
        public PreparedBridge {
            if (!PatternTargetedLocalBridgePlanner.PLANNER_ID.equals(
                    plannerId)) {
                throw new IllegalArgumentException(
                    "unexpected local bridge planner identity");
            }
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression");
            terminalPreparedExpression = requireText(
                terminalPreparedExpression,
                "terminalPreparedExpression");
            resultExpression = requireText(
                resultExpression,
                "resultExpression");
            initialAssumptions = Objects.requireNonNull(
                initialAssumptions,
                "initialAssumptions");
            finalAssumptions = Objects.requireNonNull(
                finalAssumptions,
                "finalAssumptions");
            principalRuleId = requireText(
                principalRuleId,
                "principalRuleId");
            principalRuleHash = requireHash(
                principalRuleHash,
                "principalRuleHash");
            preparationInventoryHash = requireHash(
                preparationInventoryHash,
                "preparationInventoryHash");
            budget = Objects.requireNonNull(budget, "budget");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis,
                "initialAnalysis");
            terminalAnalysis = Objects.requireNonNull(
                terminalAnalysis,
                "terminalAnalysis");
            preparationSteps = List.copyOf(Objects.requireNonNull(
                preparationSteps,
                "preparationSteps"));
            principalReplay = Objects.requireNonNull(
                principalReplay,
                "principalReplay");
            work = Objects.requireNonNull(work, "work");
            certificateHash = requireHash(
                certificateHash,
                "certificateHash");
            validatePath(
                sourceExpression,
                terminalPreparedExpression,
                initialAssumptions,
                finalAssumptions,
                preparationSteps,
                principalReplay,
                budget);
            if (!terminalAnalysis.matched()
                    || !principalReplay.rule().equals(principalRuleId)
                    || !principalReplay.transformedExpression().equals(
                        resultExpression)
                    || principalReplay.primitiveStepCount() != 1
                    || !work.budget().equals(budget)) {
                throw new IllegalArgumentException(
                    "prepared bridge principal replay or work is invalid");
            }
        }

        private static void validatePath(
            String sourceExpression,
            String terminalPreparedExpression,
            AssumptionSignature initialAssumptions,
            AssumptionSignature finalAssumptions,
            List<BridgeStep> steps,
            Transformation principalReplay,
            Budget budget
        ) {
            if (steps.isEmpty()
                    || !steps.getFirst().expressionBefore().equals(
                        sourceExpression)
                    || !steps.getLast().expressionAfter().equals(
                        terminalPreparedExpression)) {
                throw new IllegalArgumentException(
                    "prepared bridge path boundaries are invalid");
            }
            AssumptionSignature assumptions = initialAssumptions;
            int primitiveSteps = 0;
            String expectedBefore = sourceExpression;
            for (BridgeStep step : steps) {
                if (!step.expressionBefore().equals(expectedBefore)) {
                    throw new IllegalArgumentException(
                        "prepared bridge path is not contiguous");
                }
                assumptions = AssumptionSignature.merge(
                    assumptions,
                    AssumptionSignature.ofExpressions(
                        step.emittedAssumptions()));
                if (!assumptions.equals(step.resultingAssumptions())) {
                    throw new IllegalArgumentException(
                        "prepared bridge assumption lineage is invalid");
                }
                primitiveSteps = Math.addExact(
                    primitiveSteps,
                    step.primitiveRuleIds().size());
                expectedBefore = step.expressionAfter();
            }
            if (primitiveSteps > budget.maxPrimitiveSteps()) {
                throw new IllegalArgumentException(
                    "prepared bridge exceeds primitive work budget");
            }
            AssumptionSignature expectedFinal = AssumptionSignature.merge(
                assumptions,
                AssumptionSignature.ofExpressions(
                    principalReplay.assumptions()));
            if (!expectedFinal.equals(finalAssumptions)) {
                throw new IllegalArgumentException(
                    "principal assumption lineage is invalid");
            }
        }
    }

    public record PlanAttempt(
        Status status,
        String sourceExpression,
        AssumptionSignature initialAssumptions,
        Optional<PatternMatchAnalyzer.Analysis> initialAnalysis,
        Optional<Transformation> directPrincipalReplay,
        Optional<PreparedBridge> preparedBridge,
        WorkLedger work,
        String detailCode
    ) {
        public PlanAttempt {
            status = Objects.requireNonNull(status, "status");
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression");
            initialAssumptions = Objects.requireNonNull(
                initialAssumptions,
                "initialAssumptions");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis,
                "initialAnalysis");
            directPrincipalReplay = Objects.requireNonNull(
                directPrincipalReplay,
                "directPrincipalReplay");
            preparedBridge = Objects.requireNonNull(
                preparedBridge,
                "preparedBridge");
            work = Objects.requireNonNull(work, "work");
            detailCode = requireText(detailCode, "detailCode");
            boolean analysisMayBeMissing = status == Status.UNSUPPORTED
                || status == Status.BUDGET_INCONCLUSIVE;
            boolean invalidAnalysis = !analysisMayBeMissing
                && initialAnalysis.isEmpty();
            boolean unsupportedWithAnalysis = status == Status.UNSUPPORTED
                && initialAnalysis.isPresent();
            boolean invalidDirect = (status
                == Status.DIRECT_MATCH_AVAILABLE)
                != directPrincipalReplay.isPresent();
            boolean invalidPrepared = (status == Status.PREPARED)
                != preparedBridge.isPresent();
            if (invalidAnalysis
                    || unsupportedWithAnalysis
                    || invalidDirect
                    || invalidPrepared) {
                throw new IllegalArgumentException(
                    "local bridge status and retained evidence disagree");
            }
        }
    }

    static String requireHash(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                field + " must be a SHA-256 identity");
        }
        return value;
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
