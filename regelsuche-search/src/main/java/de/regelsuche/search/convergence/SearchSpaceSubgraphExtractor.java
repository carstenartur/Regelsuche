package de.regelsuche.search.convergence;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.Edge;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.Node;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.PathMembership;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Extracts a bounded real search-space subgraph from a {@link SearchProblem}. */
public final class SearchSpaceSubgraphExtractor {
    private static final int MAX_RENDERED_DEPTH = 4;
    private static final int MAX_NODES = 25;

    private final RuleFamilyClassifier classifier = new RuleFamilyClassifier();

    public SearchSpaceSubgraph extract(
        SearchProblem problem,
        List<SearchState> exploredStates,
        ConvergentDiscoveryReport report
    ) {
        int maxDepth = Math.min(problem.heuristic().maxDepth(), MAX_RENDERED_DEPTH);
        Map<String, SearchState> selectedStates = selectStates(problem, exploredStates, report, maxDepth);
        Map<String, Set<RuleFamily>> incomingFamilies = new HashMap<>();
        List<EdgeDraft> edgeDrafts = collectEdges(problem, selectedStates, report, maxDepth, incomingFamilies);
        Set<String> sources = new HashSet<>();
        for (EdgeDraft edge : edgeDrafts) {
            sources.add(edge.fromId());
        }

        List<Node> nodes = selectedStates.values().stream()
            .sorted(Comparator.comparingInt(SearchState::depth).thenComparing(SearchState::expression))
            .map(state -> node(problem, state, report, incomingFamilies.getOrDefault(id(state.canonicalHash()), Set.of()),
                sources.contains(id(state.canonicalHash()))))
            .toList();
        Set<String> nodeIds = nodes.stream().map(Node::id).collect(java.util.stream.Collectors.toSet());
        List<Edge> edges = edgeDrafts.stream()
            .filter(edge -> nodeIds.contains(edge.fromId()) && nodeIds.contains(edge.toId()))
            .map(edge -> edge(edge, selectedStates, report))
            .toList();
        return new SearchSpaceSubgraph(problem.rootExpression(), nodes, edges, maxDepth);
    }

    private Map<String, SearchState> selectStates(
        SearchProblem problem,
        List<SearchState> exploredStates,
        ConvergentDiscoveryReport report,
        int maxDepth
    ) {
        Map<String, SearchState> byHash = new LinkedHashMap<>();
        for (SearchState state : exploredStates) {
            if (state.depth() <= maxDepth) {
                byHash.putIfAbsent(id(state.canonicalHash()), state);
            }
        }
        if (byHash.isEmpty()) {
            SearchState root = rootState(problem);
            byHash.put(id(root.canonicalHash()), root);
        }
        for (ConvergentPath path : report.pathsToTarget()) {
            for (int depth = 0; depth < path.expressions().size() && depth <= maxDepth; depth++) {
                String expression = path.expressions().get(depth);
                String hash = problem.canonicalizer().stableHash(expression);
                byHash.putIfAbsent(id(hash), syntheticState(problem, path, depth, expression, hash));
            }
        }
        if (byHash.size() <= MAX_NODES) {
            return byHash;
        }
        Set<String> protectedIds = protectedNodeIds(problem, report);
        Map<String, SearchState> limited = new LinkedHashMap<>();
        byHash.entrySet().stream()
            .filter(entry -> protectedIds.contains(entry.getKey()))
            .forEach(entry -> limited.put(entry.getKey(), entry.getValue()));
        byHash.entrySet().stream()
            .filter(entry -> !limited.containsKey(entry.getKey()))
            .sorted(Comparator
                .comparingInt((Map.Entry<String, SearchState> entry) -> entry.getValue().depth())
                .thenComparingInt(entry -> entry.getValue().score().weightedTotal())
                .thenComparing(Map.Entry::getKey))
            .limit(Math.max(0, MAX_NODES - limited.size()))
            .forEach(entry -> limited.put(entry.getKey(), entry.getValue()));
        return limited;
    }

