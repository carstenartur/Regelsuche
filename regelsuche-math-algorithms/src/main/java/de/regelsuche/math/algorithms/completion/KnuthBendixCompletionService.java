package de.regelsuche.math.algorithms.completion;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.validation.CompletionService;
import de.regelsuche.validation.CriticalPairService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class KnuthBendixCompletionService implements CompletionService, CriticalPairService {
    private final MathematicalAlgorithmRegistry registry;

    public KnuthBendixCompletionService(MathematicalAlgorithmRegistry registry) {
        this.registry = registry;
    }

    @Override
    public CriticalPairReport analyzeCriticalPairs(List<PatternRewriteRule> rules) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.CRITICAL_PAIRS)) {
            return new CriticalPairReport(List.of(), MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled(
                "criticalPairs is disabled"));
        }

        int maxPairs = registry.find(MathematicalAlgorithmRegistry.CRITICAL_PAIRS)
            .map(descriptor -> descriptor.budget().maxStates())
            .orElse(Integer.MAX_VALUE);
        List<CriticalPair> criticalPairs = computeCriticalPairs(rules, maxPairs);
        return new CriticalPairReport(
            criticalPairs,
            new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                "critical pair analysis complete",
                Map.of("criticalPairCount", criticalPairs.size())
            )
        );
    }

    @Override
    public CompletionReport analyzeCompletion(List<PatternRewriteRule> rules) {
        if (!registry.isEnabled(MathematicalAlgorithmRegistry.KNUTH_BENDIX)) {
            return new CompletionReport(false, List.of(), List.of(),
                MathematicalAlgorithmRegistry.AlgorithmExecutionResult.disabled("knuthBendix is disabled"));
        }

        MathematicalAlgorithmRegistry.AlgorithmBudget budget = registry.find(MathematicalAlgorithmRegistry.KNUTH_BENDIX)
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::budget)
            .orElse(MathematicalAlgorithmRegistry.AlgorithmBudget.unbounded());

        List<CriticalPair> criticalPairs = computeCriticalPairs(rules, budget.maxStates());
        List<CompletionCandidate> candidates = new ArrayList<>();
        boolean confluent = true;

        for (CriticalPair criticalPair : criticalPairs) {
            if (!joinable(criticalPair.leftBranch(), criticalPair.rightBranch(), rules, budget.maxSteps(), budget.maxStates())) {
                confluent = false;
                String from = criticalPair.leftBranch();
                String to = criticalPair.rightBranch();
                if (from.compareTo(to) > 0) {
                    String swap = from;
                    from = to;
                    to = swap;
                }
                candidates.add(new CompletionCandidate(from, to,
                    "non-joinable critical pair " + criticalPair.leftRuleId() + " vs " + criticalPair.rightRuleId()));
            }
        }

        MathematicalAlgorithmRegistry.ExecutionStatus status = candidates.size() >= budget.maxStates()
            ? MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED
            : MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS;

        return new CompletionReport(
            confluent,
            criticalPairs,
            candidates,
            new MathematicalAlgorithmRegistry.AlgorithmExecutionResult(
                status,
                MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
                confluent ? "confluent for analyzed overlaps" : "completion candidates produced",
                Map.of("criticalPairCount", criticalPairs.size(), "candidateCount", candidates.size())
            )
        );
    }

    private List<CriticalPair> computeCriticalPairs(List<PatternRewriteRule> rules, int maxPairs) {
        Map<String, CriticalPair> unique = new LinkedHashMap<>();
        outer:
        for (PatternRewriteRule leftRule : rules) {
            Expr leftSource = toExpr(leftRule.source());
            Expr leftReduced;
            try {
                leftReduced = leftRule.apply(leftSource);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            for (PatternRewriteRule rightRule : rules) {
                for (PathAndExpr occurrence : positions(leftSource)) {
                    Map<String, Expr> bindings = new HashMap<>();
                    if (!rightRule.source().match(occurrence.expression(), bindings)) {
                        continue;
                    }
                    Expr replaced = replaceAtPath(leftSource, occurrence.path(), rightRule.target().instantiate(bindings));
                    if (leftReduced.equals(replaced)) {
                        continue;
                    }
                    String key = leftRule.id() + "|" + rightRule.id() + "|" + occurrence.path() + "|"
                        + ExpressionFormatter.format(leftReduced) + "|" + ExpressionFormatter.format(replaced);
                    unique.putIfAbsent(key, new CriticalPair(
                        ExpressionFormatter.format(leftSource),
                        ExpressionFormatter.format(leftReduced),
                        ExpressionFormatter.format(replaced),
                        leftRule.id(),
                        rightRule.id(),
                        occurrence.path()
                    ));
                    if (unique.size() >= maxPairs) {
                        break outer;
                    }
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean joinable(String left,
                             String right,
                             List<PatternRewriteRule> rules,
                             int maxSteps,
                             int maxStates) {
        Set<String> leftReachable = reachable(left, rules, maxSteps, maxStates);
        Set<String> rightReachable = reachable(right, rules, maxSteps, maxStates);
        leftReachable.retainAll(rightReachable);
        return !leftReachable.isEmpty();
    }

    private Set<String> reachable(String start,
                                  List<PatternRewriteRule> rules,
                                  int maxSteps,
                                  int maxStates) {
        AstRewriteTransformationEngine engine = new AstRewriteTransformationEngine(List.copyOf(rules));
        Set<String> visited = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        queue.add(new State(start, 0));
        visited.add(start);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            if (state.depth() >= maxSteps || visited.size() >= maxStates) {
                continue;
            }
            for (Transformation transformation : engine.transform(state.expression())) {
                if (visited.add(transformation.transformedExpression())) {
                    queue.addLast(new State(transformation.transformedExpression(), state.depth() + 1));
                }
            }
        }
        return visited;
    }

    private List<PathAndExpr> positions(Expr expression) {
        List<PathAndExpr> results = new ArrayList<>();
        collectPositions(expression, "root", results);
        return results;
    }

    private void collectPositions(Expr expression, String path, List<PathAndExpr> positions) {
        positions.add(new PathAndExpr(path, expression));
        if (expression instanceof BinaryExpr binaryExpr) {
            collectPositions(binaryExpr.left(), path + "/L", positions);
            collectPositions(binaryExpr.right(), path + "/R", positions);
        } else if (expression instanceof FunctionExpr functionExpr) {
            for (int i = 0; i < functionExpr.arguments().size(); i++) {
                collectPositions(functionExpr.arguments().get(i), path + "/A" + i, positions);
            }
        }
    }

    private Expr replaceAtPath(Expr root, String path, Expr replacement) {
        if ("root".equals(path)) {
            return replacement;
        }
        String[] tokens = path.split("/");
        return replace(root, tokens, 1, replacement);
    }

    private Expr replace(Expr expression, String[] path, int index, Expr replacement) {
        if (index >= path.length) {
            return replacement;
        }
        String token = path[index];
        if (expression instanceof BinaryExpr binaryExpr) {
            if ("L".equals(token)) {
                return new BinaryExpr(replace(binaryExpr.left(), path, index + 1, replacement), binaryExpr.operator(), binaryExpr.right());
            }
            if ("R".equals(token)) {
                return new BinaryExpr(binaryExpr.left(), binaryExpr.operator(), replace(binaryExpr.right(), path, index + 1, replacement));
            }
        }
        if (expression instanceof FunctionExpr functionExpr && token.startsWith("A")) {
            int arg = Integer.parseInt(token.substring(1));
            List<Expr> args = new ArrayList<>(functionExpr.arguments());
            args.set(arg, replace(args.get(arg), path, index + 1, replacement));
            return new FunctionExpr(functionExpr.name(), args);
        }
        return expression;
    }

    private Expr toExpr(PatternExpr expression) {
        if (expression instanceof PatternExpr.Placeholder placeholder) {
            return new VariableExpr(placeholder.name());
        }
        if (expression instanceof PatternExpr.LiteralNumber literalNumber) {
            return new NumberExpr(literalNumber.value());
        }
        if (expression instanceof PatternExpr.Operation operation) {
            return new BinaryExpr(toExpr(operation.left()), operation.operator(), toExpr(operation.right()));
        }
        PatternExpr.Function function = (PatternExpr.Function) expression;
        List<Expr> args = function.arguments().stream().map(this::toExpr).toList();
        return new FunctionExpr(function.name(), args);
    }

    private record State(String expression, int depth) {
    }

    private record PathAndExpr(String path, Expr expression) {
    }
}
