package de.regelsuche.transform;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable evidence produced by pattern-targeted local preparation. */
public final class PatternPreparationPlan {
    private PatternPreparationPlan() {
    }

    public enum Status {
        DIRECT_MATCH_AVAILABLE,
        PREPARED,
        NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE,
        UNSUPPORTED,
        INVALID_CERTIFICATE
    }

    public enum LimitReason {
        DEPTH,
        VISITED_STATES,
        GENERATED_TRANSITIONS,
        PRIMITIVE_STEPS,
        EXPRESSION_NODES,
        SUCCESSORS_PER_STATE,
        MATCH_ANALYSIS
    }

    public record Budget(
        int maxDepth,
        int maxVisitedStates,
        long maxGeneratedTransitions,
        int maxPrimitiveSteps,
        int maxExpressionNodes,
        int maxSuccessorsPerState,
        int maxMatchSteps
    ) {
        public Budget {
            if (maxDepth < 0
                    || maxVisitedStates < 1
                    || maxGeneratedTransitions < 1
                    || maxPrimitiveSteps < 1
                    || maxExpressionNodes < 1
                    || maxSuccessorsPerState < 1
                    || maxMatchSteps < 1) {
                throw new IllegalArgumentException(
                    "all preparation budgets must be positive, except depth");
            }
        }

        public static Budget safeDefaults() {
            return new Budget(3, 256, 4_096, 8, 256, 80, 20_000);
        }

        String descriptor() {
            return "depth=" + maxDepth
                + ";visited=" + maxVisitedStates
                + ";transitions=" + maxGeneratedTransitions
                + ";primitive=" + maxPrimitiveSteps
                + ";nodes=" + maxExpressionNodes
                + ";successors=" + maxSuccessorsPerState
                + ";match=" + maxMatchSteps;
        }
    }

