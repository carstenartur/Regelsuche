package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Monte Carlo Tree Search with UCB1 selection.
 *
 * <p>Unlike {@link RandomMonteCarloSearchStrategy} which only does uniform
 * random selection, MCTS keeps statistics (visit count, accumulated
 * improvement) per state and expands the most promising node next.</p>
 *
 * <p>The MCTS variant is well suited to large transformation spaces where
 * neither a strict ordering (best-first) nor pure randomness explore all
 * promising branches. It returns the union of all explored states so the
 * surrounding search service can
 * record the search graph as usual.</p>
 */
public class MonteCarloTreeSearchStrategy implements SearchStrategy {
    private static final double EXPLORATION_CONSTANT = Math.sqrt(2.0);

    private final Random random;
    private final int rolloutsPerIteration;
    private final int rolloutDepth;

    public MonteCarloTreeSearchStrategy(long seed) {
        this(seed, 1, 4);
    }

    public MonteCarloTreeSearchStrategy(long seed, int rolloutsPerIteration, int rolloutDepth) {
        if (rolloutsPerIteration < 1 || rolloutDepth < 1) {
            throw new IllegalArgumentException("rollouts and depth must be positive");
        }
        this.random = new Random(seed);
        this.rolloutsPerIteration = rolloutsPerIteration;
        this.rolloutDepth = rolloutDepth;
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        SearchState rootState = newRoot(root, problem);

        // This controller retains complete paths. A semantic value hash alone
        // cannot identify a continuation or its proof/assumption obligations.
        Map<SearchState, Node> nodes = new LinkedHashMap<>();
        nodes.put(rootState, new Node(null));

        int budget = problem.heuristic().maxVisitedExpressions();
        long maxIterations = 4L * budget;
        long iteration = 0;
        while (nodes.size() < budget && iteration < maxIterations) {
            iteration++;
            if (nodes.get(rootState).terminal) {
                break;
            }
            SearchState selected = select(nodes, rootState);
            Node selectedNode = nodes.get(selected);
            if (selectedNode.terminal) {
                continue;
            }
            if (selected.depth() >= problem.heuristic().maxDepth()) {
                selectedNode.markTerminal();
                continue;
            }
            List<SearchState> children = expand(problem, selected);
            List<SearchState> admitted = new ArrayList<>();
            for (SearchState child : children) {
                if (nodes.putIfAbsent(child, new Node(selectedNode)) == null) {
                    admitted.add(child);
                }
                selectedNode.addChild(child);
                if (nodes.size() >= budget) {
                    break;
                }
            }
            if (admitted.isEmpty()) {
                selectedNode.markTerminal();
                continue;
            }
            // Do not roll out a candidate that was never admitted to the tree.
            for (int i = 0; i < rolloutsPerIteration; i++) {
                SearchState pick = admitted.get(random.nextInt(admitted.size()));
                double reward = rollout(problem, pick);
                backpropagate(nodes, pick, reward);
            }
        }
        return new ArrayList<>(nodes.keySet());
    }

    private SearchState newRoot(String root, SearchProblem problem) {
        ExpressionScore rootScore = problem.scorer().score(root);
        return new SearchState(
            root,
            0,
            rootScore,
            List.of(root),
            List.of(),
            Set.of(),
            0,
            problem.canonicalizer().stableHash(root),
            null,
            null,
            RewriteKind.NORMALIZE,
            false,
            0,
            true,
            0
        );
    }

    private SearchState select(Map<SearchState, Node> nodes, SearchState rootKey) {
        SearchState current = rootKey;
        Set<SearchState> visitedInPath = new java.util.HashSet<>();
        while (true) {
            if (!visitedInPath.add(current)) {
                // Cycle or repeated descent — bail out.
                return current;
            }
            Node node = nodes.get(current);
            if (node == null || node.children.isEmpty() || node.terminal) {
                return current;
            }
            SearchState best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            long parentVisits = Math.max(node.visits, 1L);
            boolean anyNonTerminal = false;
            for (SearchState childKey : node.children) {
                Node child = nodes.get(childKey);
                if (child == null || child.terminal) {
                    continue;
                }
                anyNonTerminal = true;
                double exploitation = child.visits == 0 ? 0 : child.totalReward / child.visits;
                double exploration = EXPLORATION_CONSTANT
                    * Math.sqrt(Math.log(parentVisits + 1) / (child.visits + 1));
                double ucb = exploitation + exploration;
                if (ucb > bestScore) {
                    bestScore = ucb;
                    best = childKey;
                }
            }
            if (!anyNonTerminal) {
                // All children exhausted — propagate terminality upward.
                node.markTerminal();
                return current;
            }
            if (best == null) {
                return current;
            }
            current = best;
        }
    }

