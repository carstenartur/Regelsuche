package de.regelsuche.transform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Deterministic breadth-first oracle for bounded rewrite-space reachability.
 *
 * <p>The oracle deliberately distinguishes a target that is absent from a
 * completely exhausted finite closure from a search that stopped at a depth
 * or visited-state bound. It is target-aware diagnostic infrastructure, not
 * autonomous discovery evidence.</p>
 */
public final class BoundedRewriteReachabilityOracle {
    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::transformedExpression)
            .thenComparing(Transformation::rule)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(transformation ->
                String.join("\u0000", transformation.assumptions()))
            .thenComparing(transformation ->
                String.join("\u0000", transformation.primitiveRuleIds()));

    private final TransformationEngine engine;
    private final UnaryOperator<String> canonicalizer;

    public BoundedRewriteReachabilityOracle(TransformationEngine engine) {
        this(engine, String::trim);
    }

    public BoundedRewriteReachabilityOracle(
        TransformationEngine engine,
        UnaryOperator<String> canonicalizer
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    public Result search(String sourceExpression, String targetExpression, Budget budget) {
        String source = requireExpression(sourceExpression, "sourceExpression");
        String target = requireExpression(targetExpression, "targetExpression");
        Budget safeBudget = Objects.requireNonNull(budget, "budget");
        String sourceKey = canonicalize(source);
        String targetKey = canonicalize(target);

        Map<String, Node> visited = new LinkedHashMap<>();
        visited.put(sourceKey, new Node(source, sourceKey, 0, null, null));
        if (sourceKey.equals(targetKey)) {
            return Result.reachable(List.of(), 1, 0, 0);
        }

        ArrayDeque<Node> frontier = new ArrayDeque<>();
        frontier.addLast(visited.get(sourceKey));
        long generatedTransitions = 0;
        int maximumDepthReached = 0;
        boolean depthLimitReached = false;

        while (!frontier.isEmpty()) {
            Node current = frontier.removeFirst();
            maximumDepthReached = Math.max(maximumDepthReached, current.depth());
            List<Transformation> transformations = new ArrayList<>(
                engine.transform(current.expression()));
            transformations.sort(TRANSFORMATION_ORDER);

            for (Transformation transformation : transformations) {
                generatedTransitions++;
                String nextExpression = requireExpression(
                    transformation.transformedExpression(),
                    "transformation.transformedExpression");
                String nextKey = canonicalize(nextExpression);
                if (visited.containsKey(nextKey)) {
                    continue;
                }
                if (current.depth() >= safeBudget.maxDepth()) {
                    depthLimitReached = true;
                    continue;
                }
                if (visited.size() >= safeBudget.maxVisitedStates()) {
                    return Result.inconclusive(
                        visited.size(),
                        generatedTransitions,
                        maximumDepthReached,
                        depthLimitReached,
                        true);
                }

                Step step = Step.from(current.expression(), transformation);
                Node next = new Node(
                    nextExpression,
                    nextKey,
                    current.depth() + 1,
                    current.key(),
                    step);
                visited.put(nextKey, next);
                maximumDepthReached = Math.max(maximumDepthReached, next.depth());
                if (nextKey.equals(targetKey)) {
                    return Result.reachable(
                        reconstructWitness(visited, next),
                        visited.size(),
                        generatedTransitions,
                        maximumDepthReached);
                }
                frontier.addLast(next);
            }
        }

        if (depthLimitReached) {
            return Result.inconclusive(
                visited.size(),
                generatedTransitions,
                maximumDepthReached,
                true,
                false);
        }
        return Result.exhausted(
            visited.size(),
            generatedTransitions,
            maximumDepthReached);
    }

    private List<Step> reconstructWitness(Map<String, Node> visited, Node target) {
        ArrayDeque<Step> reverse = new ArrayDeque<>();
        Node current = target;
        while (current.parentKey() != null) {
            reverse.addFirst(current.incomingStep());
            current = visited.get(current.parentKey());
            if (current == null) {
                throw new IllegalStateException("reachability parent chain is incomplete");
            }
        }
        return List.copyOf(reverse);
    }

    private String canonicalize(String expression) {
        String canonical = canonicalizer.apply(expression);
        return requireExpression(canonical, "canonical expression");
    }

    private static String requireExpression(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public enum Status {
        REACHABLE,
        UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
        BUDGET_INCONCLUSIVE
    }

    public record Budget(int maxDepth, int maxVisitedStates) {
        public Budget {
            if (maxDepth < 0) {
                throw new IllegalArgumentException("maxDepth must not be negative");
            }
            if (maxVisitedStates < 1) {
                throw new IllegalArgumentException("maxVisitedStates must be positive");
            }
        }
    }

    public record Step(
        String expressionBefore,
        String expressionAfter,
        String rule,
        List<String> assumptions,
        String applicationKey,
        List<String> primitiveRuleIds,
        int primitiveStepCount
    ) {
        public Step {
            expressionBefore = requireExpression(expressionBefore, "expressionBefore");
            expressionAfter = requireExpression(expressionAfter, "expressionAfter");
            rule = requireExpression(rule, "rule");
            assumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
            applicationKey = Objects.requireNonNull(applicationKey, "applicationKey");
            primitiveRuleIds = List.copyOf(
                Objects.requireNonNull(primitiveRuleIds, "primitiveRuleIds"));
            if (primitiveStepCount < 1) {
                throw new IllegalArgumentException("primitiveStepCount must be positive");
            }
        }

        private static Step from(String expressionBefore, Transformation transformation) {
            return new Step(
                expressionBefore,
                transformation.transformedExpression(),
                transformation.rule(),
                transformation.assumptions(),
                transformation.applicationKey(),
                transformation.primitiveRuleIds(),
                transformation.primitiveStepCount());
        }
    }

    public record Result(
        Status status,
        List<Step> witness,
        int visitedStates,
        long generatedTransitions,
        int maximumDepthReached,
        boolean depthLimitReached,
        boolean stateLimitReached
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            witness = List.copyOf(Objects.requireNonNull(witness, "witness"));
            if (visitedStates < 1) {
                throw new IllegalArgumentException("visitedStates must be positive");
            }
            if (generatedTransitions < 0 || maximumDepthReached < 0) {
                throw new IllegalArgumentException("reachability counters must not be negative");
            }
            if (status == Status.REACHABLE && (depthLimitReached || stateLimitReached)) {
                throw new IllegalArgumentException("reachable result must not report a limit");
            }
            if (status == Status.UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE
                && (depthLimitReached || stateLimitReached)) {
                throw new IllegalArgumentException("complete closure must not report a limit");
            }
            if (status == Status.BUDGET_INCONCLUSIVE
                && !depthLimitReached && !stateLimitReached) {
                throw new IllegalArgumentException("inconclusive result requires a reached limit");
            }
        }

        private static Result reachable(
            List<Step> witness,
            int visitedStates,
            long generatedTransitions,
            int maximumDepthReached
        ) {
            return new Result(
                Status.REACHABLE,
                witness,
                visitedStates,
                generatedTransitions,
                maximumDepthReached,
                false,
                false);
        }

        private static Result exhausted(
            int visitedStates,
            long generatedTransitions,
            int maximumDepthReached
        ) {
            return new Result(
                Status.UNREACHABLE_IN_COMPLETE_FROZEN_CLOSURE,
                List.of(),
                visitedStates,
                generatedTransitions,
                maximumDepthReached,
                false,
                false);
        }

        private static Result inconclusive(
            int visitedStates,
            long generatedTransitions,
            int maximumDepthReached,
            boolean depthLimitReached,
            boolean stateLimitReached
        ) {
            return new Result(
                Status.BUDGET_INCONCLUSIVE,
                List.of(),
                visitedStates,
                generatedTransitions,
                maximumDepthReached,
                depthLimitReached,
                stateLimitReached);
        }
    }

    private record Node(
        String expression,
        String key,
        int depth,
        String parentKey,
        Step incomingStep
    ) {
    }
}