    private List<EdgeDraft> collectEdges(
        SearchProblem problem,
        Map<String, SearchState> states,
        ConvergentDiscoveryReport report,
        int maxDepth,
        Map<String, Set<RuleFamily>> incomingFamilies
    ) {
        List<EdgeDraft> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (SearchState state : states.values()) {
            if (state.parentExpression() != null && state.appliedRuleId() != null) {
                String from = id(problem.canonicalizer().stableHash(state.parentExpression()));
                String to = id(state.canonicalHash());
                addEdge(edges, edgeKeys, incomingFamilies, from, to, state.appliedRuleId(), state.depth());
            }
        }
        for (ConvergentPath path : report.pathsToTarget()) {
            for (int index = 1; index < path.expressions().size(); index++) {
                String from = id(problem.canonicalizer().stableHash(path.expressions().get(index - 1)));
                String to = id(problem.canonicalizer().stableHash(path.expressions().get(index)));
                addEdge(edges, edgeKeys, incomingFamilies, from, to, path.ruleIds().get(index - 1), index);
            }
        }
        for (SearchState state : states.values()) {
            if (state.depth() >= maxDepth) {
                continue;
            }
            for (Transformation transformation : problem.engine().transform(state.expression())) {
                String to = id(problem.canonicalizer().stableHash(transformation.transformedExpression()));
                if (states.containsKey(to)) {
                    addEdge(edges, edgeKeys, incomingFamilies, id(state.canonicalHash()), to,
                        transformation.rule(), state.depth() + 1);
                }
            }
        }
        return edges;
    }

    private void addEdge(
        List<EdgeDraft> edges,
        Set<String> edgeKeys,
        Map<String, Set<RuleFamily>> incomingFamilies,
        String from,
        String to,
        String ruleId,
        int depth
    ) {
        RuleFamily family = classifier.classify(ruleId);
        String key = from + "->" + to + "|" + ruleId;
        if (edgeKeys.add(key)) {
            edges.add(new EdgeDraft("edge_" + edgeKeys.size(), from, to, ruleId, family, depth));
            incomingFamilies.computeIfAbsent(to, ignored -> new LinkedHashSet<>()).add(family);
        }
    }

    private Node node(
        SearchProblem problem,
        SearchState state,
        ConvergentDiscoveryReport report,
        Set<RuleFamily> ruleFamilies,
        boolean hasOutgoingEdge
    ) {
        Set<PathMembership> membership = pathMembership(problem, state.expression(), report);
        boolean isTarget = membership.contains(PathMembership.TARGET);
        boolean didactic = membership.contains(PathMembership.DIDACTIC);
        boolean macro = membership.contains(PathMembership.MACRO);
        boolean notSelected = !isTarget && !didactic && !macro;
        boolean isDeadEnd = notSelected && !hasOutgoingEdge;
        return new Node(
            id(state.canonicalHash()),
            state.expression(),
            membership,
            ruleFamilies,
            state.depth(),
            state.score().weightedTotal(),
            isTarget,
            didactic,
            macro,
            isDeadEnd,
            notSelected
        );
    }

    private Edge edge(EdgeDraft edge, Map<String, SearchState> states, ConvergentDiscoveryReport report) {
        SearchState to = states.get(edge.toId());
        boolean isTarget = to != null && isTarget(to.expression(), report);
        boolean didactic = isPathEdge(edge, report, false);
        boolean macro = isPathEdge(edge, report, true);
        boolean notSelected = !isTarget && !didactic && !macro;
        return new Edge(
            edge.id(),
            edge.fromId(),
            edge.toId(),
            edge.ruleId(),
            edge.family(),
            edge.depth(),
            to == null ? 0 : to.score().weightedTotal(),
            isTarget,
            didactic,
            macro,
            false,
            notSelected
        );
    }