    private List<Transformation> eligibleTransformations(SearchProblem problem, SearchState parent) {
        if (parent.depth() >= problem.heuristic().maxDepth()) {
            return List.of();
        }
        return problem.transformations(parent.expression()).stream()
            .filter(step -> !parent.appliedRuleApplications().contains(step.applicationKey()))
            .filter(step -> step.kind() != RewriteKind.EXPAND
                || parent.expandedStepCount() < problem.heuristic().maxExpandingSteps())
            .filter(step -> !step.transformedExpression().equals(parent.expression()))
            .limit(problem.heuristic().maxCandidatesPerState())
            .toList();
    }

    private List<SearchState> expand(SearchProblem problem, SearchState parent) {
        List<SearchState> children = new ArrayList<>();
        for (Transformation transformation : eligibleTransformations(problem, parent)) {
            children.add(childState(problem, parent, transformation));
        }
        children.sort(Comparator.comparingInt(state -> state.score().weightedTotal()));
        return children;
    }

    private SearchState childState(SearchProblem problem, SearchState parent, Transformation transformation) {
        String nextExpression = transformation.transformedExpression();
        ExpressionScore nextScore = problem.scorer().score(nextExpression);
        Set<String> applied = new java.util.HashSet<>(parent.appliedRuleApplications());
        applied.add(transformation.applicationKey());
        List<String> path = new ArrayList<>(parent.path());
        path.add(nextExpression);
        List<String> appliedRuleIds = new ArrayList<>(parent.appliedRuleIds());
        appliedRuleIds.add(transformation.rule());
        List<RewriteKind> appliedRuleKinds = new ArrayList<>(parent.appliedRuleKinds());
        appliedRuleKinds.add(transformation.kind());
        List<Boolean> equivalenceFlags = new ArrayList<>(parent.equivalencePreservingFlags());
        equivalenceFlags.add(transformation.equivalencePreservingByConstruction());
        return new SearchState(
            nextExpression, parent.depth() + 1, nextScore, path, appliedRuleIds, applied,
            parent.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0),
            problem.canonicalizer().stableHash(nextExpression), parent.expression(),
            transformation.rule(), transformation.kind(), transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(), transformation.equivalencePreservingByConstruction(),
            parent.score().weightedTotal() - nextScore.weightedTotal(),
            appliedRuleKinds, equivalenceFlags, SearchState.accumulatedAssumptions(parent, transformation),
            SearchState.extendedPath(parent, transformation)
        );
    }

    private double rollout(SearchProblem problem, SearchState start) {
        SearchState current = start;
        double bestImprovement = current.improvement();
        for (int step = 0; step < rolloutDepth; step++) {
            List<Transformation> options = eligibleTransformations(problem, current);
            if (options.isEmpty()) {
                break;
            }
            current = childState(problem, current, options.get(random.nextInt(options.size())));
            bestImprovement = Math.max(bestImprovement, current.improvement());
        }
        return bestImprovement;
    }

    private void backpropagate(Map<SearchState, Node> nodes, SearchState leaf, double reward) {
        // Each admitted state retains its full path, so its parent is unique.
        // Credit the simulated outcome to every choice on that path, including
        // the root, rather than starving ancestors of visit/reward statistics.
        for (Node node = nodes.get(leaf); node != null; node = node.parent) {
            node.visits++;
            node.totalReward += reward;
        }
    }

    private static final class Node {
        private final Node parent;
        private final List<SearchState> children = new ArrayList<>();
        private long visits;
        private double totalReward;
        private boolean terminal;

        Node(Node parent) {
            this.parent = parent;
        }

        void addChild(SearchState key) {
            if (!children.contains(key)) {
                children.add(key);
            }
        }

        void markTerminal() {
            terminal = true;
        }
    }
}
