package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.OracleEvidence;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.CaseDiagnostic;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.CaseStatus;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.EventSnapshot;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.LossReason;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.LostStep;
import de.regelsuche.benchmark.HistoricalWitnessPruningReport.TargetBlindTerminalStatus;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Case;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Derives one first-loss classification from an oracle witness and search trace. */
final class WitnessPrefixAnalyzer {
    private final ExpressionParser parser = new ExpressionParser();

    CaseDiagnostic analyze(
        Case benchmarkCase,
        OracleEvidence oracle,
        GoalSearchResult search,
        List<SearchEvent> events,
        int engineCalls,
        long generatedTransformations
    ) {
        Set<String> explored = search.states().stream()
            .map(SearchState::expression)
            .map(this::expressionKey)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new));
        String before = format(benchmarkCase.source());
        int prefixLength = 0;
        for (int index = 0; index < oracle.witnessExpressions().size(); index++) {
            String after = format(oracle.witnessExpressions().get(index));
            String rule = oracle.witnessRuleIds().get(index);
            if (explored.contains(expressionKey(after))) {
                prefixLength++;
                before = after;
                continue;
            }
            return new CaseDiagnostic(
                benchmarkCase.id(),
                CaseStatus.WITNESS_PREFIX_LOST,
                oracle.status(),
                oracle.witnessExpressions().size(),
                prefixLength,
                terminalStatus(benchmarkCase, search.metrics()),
                search.states().size(),
                engineCalls,
                generatedTransformations,
                diagnoseLoss(index, before, after, rule, events),
                "first target-aware oracle witness edge absent from the "
                    + "target-blind explored prefix");
        }
        return new CaseDiagnostic(
            benchmarkCase.id(),
            CaseStatus.WITNESS_COMPLETELY_EXPLORED,
            oracle.status(),
            oracle.witnessExpressions().size(),
            prefixLength,
            terminalStatus(benchmarkCase, search.metrics()),
            search.states().size(),
            engineCalls,
            generatedTransformations,
            null,
            "all oracle witness states were explored; relation matching must "
                + "be diagnosed separately");
    }

    private LostStep diagnoseLoss(
        int index,
        String before,
        String after,
        String rule,
        List<SearchEvent> events
    ) {
        Optional<SearchEvent> transition = firstTransition(events, before, after, rule);
        if (transition.isPresent()) {
            SearchEvent generated = transition.orElseThrow();
            if (!generated.pruningReason().isBlank()) {
                return loss(index, before, after, rule,
                    LossReason.TRANSFORMATION_SKIPPED, generated,
                    "witness transformation was offered but rejected");
            }
            Optional<SearchEvent> duplicate = firstEdgeEvent(
                events, SearchEventType.STATE_PRUNED_DUPLICATE,
                before, after, rule, generated.sequence());
            if (duplicate.isPresent()) {
                return loss(index, before, after, rule,
                    LossReason.STATE_PRUNED_DUPLICATE,
                    duplicate.orElseThrow(),
                    "witness state was removed by visited-state identity");
            }
            Optional<SearchEvent> enqueued = firstEdgeEvent(
                events, SearchEventType.STATE_ENQUEUED,
                before, after, rule, generated.sequence());
            if (enqueued.isPresent()) {
                return loss(index, before, after, rule,
                    LossReason.STATE_ENQUEUED_BUT_NOT_EXPLORED,
                    enqueued.orElseThrow(),
                    "witness state remained outside the explored prefix");
            }
            return loss(index, before, after, rule,
                LossReason.TRANSFORMATION_GENERATED_NOT_ENQUEUED,
                generated,
                "generated witness edge has no enqueue or prune event");
        }
        return parentLoss(index, before, after, rule, events);
    }

    private LostStep parentLoss(
        int index,
        String before,
        String after,
        String rule,
        List<SearchEvent> events
    ) {
        Optional<SearchEvent> event = firstStateEvent(
            events, SearchEventType.STATE_PRUNED_BUDGET, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.CANDIDATE_BUDGET_BEFORE_WITNESS_EDGE,
                event.orElseThrow(),
                "per-state candidate ceiling stopped before the witness edge");
        }
        event = firstStateEvent(
            events, SearchEventType.STATE_PRUNED_DEPTH, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.PARENT_DEPTH_LIMIT, event.orElseThrow(),
                "witness parent reached the configured depth ceiling");
        }
        event = firstStateEvent(
            events, SearchEventType.STATE_PRUNED_TRANSPOSITION, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.PARENT_PRUNED_TRANSPOSITION,
                event.orElseThrow(),
                "witness parent was rejected by transposition memory");
        }
        event = firstStateEvent(
            events, SearchEventType.STATE_PRUNED_DUPLICATE, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.PARENT_PRUNED_DUPLICATE,
                event.orElseThrow(),
                "witness parent was rejected as a duplicate");
        }
        event = firstStateEvent(events, SearchEventType.STATE_EXPANDED, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.TRANSFORMATION_NOT_GENERATED,
                event.orElseThrow(),
                "production engine did not emit the oracle witness edge");
        }
        event = firstStateEvent(events, SearchEventType.STATE_ENQUEUED, before);
        if (event.isPresent()) {
            return loss(index, before, after, rule,
                LossReason.PARENT_ENQUEUED_BUT_NOT_EXPLORED,
                event.orElseThrow(),
                "witness parent remained in the frontier");
        }
        return loss(index, before, after, rule,
            LossReason.PARENT_NOT_REACHED, null,
            "target-blind search never reached the witness parent");
    }

    private LostStep loss(
        int index,
        String before,
        String after,
        String rule,
        LossReason reason,
        SearchEvent event,
        String detail
    ) {
        EventSnapshot snapshot = event == null ? null : new EventSnapshot(
            event.type().name(),
            event.sequence(),
            event.depth(),
            event.score(),
            event.frontierSize(),
            event.visitedCount(),
            event.generatedCount(),
            event.pruningReason());
        return new LostStep(
            index, before, after, rule, reason, snapshot, detail);
    }

    private Optional<SearchEvent> firstTransition(
        List<SearchEvent> events,
        String before,
        String after,
        String rule
    ) {
        return events.stream()
            .filter(event -> event.type() == SearchEventType.TRANSFORMATION_GENERATED)
            .filter(event -> sameExpression(event.parentExpression(), before))
            .filter(event -> sameExpression(event.expression(), after))
            .filter(event -> event.ruleId().equals(rule))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private Optional<SearchEvent> firstEdgeEvent(
        List<SearchEvent> events,
        SearchEventType type,
        String before,
        String after,
        String rule,
        long afterSequence
    ) {
        return events.stream()
            .filter(event -> event.sequence() > afterSequence)
            .filter(event -> event.type() == type)
            .filter(event -> sameExpression(event.parentExpression(), before))
            .filter(event -> sameExpression(event.expression(), after))
            .filter(event -> event.ruleId().equals(rule))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private Optional<SearchEvent> firstStateEvent(
        List<SearchEvent> events,
        SearchEventType type,
        String expression
    ) {
        return events.stream()
            .filter(event -> event.type() == type)
            .filter(event -> sameExpression(event.expression(), expression))
            .min(Comparator.comparingLong(SearchEvent::sequence));
    }

    private TargetBlindTerminalStatus terminalStatus(
        Case benchmarkCase,
        GoalMetrics metrics
    ) {
        if (metrics.exploredStates() >= benchmarkCase.searchMaxVisitedStates()) {
            return TargetBlindTerminalStatus.STATE_BUDGET;
        }
        if (metrics.candidateBudgetPrunes() > 0) {
            return TargetBlindTerminalStatus.CANDIDATE_BUDGET;
        }
        if (metrics.depthPrunes() > 0) {
            return TargetBlindTerminalStatus.DEPTH_BUDGET;
        }
        if (metrics.expandedStates() > 0
                && metrics.generatedTransformations() == 0) {
            return TargetBlindTerminalStatus.NO_TRANSFORMATIONS;
        }
        return TargetBlindTerminalStatus.FRONTIER_EXHAUSTED;
    }

    private boolean sameExpression(String left, String right) {
        return expressionKey(left).equals(expressionKey(right));
    }

    private String expressionKey(String expression) {
        try {
            return format(expression);
        } catch (IllegalArgumentException exception) {
            return Objects.requireNonNull(expression, "expression")
                .trim().replaceAll("\\s+", " ");
        }
    }

    private String format(String expression) {
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }
}
