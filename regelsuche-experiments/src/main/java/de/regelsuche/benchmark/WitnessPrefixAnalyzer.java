package de.regelsuche.benchmark;

import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.BoundedRewriteReachabilityOracle.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Locates the first production-search loss along one deterministic oracle witness.
 *
 * <p>The oracle is target-aware and is used only after the target-blind search has
 * completed. The resulting prefix evidence is diagnostic; it is not autonomous
 * discovery, proof, global unreachability, novelty, or superiority evidence.</p>
 */
public final class WitnessPrefixAnalyzer {
    private WitnessPrefixAnalyzer() {
    }

    public static Analysis analyze(
        List<Step> witness,
        List<SearchEvent> events,
        TerminalStatus terminalStatus,
        boolean targetReached,
        UnaryOperator<String> normalizer
    ) {
        List<Step> safeWitness = List.copyOf(
            Objects.requireNonNull(witness, "witness"));
        List<SearchEvent> safeEvents = List.copyOf(
            Objects.requireNonNull(events, "events"));
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        UnaryOperator<String> safeNormalizer = Objects.requireNonNull(
            normalizer,
            "normalizer");

        if (targetReached) {
            return new Analysis(Outcome.TARGET_REACHED, List.of(), null);
        }
        if (safeWitness.isEmpty()) {
            return new Analysis(
                Outcome.WITNESS_RETAINED_TARGET_NOT_REACHED,
                List.of(),
                null);
        }

        List<PrefixStep> prefix = new ArrayList<>();
        for (int index = 0; index < safeWitness.size(); index++) {
            PrefixStep step = inspectStep(
                index,
                safeWitness,
                safeEvents,
                terminalStatus,
                safeNormalizer);
            prefix.add(step);
            if (step.reason() != Reason.NONE) {
                return new Analysis(
                    Outcome.FIRST_PREFIX_LOST,
                    prefix,
                    PrefixLoss.from(step));
            }
        }
        return new Analysis(
            Outcome.WITNESS_RETAINED_TARGET_NOT_REACHED,
            prefix,
            null);
    }