    private Set<PathMembership> pathMembership(SearchProblem problem, String expression, ConvergentDiscoveryReport report) {
        Set<PathMembership> membership = new LinkedHashSet<>();
        String hash = problem.canonicalizer().stableHash(expression);
        if (isTarget(expression, report)) {
            membership.add(PathMembership.TARGET);
        }
        selectedPath(report, false).ifPresent(path -> {
            if (containsHash(problem, path, hash)) {
                membership.add(PathMembership.DIDACTIC);
            }
        });
        selectedPath(report, true).ifPresent(path -> {
            if (containsHash(problem, path, hash)) {
                membership.add(PathMembership.MACRO);
            }
        });
        if (membership.isEmpty()) {
            membership.add(PathMembership.ALTERNATIVE);
        }
        return membership;
    }

    private java.util.Optional<ConvergentPath> selectedPath(ConvergentDiscoveryReport report, boolean macro) {
        if (report.convergentStates().isEmpty()) {
            return java.util.Optional.empty();
        }
        String pathId = macro
            ? report.convergentStates().getFirst().macroPathId().orElse(null)
            : report.convergentStates().getFirst().mostDidacticPathId();
        return report.pathsToTarget().stream().filter(path -> path.pathId().equals(pathId)).findFirst();
    }

    private boolean containsHash(SearchProblem problem, ConvergentPath path, String hash) {
        return path.expressions().stream()
            .map(problem.canonicalizer()::stableHash)
            .anyMatch(hash::equals);
    }

    private boolean isTarget(String expression, ConvergentDiscoveryReport report) {
        String canonical = new de.regelsuche.canonical.ExpressionCanonicalizer().canonicalize(expression);
        return canonical.equals(report.canonicalTargetExpression())
            || report.convergentStates().stream().anyMatch(state -> state.expression().equals(expression));
    }

    private boolean isPathEdge(EdgeDraft edge, ConvergentDiscoveryReport report, boolean macro) {
        return selectedPath(report, macro).stream().anyMatch(path -> {
            for (int index = 1; index < path.expressions().size(); index++) {
                String rule = path.ruleIds().get(index - 1);
                if (rule.equals(edge.ruleId())) {
                    return true;
                }
            }
            return false;
        });
    }

    private Set<String> protectedNodeIds(SearchProblem problem, ConvergentDiscoveryReport report) {
        Set<String> ids = new HashSet<>();
        ids.add(id(problem.canonicalizer().stableHash(problem.rootExpression())));
        for (ConvergentPath path : report.pathsToTarget()) {
            for (String expression : path.expressions()) {
                ids.add(id(problem.canonicalizer().stableHash(expression)));
            }
        }
        return ids;
    }

    private SearchState rootState(SearchProblem problem) {
        String root = problem.rootExpression().trim().replaceAll("\\s+", " ");
        return new SearchState(root, 0, problem.scorer().score(root), List.of(root), List.of(), Set.of(), 0,
            problem.canonicalizer().stableHash(root), null, null, RewriteKind.NORMALIZE, false, 0, true, 0);
    }

    private SearchState syntheticState(SearchProblem problem, ConvergentPath path, int depth, String expression, String hash) {
        ExpressionScore score = problem.scorer().score(expression);
        List<String> expressions = path.expressions().subList(0, depth + 1);
        List<String> rules = path.ruleIds().subList(0, Math.min(depth, path.ruleIds().size()));
        return new SearchState(expression, depth, score, expressions, rules, Set.copyOf(rules), 0, hash,
            depth == 0 ? null : path.expressions().get(depth - 1),
            depth == 0 ? null : path.ruleIds().get(depth - 1),
            RewriteKind.NORMALIZE, false, 0, true, 0);
    }

    private String id(String canonicalHash) {
        return "space_" + canonicalHash;
    }

    private record EdgeDraft(String id, String fromId, String toId, String ruleId, RuleFamily family, int depth) {
    }
}
