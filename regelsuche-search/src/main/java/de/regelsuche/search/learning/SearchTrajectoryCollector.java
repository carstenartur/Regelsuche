package de.regelsuche.search.learning;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.telemetry.SearchEvent;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.search.telemetry.SearchObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Records ordinary telemetry and adds outcome labels only after search finishes.
 * The collector never mutates the search problem or candidate ordering.
 */
public final class SearchTrajectoryCollector implements SearchObserver {
    private final List<SearchEvent> events = new ArrayList<>();
    private boolean finished;

    @Override
    public synchronized void onEvent(SearchEvent event) {
        Objects.requireNonNull(event, "event");
        if (finished) {
            throw new IllegalStateException("trajectory collector is already finished");
        }
        if (event.sequence() != events.size()) {
            throw new IllegalArgumentException(
                "non-contiguous search event sequence: expected " + events.size()
                    + " but got " + event.sequence());
        }
        events.add(event);
    }

    public synchronized List<SearchEvent> events() {
        return List.copyOf(events);
    }

    public synchronized SearchTrajectoryRun finish(
        SearchProblem problem,
        GoalSearchResult result,
        SearchTrajectoryContext context
    ) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(context, "context");
        if (finished) {
            throw new IllegalStateException("trajectory collector is already finished");
        }
        finished = true;

        ExpressionCanonicalizer canonicalizer = problem.canonicalizer();
        ExpressionFingerprint root = ExpressionFingerprint.of(
            problem.rootExpression(), canonicalizer);
        ExpressionFingerprint target = problem.target() == null
            ? null
            : ExpressionFingerprint.of(problem.target().targetExpression(), canonicalizer);
        String taskValue = pairFingerprint(
            root.valueHash(), target == null ? "" : target.valueHash(), "task-value-v1:");
        String taskAlpha = pairFingerprint(
            root.alphaShapeHash(), target == null ? "" : target.alphaShapeHash(), "task-alpha-v1:");

        Map<String, List<String>> applicableByParent = applicableRulesByParent();
        Set<String> selectedStates = selectedStateSyntaxHashes(result);
        Set<TransitionKey> selectedTransitions = selectedTransitions(result);

        List<SearchTrajectoryRecord> records = events.stream()
            .map(event -> toRecord(
                event,
                context,
                problem,
                result,
                canonicalizer,
                target,
                applicableByParent,
                selectedStates,
                selectedTransitions))
            .toList();
        return new SearchTrajectoryRun(
            context,
            root,
            target,
            taskValue,
            taskAlpha,
            result.status(),
            result.reached(),
            records);
    }

    private SearchTrajectoryRecord toRecord(
        SearchEvent event,
        SearchTrajectoryContext context,
        SearchProblem problem,
        GoalSearchResult result,
        ExpressionCanonicalizer canonicalizer,
        ExpressionFingerprint target,
        Map<String, List<String>> applicableByParent,
        Set<String> selectedStates,
        Set<TransitionKey> selectedTransitions
    ) {
        ExpressionFingerprint expression = ExpressionFingerprint.of(
            event.expression(), canonicalizer);
        ExpressionFingerprint parent = event.parentExpression().isBlank()
            ? null
            : ExpressionFingerprint.of(event.parentExpression(), canonicalizer);
        boolean transformation = event.type() == SearchEventType.TRANSFORMATION_GENERATED;
        String eventSyntax = syntaxIdentity(event.expression());
        String parentSyntax = syntaxIdentity(event.parentExpression());
        boolean selected = transformation
            ? selectedTransitions.contains(new TransitionKey(
                parentSyntax, eventSyntax, event.ruleId(), event.depth()))
            : selectedStates.contains(eventSyntax);
        String applicableKey = transformation ? parentSyntax : eventSyntax;
        List<String> applicable = applicableByParent.getOrDefault(applicableKey, List.of());
        int parentScore = event.parentExpression().isBlank()
            ? event.score()
            : problem.scorer().score(event.parentExpression()).weightedTotal();

        return new SearchTrajectoryRecord(
            SearchTrajectoryRecord.SCHEMA,
            context.producerVersion(),
            context.runId(),
            context.family(),
            context.split(),
            context.ruleInventoryHash(),
            event.sequence(),
            event.type(),
            expression,
            parent,
            target,
            ExpressionFeatures.of(event.expression()),
            event.depth(),
            event.score(),
            parentScore,
            event.frontierSize(),
            event.visitedCount(),
            event.generatedCount(),
            event.ruleId(),
            event.rewriteKind(),
            applicable,
            event.assumptions(),
            event.pruningReason(),
            result.reached(),
            selected,
            result.status());
    }

    private Map<String, List<String>> applicableRulesByParent() {
        Map<String, Set<String>> mutable = new LinkedHashMap<>();
        for (SearchEvent event : events) {
            if (event.type() == SearchEventType.TRANSFORMATION_GENERATED
                    && !event.parentExpression().isBlank()
                    && !event.ruleId().isBlank()) {
                mutable.computeIfAbsent(
                    syntaxIdentity(event.parentExpression()), ignored -> new LinkedHashSet<>())
                    .add(event.ruleId());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        mutable.forEach((key, values) ->
            result.put(key, values.stream().sorted().toList()));
        return Map.copyOf(result);
    }

    private Set<String> selectedStateSyntaxHashes(GoalSearchResult result) {
        SearchState selected = result.reachedState();
        if (selected == null) {
            return Set.of();
        }
        Set<String> hashes = new LinkedHashSet<>();
        selected.path().forEach(expression -> hashes.add(syntaxIdentity(expression)));
        return Set.copyOf(hashes);
    }

    private Set<TransitionKey> selectedTransitions(GoalSearchResult result) {
        SearchState selected = result.reachedState();
        if (selected == null || selected.path().size() < 2) {
            return Set.of();
        }
        Set<TransitionKey> transitions = new LinkedHashSet<>();
        int count = Math.min(
            selected.appliedRuleIds().size(),
            selected.path().size() - 1);
        for (int index = 0; index < count; index++) {
            transitions.add(new TransitionKey(
                syntaxIdentity(selected.path().get(index)),
                syntaxIdentity(selected.path().get(index + 1)),
                selected.appliedRuleIds().get(index),
                index + 1));
        }
        return Set.copyOf(transitions);
    }

    private static String syntaxIdentity(String expression) {
        return "syntax-transition-v1:" + sha256(normalize(expression));
    }

    private static String normalize(String expression) {
        return expression == null ? "" : expression.trim().replaceAll("\\s+", " ");
    }

    private static String pairFingerprint(
        String left,
        String right,
        String prefix
    ) {
        return prefix + sha256(left + "\u0000" + right);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record TransitionKey(
        String parentSyntaxHash,
        String childSyntaxHash,
        String ruleId,
        int depth
    ) {
    }
}