    private static PrefixStep inspectStep(
        int index,
        List<Step> witness,
        List<SearchEvent> events,
        TerminalStatus terminalStatus,
        UnaryOperator<String> normalizer
    ) {
        Step oracleStep = witness.get(index);
        ExpectedState source = sourceState(index, witness, normalizer);
        ExpectedState target = new ExpectedState(
            normalize(oracleStep.expressionAfter(), normalizer),
            normalize(oracleStep.expressionBefore(), normalizer),
            oracleStep.rule(),
            index + 1);

        SearchEvent sourceVisited = firstStateEvent(
            events,
            SearchEventType.STATE_VISITED,
            source,
            normalizer);
        SearchEvent sourceExpanded = firstStateEvent(
            events,
            SearchEventType.STATE_EXPANDED,
            source,
            normalizer);
        SearchEvent sourceDepthPrune = firstStateEvent(
            events,
            SearchEventType.STATE_PRUNED_DEPTH,
            source,
            normalizer);
        SearchEvent sourceTranspositionPrune = firstStateEvent(
            events,
            SearchEventType.STATE_PRUNED_TRANSPOSITION,
            source,
            normalizer);
        SearchEvent sourceCandidateBudget = firstStateEvent(
            events,
            SearchEventType.STATE_PRUNED_BUDGET,
            source,
            normalizer);
        SearchEvent transition = firstTransitionEvent(
            events,
            source.expression(),
            target.expression(),
            oracleStep.rule(),
            index + 1,
            normalizer);
        SearchEvent targetEnqueued = firstStateEvent(
            events,
            SearchEventType.STATE_ENQUEUED,
            target,
            normalizer);
        SearchEvent targetVisited = firstStateEvent(
            events,
            SearchEventType.STATE_VISITED,
            target,
            normalizer);
        SearchEvent targetDuplicate = firstStateEvent(
            events,
            SearchEventType.STATE_PRUNED_DUPLICATE,
            target,
            normalizer);

        if (targetVisited != null) {
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                transition,
                targetEnqueued,
                targetVisited,
                Stage.RETAINED,
                Reason.NONE,
                targetVisited.sequence());
        }
        if (sourceVisited == null) {
            Reason reason = terminalStatus == TerminalStatus.STATE_BUDGET
                ? Reason.SOURCE_NOT_VISITED_BEFORE_STATE_BUDGET
                : Reason.PREFIX_NOT_RETAINED_UNCLASSIFIED;
            return prefixStep(
                index,
                oracleStep,
                null,
                sourceExpanded,
                transition,
                targetEnqueued,
                null,
                Stage.SOURCE_STATE,
                reason,
                -1L);
        }
        if (sourceExpanded == null) {
            if (sourceTranspositionPrune != null) {
                return prefixStep(
                    index,
                    oracleStep,
                    sourceVisited,
                    null,
                    transition,
                    targetEnqueued,
                    null,
                    Stage.SOURCE_STATE,
                    Reason.SOURCE_PRUNED_TRANSPOSITION,
                    sourceTranspositionPrune.sequence());
            }
            if (sourceDepthPrune != null) {
                return prefixStep(
                    index,
                    oracleStep,
                    sourceVisited,
                    null,
                    transition,
                    targetEnqueued,
                    null,
                    Stage.SOURCE_STATE,
                    Reason.SOURCE_PRUNED_AT_DEPTH_LIMIT,
                    sourceDepthPrune.sequence());
            }
            Reason reason = terminalStatus == TerminalStatus.STATE_BUDGET
                ? Reason.SOURCE_NOT_EXPANDED_BEFORE_STATE_BUDGET
                : Reason.PREFIX_NOT_RETAINED_UNCLASSIFIED;
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                null,
                transition,
                targetEnqueued,
                null,
                Stage.SOURCE_STATE,
                reason,
                -1L);
        }
        if (transition != null && !transition.pruningReason().isBlank()) {
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                transition,
                targetEnqueued,
                null,
                Stage.TRANSFORMATION,
                skippedTransformationReason(transition.pruningReason()),
                transition.sequence());
        }
        if (transition == null) {
            if (sourceCandidateBudget != null) {
                return prefixStep(
                    index,
                    oracleStep,
                    sourceVisited,
                    sourceExpanded,
                    null,
                    targetEnqueued,
                    null,
                    Stage.TRANSFORMATION,
                    Reason.EXPECTED_TRANSFORMATION_OUTSIDE_CANDIDATE_BUDGET,
                    sourceCandidateBudget.sequence());
            }
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                null,
                targetEnqueued,
                null,
                Stage.TRANSFORMATION,
                Reason.PREFIX_NOT_RETAINED_UNCLASSIFIED,
                -1L);
        }
        if (targetDuplicate != null) {
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                transition,
                null,
                null,
                Stage.ADMISSION,
                Reason.EXPECTED_STATE_PRUNED_DUPLICATE,
                targetDuplicate.sequence());
        }
        if (targetEnqueued == null) {
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                transition,
                null,
                null,
                Stage.ADMISSION,
                Reason.PREFIX_NOT_RETAINED_UNCLASSIFIED,
                transition.sequence());
        }
        if (terminalStatus == TerminalStatus.STATE_BUDGET) {
            return prefixStep(
                index,
                oracleStep,
                sourceVisited,
                sourceExpanded,
                transition,
                targetEnqueued,
                null,
                Stage.RETENTION,
                Reason.EXPECTED_STATE_NOT_VISITED_BEFORE_STATE_BUDGET,
                targetEnqueued.sequence());
        }
        Reason reason = terminalStatus == TerminalStatus.FRONTIER_EXHAUSTED
            ? Reason.FRONTIER_EXHAUSTED_BEFORE_PREFIX
            : Reason.PREFIX_NOT_RETAINED_UNCLASSIFIED;
        return prefixStep(
            index,
            oracleStep,
            sourceVisited,
            sourceExpanded,
            transition,
            targetEnqueued,
            null,
            Stage.SEARCH_TERMINAL,
            reason,
            targetEnqueued.sequence());
    }

    private static ExpectedState sourceState(
        int index,
        List<Step> witness,
        UnaryOperator<String> normalizer
    ) {
        Step current = witness.get(index);
        if (index == 0) {
            return new ExpectedState(
                normalize(current.expressionBefore(), normalizer),
                "",
                "",
                0);
        }
        Step incoming = witness.get(index - 1);
        return new ExpectedState(
            normalize(current.expressionBefore(), normalizer),
            normalize(incoming.expressionBefore(), normalizer),
            incoming.rule(),
            index);
    }

    private static PrefixStep prefixStep(
        int index,
        Step oracleStep,
        SearchEvent sourceVisited,
        SearchEvent sourceExpanded,
        SearchEvent transition,
        SearchEvent targetEnqueued,
        SearchEvent targetVisited,
        Stage stage,
        Reason reason,
        long evidenceSequence
    ) {
        return new PrefixStep(
            index,
            oracleStep.expressionBefore(),
            oracleStep.expressionAfter(),
            oracleStep.rule(),
            sourceVisited != null,
            sourceExpanded != null,
            transition != null,
            targetEnqueued != null,
            targetVisited != null,
            stage,
            reason,
            evidenceSequence);
    }

    private static SearchEvent firstStateEvent(
        List<SearchEvent> events,
        SearchEventType type,
        ExpectedState expected,
        UnaryOperator<String> normalizer
    ) {
        return events.stream()
            .filter(event -> event.type() == type)
            .filter(event -> event.depth() == expected.depth())
            .filter(event -> normalize(event.expression(), normalizer)
                .equals(expected.expression()))
            .filter(event -> normalizeOptional(event.parentExpression(), normalizer)
                .equals(expected.parentExpression()))
            .filter(event -> event.ruleId().equals(expected.ruleId()))
            .findFirst()
            .orElse(null);
    }

    private static SearchEvent firstTransitionEvent(
        List<SearchEvent> events,
        String source,
        String target,
        String ruleId,
        int depth,
        UnaryOperator<String> normalizer
    ) {
        return events.stream()
            .filter(event -> event.type()
                == SearchEventType.TRANSFORMATION_GENERATED)
            .filter(event -> event.depth() == depth)
            .filter(event -> normalize(event.parentExpression(), normalizer)
                .equals(source))
            .filter(event -> normalize(event.expression(), normalizer)
                .equals(target))
            .filter(event -> event.ruleId().equals(ruleId))
            .findFirst()
            .orElse(null);
    }

    private static Reason skippedTransformationReason(String reason) {
        return switch (reason) {
            case "repeated-rule-application" ->
                Reason.EXPECTED_TRANSFORMATION_SKIPPED_REPEATED_APPLICATION;
            case "max-expanding-steps" ->
                Reason.EXPECTED_TRANSFORMATION_SKIPPED_EXPANSION_LIMIT;
            case "same-expression" ->
                Reason.EXPECTED_TRANSFORMATION_SKIPPED_SAME_EXPRESSION;
            default -> Reason.EXPECTED_TRANSFORMATION_SKIPPED_OTHER;
        };
    }

    private static String normalize(
        String expression,
        UnaryOperator<String> normalizer
    ) {
        String normalized = normalizer.apply(
            Objects.requireNonNull(expression, "expression"));
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(
                "normalizer must return a non-blank expression");
        }
        return normalized.trim();
    }

    private static String normalizeOptional(
        String expression,
        UnaryOperator<String> normalizer
    ) {
        return expression == null || expression.isBlank()
            ? ""
            : normalize(expression, normalizer);
    }

    public enum TerminalStatus {
        STATE_BUDGET,
        CANDIDATE_BUDGET,
        DEPTH_BUDGET,
        NO_TRANSFORMATIONS,
        FRONTIER_EXHAUSTED
    }

    public enum Outcome {
        TARGET_REACHED,
        FIRST_PREFIX_LOST,
        WITNESS_RETAINED_TARGET_NOT_REACHED
    }

    public enum Stage {
        RETAINED,
        SOURCE_STATE,
        TRANSFORMATION,
        ADMISSION,
        RETENTION,
        SEARCH_TERMINAL
    }

    public enum Reason {
        NONE,
        SOURCE_NOT_VISITED_BEFORE_STATE_BUDGET,
        SOURCE_NOT_EXPANDED_BEFORE_STATE_BUDGET,
        SOURCE_PRUNED_AT_DEPTH_LIMIT,
        SOURCE_PRUNED_TRANSPOSITION,
        EXPECTED_TRANSFORMATION_OUTSIDE_CANDIDATE_BUDGET,
        EXPECTED_TRANSFORMATION_SKIPPED_REPEATED_APPLICATION,
        EXPECTED_TRANSFORMATION_SKIPPED_EXPANSION_LIMIT,
        EXPECTED_TRANSFORMATION_SKIPPED_SAME_EXPRESSION,
        EXPECTED_TRANSFORMATION_SKIPPED_OTHER,
        EXPECTED_STATE_PRUNED_DUPLICATE,
        EXPECTED_STATE_NOT_VISITED_BEFORE_STATE_BUDGET,
        FRONTIER_EXHAUSTED_BEFORE_PREFIX,
        PREFIX_NOT_RETAINED_UNCLASSIFIED
    }

    public record PrefixStep(
        int index,
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        boolean sourceVisited,
        boolean sourceExpanded,
        boolean transitionObserved,
        boolean targetEnqueued,
        boolean targetVisited,
        Stage stage,
        Reason reason,
        long evidenceSequence
    ) {
        public PrefixStep {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            expressionBefore = requireText(expressionBefore, "expressionBefore");
            expressionAfter = requireText(expressionAfter, "expressionAfter");
            ruleId = requireText(ruleId, "ruleId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(reason, "reason");
            if (reason == Reason.NONE && stage != Stage.RETAINED) {
                throw new IllegalArgumentException(
                    "only a retained prefix step may have no loss reason");
            }
            if (reason != Reason.NONE && stage == Stage.RETAINED) {
                throw new IllegalArgumentException(
                    "a retained prefix step must not have a loss reason");
            }
            if (evidenceSequence < -1L) {
                throw new IllegalArgumentException(
                    "evidenceSequence must be -1 or non-negative");
            }
        }
    }

    public record PrefixLoss(
        int index,
        String expressionBefore,
        String expressionAfter,
        String ruleId,
        Stage stage,
        Reason reason,
        long evidenceSequence
    ) {
        public PrefixLoss {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            expressionBefore = requireText(expressionBefore, "expressionBefore");
            expressionAfter = requireText(expressionAfter, "expressionAfter");
            ruleId = requireText(ruleId, "ruleId");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(reason, "reason");
            if (reason == Reason.NONE || stage == Stage.RETAINED) {
                throw new IllegalArgumentException(
                    "prefix loss requires a concrete non-retained reason");
            }
            if (evidenceSequence < -1L) {
                throw new IllegalArgumentException(
                    "evidenceSequence must be -1 or non-negative");
            }
        }

        private static PrefixLoss from(PrefixStep step) {
            return new PrefixLoss(
                step.index(),
                step.expressionBefore(),
                step.expressionAfter(),
                step.ruleId(),
                step.stage(),
                step.reason(),
                step.evidenceSequence());
        }
    }

    public record Analysis(
        Outcome outcome,
        List<PrefixStep> prefix,
        PrefixLoss firstLoss
    ) {
        public Analysis {
            Objects.requireNonNull(outcome, "outcome");
            prefix = List.copyOf(Objects.requireNonNull(prefix, "prefix"));
            if (outcome == Outcome.FIRST_PREFIX_LOST && firstLoss == null) {
                throw new IllegalArgumentException(
                    "lost prefix analysis requires firstLoss");
            }
            if (outcome != Outcome.FIRST_PREFIX_LOST && firstLoss != null) {
                throw new IllegalArgumentException(
                    "non-losing analysis must not carry firstLoss");
            }
        }
    }

    private record ExpectedState(
        String expression,
        String parentExpression,
        String ruleId,
        int depth
    ) {
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
