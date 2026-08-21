package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.CANONICALIZER;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.TRANSFORMATION_ORDER;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

record ExecutionResult(
    List<CandidateEvidence> candidates,
    WorkLedger work,
    String terminalReason,
    List<String> limitReasons
) {
    ExecutionResult {
        candidates = List.copyOf(candidates);
        Objects.requireNonNull(work, "work");
        terminalReason = requireText(
            terminalReason, "terminalReason");
        limitReasons = List.copyOf(new TreeSet<>(limitReasons));
    }
}

record EnumState(
    String expression,
    List<String> assumptions,
    int depth,
    List<String> pathExpressions,
    List<String> pathRuleIds,
    List<String> primitiveRuleIds,
    boolean equivalencePreserving,
    boolean temporaryComplexityIncrease
) {
    EnumState {
        assumptions = List.copyOf(new TreeSet<>(assumptions));
        pathExpressions = List.copyOf(pathExpressions);
        pathRuleIds = List.copyOf(pathRuleIds);
        primitiveRuleIds = List.copyOf(primitiveRuleIds);
    }

    static EnumState root(
        String expression,
        List<String> assumptions
    ) {
        return new EnumState(
            expression,
            assumptions,
            0,
            List.of(expression),
            List.of(),
            List.of(),
            true,
            false);
    }

    EnumState successor(
        String nextExpression,
        List<String> nextAssumptions,
        Transformation transformation,
        int nextDepth,
        boolean complexityIncrease
    ) {
        List<String> expressions = new ArrayList<>(pathExpressions);
        expressions.add(nextExpression);
        List<String> rules = new ArrayList<>(pathRuleIds);
        rules.add(transformation.rule());
        List<String> primitives = new ArrayList<>(primitiveRuleIds);
        primitives.addAll(transformation.primitiveRuleIds());
        return new EnumState(
            nextExpression,
            nextAssumptions,
            nextDepth,
            expressions,
            rules,
            primitives,
            equivalencePreserving
                && transformation
                    .equivalencePreservingByConstruction(),
            complexityIncrease);
    }

    CandidateEvidence toCandidate() {
        return CandidateEvidence.create(
            expression,
            assumptions,
            depth,
            pathExpressions,
            pathRuleIds,
            primitiveRuleIds,
            equivalencePreserving,
            temporaryComplexityIncrease);
    }
}

record Proof(
    CandidateProofStatus status,
    String oracleStatus
) {
}

final class CheckpointEngine
        implements TransformationEngine {
    private final TransformationEngine delegate;
    private final int checkpoint;
    private final int maxMaterializedTransitions;
    private final int maxCandidatesPerState;
    private int engineCalls;
    private int materializedTransitions;
    private int admittedTransitions;
    private int admittedPrimitiveSteps;
    private boolean candidateLimitObserved;
    private boolean materializedLimitReached;

    CheckpointEngine(
        TransformationEngine delegate,
        int checkpoint,
        int maxMaterializedTransitions,
        int maxCandidatesPerState
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (checkpoint < 1 || maxMaterializedTransitions < 1
                || maxCandidatesPerState < 1) {
            throw new IllegalArgumentException(
                "checkpoint-engine budgets must be positive");
        }
        this.checkpoint = checkpoint;
        this.maxMaterializedTransitions = maxMaterializedTransitions;
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    @Override
    public List<Transformation> transform(String expression) {
        if (checkpointReached() || materializedLimitReached) {
            return List.of();
        }
        engineCalls++;
        List<Transformation> generated = new ArrayList<>(
            delegate.transform(expression));
        generated.sort(TRANSFORMATION_ORDER);
        candidateLimitObserved |=
            generated.size() >= maxCandidatesPerState;
        int remainingMaterialized = maxMaterializedTransitions
            - materializedTransitions;
        if (generated.size() > remainingMaterialized) {
            generated = generated.stream()
                .limit(remainingMaterialized).toList();
            materializedLimitReached = true;
        }
        materializedTransitions += generated.size();
        List<Transformation> admitted = new ArrayList<>();
        for (Transformation candidate : generated) {
            int cost = candidate.primitiveStepCount();
            if (cost < 1) {
                throw new IllegalStateException(
                    "primitive work cost must be positive");
            }
            if (admittedPrimitiveSteps + cost > checkpoint) {
                break;
            }
            admitted.add(candidate);
            admittedTransitions++;
            admittedPrimitiveSteps += cost;
            if (checkpointReached()) {
                break;
            }
        }
        return List.copyOf(admitted);
    }

    int engineCalls() {
        return engineCalls;
    }

    int materializedTransitions() {
        return materializedTransitions;
    }

    int admittedTransitions() {
        return admittedTransitions;
    }

    int admittedPrimitiveSteps() {
        return admittedPrimitiveSteps;
    }

    boolean checkpointReached() {
        return admittedPrimitiveSteps == checkpoint;
    }

    List<String> limitReasons() {
        TreeSet<String> result = new TreeSet<>();
        if (candidateLimitObserved) {
            result.add("MAX_CANDIDATES_PER_STATE_OBSERVED");
        }
        if (materializedLimitReached) {
            result.add("MAX_GENERATED_TRANSITIONS");
        }
        return List.copyOf(result);
    }
}
