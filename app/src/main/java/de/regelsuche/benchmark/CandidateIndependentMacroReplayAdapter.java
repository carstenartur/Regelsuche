package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reproduces frozen macro-learning traces through current production rewrite
 * rules while preserving the versioned abstract operation vocabulary.
 *
 * <p>A trace is accepted only if its target is reached and every concrete rule
 * application can be assigned to the declared abstract operation sequence.
 * Consecutive concrete applications may belong to the same abstract phase;
 * phases may advance only in the frozen order. This supports semantic phases
 * such as distribution that require more than one local AST rewrite.</p>
 */
public final class CandidateIndependentMacroReplayAdapter {
    private static final int DEFAULT_MAX_DEPTH = 12;
    private static final int DEFAULT_MAX_STATES = 2_000;

    private final Map<String, List<String>> operationRuleIds;
    private final Map<String, Set<String>> operationsByRuleId;
    private final TransformationEngine engine;
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final int maxDepth;
    private final int maxStates;

    public CandidateIndependentMacroReplayAdapter(
        Map<String, List<String>> operationRuleIds
    ) {
        this(operationRuleIds, DEFAULT_MAX_DEPTH, DEFAULT_MAX_STATES);
    }

    CandidateIndependentMacroReplayAdapter(
        Map<String, List<String>> operationRuleIds,
        int maxDepth,
        int maxStates
    ) {
        Objects.requireNonNull(operationRuleIds, "operationRuleIds");
        if (operationRuleIds.isEmpty()) {
            throw new IllegalArgumentException(
                "macro primitive profile must not be empty");
        }
        if (maxDepth < 1 || maxStates < 1) {
            throw new IllegalArgumentException(
                "replay bounds must be positive");
        }
        this.maxDepth = maxDepth;
        this.maxStates = maxStates;

        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        LinkedHashMap<String, Set<String>> reverse = new LinkedHashMap<>();
        operationRuleIds.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String operationId = requireText(
                    entry.getKey(), "operationId");
                List<String> rules = immutableStrings(
                    entry.getValue(), "implementationRuleIds").stream()
                    .distinct().sorted().toList();
                if (rules.isEmpty()) {
                    throw new IllegalArgumentException(
                        "operation has no implementation rules: "
                            + operationId);
                }
                normalized.put(operationId, rules);
                rules.forEach(rule -> reverse.computeIfAbsent(
                    rule, ignored -> new LinkedHashSet<>()).add(operationId));
            });
        this.operationRuleIds = Map.copyOf(normalized);
        LinkedHashMap<String, Set<String>> immutableReverse =
            new LinkedHashMap<>();
        reverse.forEach((rule, operations) ->
            immutableReverse.put(rule, Set.copyOf(operations)));
        this.operationsByRuleId = Map.copyOf(immutableReverse);

        Set<String> declaredRules = reverse.keySet();
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules()
            .stream()
            .filter(rule -> declaredRules.contains(rule.id()))
            .sorted(Comparator.comparing(RewriteRule::id))
            .toList();
        Set<String> resolvedRules = rules.stream().map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new));
        if (!resolvedRules.equals(new LinkedHashSet<>(declaredRules))) {
            LinkedHashSet<String> missing = new LinkedHashSet<>(declaredRules);
            missing.removeAll(resolvedRules);
            throw new IllegalArgumentException(
                "macro profile references unavailable rules: " + missing);
        }
        this.engine = new AstRewriteTransformationEngine(rules, 16, 120);
    }

    public BatchResult replayAll(List<ReplayTrace> traces) {
        Objects.requireNonNull(traces, "traces");
        if (traces.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one replay trace is required");
        }
        List<ReplayResult> results = traces.stream()
            .map(this::replay)
            .toList();
        boolean complete = results.stream().allMatch(ReplayResult::reproduced);
        return new BatchResult(
            complete ? BatchStatus.REPRODUCED : BatchStatus.INCOMPLETE,
            results,
            complete
                ? "every frozen TRAIN replay was reproduced"
                : "one or more frozen TRAIN replays were not reproduced");
    }

    public ReplayResult replay(ReplayTrace trace) {
        Objects.requireNonNull(trace, "trace");
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        queue.add(new Node(trace.source(), List.of(), List.of(), 0, -1));
        visited.add(key(trace.source(), -1));
        int explored = 0;

        while (!queue.isEmpty() && explored < maxStates) {
            Node node = queue.removeFirst();
            explored++;
            if (same(node.expression(), trace.target())
                    && node.operationIndex()
                        == trace.primitiveSteps().size() - 1) {
                List<String> expressions = expressions(
                    trace.source(), node.steps());
                SuccessfulTransformationPath path =
                    new SuccessfulTransformationPath(
                        trace.traceId(),
                        trace.source(),
                        trace.target(),
                        expressions,
                        node.steps().stream()
                            .map(Transformation::rule).toList(),
                        scorer.score(trace.source()),
                        scorer.score(trace.target()),
                        true,
                        "replayed with frozen macro primitive profile",
                        Map.of(
                            "primitiveOperations",
                            String.join(",", trace.primitiveSteps())),
                        trace.assumptions());
                ReplayEvidence evidence = new ReplayEvidence(
                    trace.traceId(),
                    true,
                    explored,
                    node.steps().stream()
                        .map(Transformation::rule).toList(),
                    node.assignedOperations(),
                    compressed(node.assignedOperations()),
                    expressions,
                    "target reached under the declared abstract operation order");
                return new ReplayResult(
                    trace.traceId(), true, Optional.of(path), evidence);
            }
            if (node.depth() >= maxDepth) {
                continue;
            }
            List<Transformation> transformations = engine
                .transform(node.expression()).stream()
                .sorted(Comparator
                    .comparing(Transformation::rule)
                    .thenComparing(Transformation::transformedExpression)
                    .thenComparing(Transformation::applicationKey))
                .toList();
            for (Transformation transformation : transformations) {
                if (!assumptionsCovered(
                        transformation.assumptions(), trace.assumptions())) {
                    continue;
                }
                for (String operation : operationsByRuleId.getOrDefault(
                        transformation.rule(), Set.of()).stream()
                        .sorted().toList()) {
                    int nextIndex = compatibleOperationIndex(
                        trace.primitiveSteps(),
                        node.operationIndex(),
                        operation);
                    if (nextIndex < 0) {
                        continue;
                    }
                    String key = key(
                        transformation.transformedExpression(), nextIndex);
                    if (!visited.add(key)) {
                        continue;
                    }
                    queue.addLast(new Node(
                        transformation.transformedExpression(),
                        append(node.steps(), transformation),
                        append(node.assignedOperations(), operation),
                        node.depth() + 1,
                        nextIndex));
                }
            }
        }

        ReplayEvidence evidence = new ReplayEvidence(
            trace.traceId(),
            false,
            explored,
            List.of(),
            List.of(),
            List.of(),
            List.of(trace.source()),
            queue.isEmpty()
                ? "no compatible production replay reaches the target"
                : "replay state budget exhausted");
        return new ReplayResult(
            trace.traceId(), false, Optional.empty(), evidence);
    }

    public Map<String, List<String>> operationRuleIds() {
        return operationRuleIds;
    }

    private int compatibleOperationIndex(
        List<String> expected,
        int currentIndex,
        String operation
    ) {
        if (currentIndex < 0) {
            return expected.getFirst().equals(operation) ? 0 : -1;
        }
        if (expected.get(currentIndex).equals(operation)) {
            return currentIndex;
        }
        int next = currentIndex + 1;
        return next < expected.size() && expected.get(next).equals(operation)
            ? next : -1;
    }

    private boolean assumptionsCovered(
        List<String> required,
        List<String> available
    ) {
        Set<String> normalizedAvailable = new LinkedHashSet<>(
            AssumptionSignature.ofExpressions(available)
                .normalizedAssumptions());
        return normalizedAvailable.containsAll(
            AssumptionSignature.ofExpressions(required)
                .normalizedAssumptions());
    }

    private boolean same(String left, String right) {
        return canonicalizer.canonicalize(left)
            .equals(canonicalizer.canonicalize(right));
    }

    private String key(String expression, int operationIndex) {
        return canonicalizer.stableHash(expression) + ':' + operationIndex;
    }

    private List<String> expressions(
        String source,
        List<Transformation> steps
    ) {
        List<String> result = new ArrayList<>();
        result.add(source);
        steps.forEach(step -> result.add(step.transformedExpression()));
        return List.copyOf(result);
    }

    private List<String> compressed(List<String> operations) {
        List<String> result = new ArrayList<>();
        for (String operation : operations) {
            if (result.isEmpty() || !result.getLast().equals(operation)) {
                result.add(operation);
            }
        }
        return List.copyOf(result);
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static List<String> immutableStrings(
        List<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(
                value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                name + " must not contain blank values");
        }
        return List.copyOf(values);
    }

    public enum BatchStatus {
        REPRODUCED,
        INCOMPLETE
    }

    public record ReplayTrace(
        String traceId,
        String source,
        String target,
        List<String> primitiveSteps,
        List<String> assumptions
    ) {
        public ReplayTrace {
            traceId = requireText(traceId, "traceId");
            source = requireText(source, "source");
            target = requireText(target, "target");
            primitiveSteps = immutableStrings(
                primitiveSteps, "primitiveSteps");
            assumptions = assumptions == null
                ? List.of() : List.copyOf(assumptions);
            if (primitiveSteps.isEmpty()) {
                throw new IllegalArgumentException(
                    "replay trace has no primitive steps");
            }
        }
    }

    public record ReplayEvidence(
        String traceId,
        boolean reproduced,
        int exploredStates,
        List<String> actualRuleIds,
        List<String> assignedOperationIds,
        List<String> compressedOperationIds,
        List<String> expressionPath,
        String detail
    ) {
        public ReplayEvidence {
            traceId = requireText(traceId, "traceId");
            if (exploredStates < 0) {
                throw new IllegalArgumentException(
                    "exploredStates must not be negative");
            }
            actualRuleIds = actualRuleIds == null
                ? List.of() : List.copyOf(actualRuleIds);
            assignedOperationIds = assignedOperationIds == null
                ? List.of() : List.copyOf(assignedOperationIds);
            compressedOperationIds = compressedOperationIds == null
                ? List.of() : List.copyOf(compressedOperationIds);
            expressionPath = expressionPath == null
                ? List.of() : List.copyOf(expressionPath);
            detail = requireText(detail, "detail");
        }
    }

    public record ReplayResult(
        String traceId,
        boolean reproduced,
        Optional<SuccessfulTransformationPath> path,
        ReplayEvidence evidence
    ) {
        public ReplayResult {
            traceId = requireText(traceId, "traceId");
            path = path == null ? Optional.empty() : path;
            Objects.requireNonNull(evidence, "evidence");
            if (reproduced != path.isPresent()
                    || reproduced != evidence.reproduced()) {
                throw new IllegalArgumentException(
                    "replay result and evidence status disagree");
            }
        }
    }

    public record BatchResult(
        BatchStatus status,
        List<ReplayResult> results,
        String detail
    ) {
        public BatchResult {
            Objects.requireNonNull(status, "status");
            results = results == null ? List.of() : List.copyOf(results);
            detail = requireText(detail, "detail");
            if (results.isEmpty()) {
                throw new IllegalArgumentException(
                    "batch result must contain replay rows");
            }
        }
    }

    private record Node(
        String expression,
        List<Transformation> steps,
        List<String> assignedOperations,
        int depth,
        int operationIndex
    ) {
    }
}