    public record Step(
        String expressionBefore,
        String expressionBeforeFingerprint,
        String expressionAfter,
        String expressionAfterFingerprint,
        String rule,
        List<String> assumptions,
        String applicationKey,
        List<String> primitiveRuleIds
    ) {
        public Step {
            expressionBefore = text(expressionBefore, "expressionBefore");
            expressionBeforeFingerprint = hash(
                expressionBeforeFingerprint,
                "expressionBeforeFingerprint");
            expressionAfter = text(expressionAfter, "expressionAfter");
            expressionAfterFingerprint = hash(
                expressionAfterFingerprint,
                "expressionAfterFingerprint");
            rule = text(rule, "rule");
            assumptions = List.copyOf(Objects.requireNonNull(
                assumptions,
                "assumptions"));
            applicationKey = text(applicationKey, "applicationKey");
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds,
                "primitiveRuleIds"));
            if (primitiveRuleIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "primitiveRuleIds must not be empty");
            }
        }
    }

    public record WorkLedger(
        Budget configuredBudget,
        int visitedStates,
        long generatedTransitions,
        int maximumPrimitivePathWork,
        int matchAnalyses,
        int maximumDepthReached,
        int maximumExpressionNodes,
        Set<LimitReason> reachedLimits
    ) {
        public WorkLedger {
            configuredBudget = Objects.requireNonNull(
                configuredBudget,
                "configuredBudget");
            reachedLimits = Set.copyOf(Objects.requireNonNull(
                reachedLimits,
                "reachedLimits"));
            if (visitedStates < 0
                    || generatedTransitions < 0
                    || maximumPrimitivePathWork < 0
                    || matchAnalyses < 0
                    || maximumDepthReached < 0
                    || maximumExpressionNodes < 0) {
                throw new IllegalArgumentException(
                    "work counters must not be negative");
            }
        }

        String descriptor() {
            return configuredBudget.descriptor()
                + ";visitedActual=" + visitedStates
                + ";generatedActual=" + generatedTransitions
                + ";primitivePathActual=" + maximumPrimitivePathWork
                + ";matchAnalyses=" + matchAnalyses
                + ";depthActual=" + maximumDepthReached
                + ";nodesActual=" + maximumExpressionNodes
                + ";limits=" + reachedLimits.stream()
                    .sorted()
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.joining(","));
        }
    }

    public record Certificate(
        String schema,
        String plannerId,
        String contentHash
    ) {
        public Certificate {
            schema = text(schema, "schema");
            plannerId = text(plannerId, "plannerId");
            contentHash = hash(contentHash, "contentHash");
        }
    }

    public record PreparedApplication(
        String sourceExpression,
        String sourceFingerprint,
        List<String> sourceAssumptions,
        String preparedExpression,
        String preparedFingerprint,
        String resultExpression,
        String resultFingerprint,
        String principalRuleId,
        RewriteKind principalKind,
        boolean principalMayIncreaseComplexity,
        int principalEstimatedCostDelta,
        boolean principalEquivalencePreserving,
        String principalPackId,
        String principalLicense,
        List<String> finalAssumptions,
        List<Step> preparationSteps,
        List<String> primitiveRuleIds,
        WorkLedger work,
        Certificate certificate
    ) {
        public PreparedApplication {
            sourceExpression = text(sourceExpression, "sourceExpression");
            sourceFingerprint = hash(
                sourceFingerprint,
                "sourceFingerprint");
            sourceAssumptions = List.copyOf(Objects.requireNonNull(
                sourceAssumptions,
                "sourceAssumptions"));
            preparedExpression = text(
                preparedExpression,
                "preparedExpression");
            preparedFingerprint = hash(
                preparedFingerprint,
                "preparedFingerprint");
            resultExpression = text(resultExpression, "resultExpression");
            resultFingerprint = hash(
                resultFingerprint,
                "resultFingerprint");
            principalRuleId = text(principalRuleId, "principalRuleId");
            principalKind = Objects.requireNonNull(
                principalKind,
                "principalKind");
            principalPackId = text(principalPackId, "principalPackId");
            principalLicense = text(principalLicense, "principalLicense");
            finalAssumptions = List.copyOf(Objects.requireNonNull(
                finalAssumptions,
                "finalAssumptions"));
            preparationSteps = List.copyOf(Objects.requireNonNull(
                preparationSteps,
                "preparationSteps"));
            primitiveRuleIds = List.copyOf(Objects.requireNonNull(
                primitiveRuleIds,
                "primitiveRuleIds"));
            work = Objects.requireNonNull(work, "work");
            certificate = Objects.requireNonNull(
                certificate,
                "certificate");
            if (preparationSteps.isEmpty()
                    || primitiveRuleIds.size() < 2
                    || !primitiveRuleIds.getLast().equals(principalRuleId)) {
                throw new IllegalArgumentException(
                    "prepared application requires preparation and principal lineage");
            }
        }

        PreparedApplication withCertificate(Certificate replacement) {
            return new PreparedApplication(
                sourceExpression,
                sourceFingerprint,
                sourceAssumptions,
                preparedExpression,
                preparedFingerprint,
                resultExpression,
                resultFingerprint,
                principalRuleId,
                principalKind,
                principalMayIncreaseComplexity,
                principalEstimatedCostDelta,
                principalEquivalencePreserving,
                principalPackId,
                principalLicense,
                finalAssumptions,
                preparationSteps,
                primitiveRuleIds,
                work,
                replacement);
        }
    }

    public record Attempt(
        Status status,
        String detailCode,
        String principalRuleId,
        Optional<PreparedApplication> application,
        Optional<PatternMatchAnalyzer.Analysis> sourceAnalysis,
        WorkLedger work,
        Set<LimitReason> reachedLimits
    ) {
        public Attempt {
            status = Objects.requireNonNull(status, "status");
            detailCode = text(detailCode, "detailCode");
            principalRuleId = principalRuleId == null
                ? ""
                : principalRuleId.trim();
            application = Objects.requireNonNull(
                application,
                "application");
            sourceAnalysis = Objects.requireNonNull(
                sourceAnalysis,
                "sourceAnalysis");
            work = Objects.requireNonNull(work, "work");
            reachedLimits = Set.copyOf(Objects.requireNonNull(
                reachedLimits,
                "reachedLimits"));
            if ((status == Status.PREPARED) != application.isPresent()) {
                throw new IllegalArgumentException(
                    "only PREPARED may retain an application");
            }
            if (status == Status.DIRECT_MATCH_AVAILABLE
                    && principalRuleId.isBlank()) {
                throw new IllegalArgumentException(
                    "direct match requires a principal rule ID");
            }
            if (status == Status.BUDGET_INCONCLUSIVE
                    && reachedLimits.isEmpty()) {
                throw new IllegalArgumentException(
                    "inconclusive result requires a reached limit");
            }
        }
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String hash(String value, String name) {
        String normalized = text(value, name);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                name + " must be a lowercase SHA-256 value");
        }
        return normalized;
    }
}
