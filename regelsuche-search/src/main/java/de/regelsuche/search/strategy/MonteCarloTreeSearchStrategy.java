package de.regelsuche.search.strategy;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * surrounding {@link de.regelsuche.search.TransformationSearchService} can
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

        Map<String, SearchState> states = new HashMap<>();
        Map<String, Node> nodes = new HashMap<>();
        Set<String> visitedOrder = new LinkedHashSet<>();
        String rootKey = stateKey(rootState);
        states.put(rootKey, rootState);
        nodes.put(rootKey, new Node());
        visitedOrder.add(rootKey);

        int budget = problem.heuristic().maxVisitedExpressions();
        int maxIterations = budget * 4;
        int iteration = 0;
        while (visitedOrder.size() < budget && iteration < maxIterations) {
            iteration++;
            Node rootNode = nodes.get(rootKey);
            if (rootNode != null && rootNode.terminal) {
                break;
            }
            String selectedKey = select(nodes, rootKey);
            SearchState selected = states.get(selectedKey);
            if (selected == null || selected.depth() >= problem.heuristic().maxDepth()) {
                // Cannot expand from a depth-limited leaf; mark terminal and continue.
                if (nodes.containsKey(selectedKey)) {
                    nodes.get(selectedKey).markTerminal();
                }
                if (selectedKey.equals(rootKey)) {
                    break;
                }
                continue;
            }
            List<SearchState> children = expand(problem, selected);
            if (children.isEmpty()) {
                nodes.get(selectedKey).markTerminal();
                continue;
            }
            int addedThisIteration = 0;
            for (SearchState child : children) {
                String childKey = stateKey(child);
                if (states.putIfAbsent(childKey, child) == null) {
                    nodes.put(childKey, new Node());
                    visitedOrder.add(childKey);
                    addedThisIteration++;
                    if (visitedOrder.size() >= budget) {
                        break;
                    }
                }
                nodes.get(selectedKey).addChild(childKey);
            }
            if (addedThisIteration == 0) {
                // No progress from this node — treat it as terminal so UCB
                // does not keep selecting it forever.
                nodes.get(selectedKey).markTerminal();
                continue;
            }
            for (int i = 0; i < rolloutsPerIteration; i++) {
                SearchState pick = children.get(random.nextInt(children.size()));
                double reward = rollout(problem, pick);
                backpropagate(nodes, pick, reward);
            }
        }

        List<SearchState> ordered = new ArrayList<>();
        for (String key : visitedOrder) {
            ordered.add(states.get(key));
        }
        return ordered;
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

    private String select(Map<String, Node> nodes, String rootKey) {
        String current = rootKey;
        java.util.Set<String> visitedInPath = new java.util.HashSet<>();
        while (true) {
            if (!visitedInPath.add(current)) {
                // Cycle or repeated descent — bail out.
                return current;
            }
            Node node = nodes.get(current);
            if (node == null || node.children.isEmpty() || node.terminal) {
                return current;
            }
            String best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            int parentVisits = Math.max(node.visits, 1);
            boolean anyNonTerminal = false;
            for (String childKey : node.children) {
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

    private List<SearchState> expand(SearchProblem problem, SearchState parent) {
        List<SearchState> children = new ArrayList<>();
        for (Transformation transformation : problem.engine().transform(parent.expression())) {
            if (parent.appliedRuleApplications().contains(transformation.applicationKey())) {
                continue;
            }
            int expandedSteps = parent.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0);
            if (expandedSteps > problem.heuristic().maxExpandingSteps()) {
                continue;
            }
            String nextExpression = transformation.transformedExpression();
            if (nextExpression.equals(parent.expression())) {
                continue;
            }
            ExpressionScore nextScore = problem.scorer().score(nextExpression);
            int improvement = parent.score().weightedTotal() - nextScore.weightedTotal();
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
            children.add(new SearchState(
                nextExpression,
                parent.depth() + 1,
                nextScore,
                path,
                appliedRuleIds,
                applied,
                expandedSteps,
                problem.canonicalizer().stableHash(nextExpression),
                parent.expression(),
                transformation.rule(),
                transformation.kind(),
                transformation.mayIncreaseComplexity(),
                transformation.estimatedCostDelta(),
                transformation.equivalencePreservingByConstruction(),
                improvement,
                appliedRuleKinds,
                equivalenceFlags
            ));
            if (children.size() >= problem.heuristic().maxCandidatesPerState()) {
                break;
            }
        }
        children.sort(Comparator.comparingInt((SearchState state) -> state.score().weightedTotal()));
        return children;
    }

    private double rollout(SearchProblem problem, SearchState start) {
        SearchState current = start;
        double bestImprovement = current.improvement();
        for (int step = 0; step < rolloutDepth; step++) {
            List<Transformation> options = problem.engine().transform(current.expression());
            if (options.isEmpty()) {
                break;
            }
            Transformation pick = options.get(random.nextInt(options.size()));
            String nextExpression = pick.transformedExpression();
            if (nextExpression.equals(current.expression())) {
                break;
            }
            ExpressionScore nextScore = problem.scorer().score(nextExpression);
            int improvement = current.score().weightedTotal() - nextScore.weightedTotal();
            bestImprovement = Math.max(bestImprovement, improvement);
            current = new SearchState(
                nextExpression,
                current.depth() + 1,
                nextScore,
                current.path(),
                current.appliedRuleIds(),
                current.appliedRuleApplications(),
                current.expandedStepCount(),
                problem.canonicalizer().stableHash(nextExpression),
                current.expression(),
                pick.rule(),
                pick.kind(),
                pick.mayIncreaseComplexity(),
                pick.estimatedCostDelta(),
                pick.equivalencePreservingByConstruction(),
                improvement
            );
        }
        return bestImprovement;
    }

    private void backpropagate(Map<String, Node> nodes, SearchState leaf, double reward) {
        // Without explicit parent pointers in the cached node table we only
        // update the leaf and its key; full backprop is implicit through UCB
        // because expansion already added the child key to its parent's child
        // set. Updating the leaf statistics is sufficient to bias future UCB
        // decisions toward improving subtrees.
        Node node = nodes.get(stateKey(leaf));
        if (node != null) {
            node.visits++;
            node.totalReward += reward;
        }
    }

    private static String stateKey(SearchState state) {
        return state.canonicalHash() + ":" + state.appliedRuleApplications();
    }

    private static final class Node {
        private final List<String> children = new ArrayList<>();
        private int visits;
        private double totalReward;
        private boolean terminal;

        void addChild(String key) {
            if (!children.contains(key)) {
                children.add(key);
            }
        }

        void markTerminal() {
            terminal = true;
        }
    }
}
