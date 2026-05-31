package de.regelsuche.search.convergence;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.Edge;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.Node;
import de.regelsuche.search.convergence.SearchSpaceSubgraph.PathMembership;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        List<EdgeDraft> edgeDrafts = collectEdges(problem, selectedStates, report, maxDepth);
        Set<String> nodeIds = Set.copyOf(selectedStates.keySet());
        List<EdgeDraft> renderedEdgeDrafts = edgeDrafts.stream()
            .filter(edge -> nodeIds.contains(edge.fromId()) && nodeIds.contains(edge.toId()))
            .toList();
        Map<String, Set<RuleFamily>> incomingFamilies = new HashMap<>();
        Set<String> sources = new HashSet<>();
        for (EdgeDraft edge : renderedEdgeDrafts) {
            sources.add(edge.fromId());
            incomingFamilies.computeIfAbsent(edge.toId(), ignored -> new LinkedHashSet<>()).add(edge.family());
        }

        List<Node> nodes = selectedStates.values().stream()
            .sorted(Comparator.comparingInt(SearchState::depth).thenComparing(SearchState::expression))
            .map(state -> node(problem, state, report,
                incomingFamilies.getOrDefault(idForExpression(state.expression()), Set.of()),
                sources.contains(idForExpression(state.expression()))))
            .toList();
        List<Edge> edges = renderedEdgeDrafts.stream()
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
                byHash.putIfAbsent(idForExpression(state.expression()), state);
            }
        }
        if (byHash.isEmpty()) {
            SearchState root = rootState(problem);
            byHash.put(idForExpression(root.expression()), root);
        }
        expandBounded(problem, byHash, maxDepth);
        for (ConvergentPath path : report.pathsToTarget()) {
            for (int depth = 0; depth < path.expressions().size() && depth <= maxDepth; depth++) {
                String expression = path.expressions().get(depth);
                String hash = problem.canonicalizer().stableHash(expression);
                byHash.putIfAbsent(idForExpression(expression), syntheticState(problem, path, depth, expression, hash));
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
        int maxDepth
    ) {
        List<EdgeDraft> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (SearchState state : states.values()) {
            if (state.parentExpression() != null && state.appliedRuleId() != null) {
                String from = idForExpression(state.parentExpression());
                String to = idForExpression(state.expression());
                addEdge(edges, edgeKeys, from, to, state.appliedRuleId(), state.depth());
            }
        }
        for (ConvergentPath path : report.pathsToTarget()) {
            for (int index = 1; index < path.expressions().size(); index++) {
                String from = idForExpression(path.expressions().get(index - 1));
                String to = idForExpression(path.expressions().get(index));
                addEdge(edges, edgeKeys, from, to, path.ruleIds().get(index - 1), index);
            }
        }
        for (SearchState state : states.values()) {
            if (state.depth() >= maxDepth) {
                continue;
            }
            for (Transformation transformation : problem.engine().transform(state.expression())) {
                String to = idForExpression(transformation.transformedExpression());
                SearchState toState = states.get(to);
                if (toState != null && toState.depth() == state.depth() + 1) {
                    addEdge(edges, edgeKeys, idForExpression(state.expression()), to,
                        transformation.rule(), state.depth() + 1);
                }
            }
        }
        return edges;
    }

    private void addEdge(
        List<EdgeDraft> edges,
        Set<String> edgeKeys,
        String from,
        String to,
        String ruleId,
        int depth
    ) {
        RuleFamily family = classifier.classify(ruleId);
        String key = from + "->" + to + "|" + ruleId;
        if (edgeKeys.add(key)) {
            edges.add(new EdgeDraft("edge_" + edgeKeys.size(), from, to, ruleId, family, depth));
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
            idForExpression(state.expression()),
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
        if (isTarget(expression, report)) {
            membership.add(PathMembership.TARGET);
        }
        selectedPath(report, false).ifPresent(path -> {
            if (path.expressions().contains(expression)) {
                membership.add(PathMembership.DIDACTIC);
            }
        });
        selectedPath(report, true).ifPresent(path -> {
            if (path.expressions().contains(expression)) {
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

    private boolean isTarget(String expression, ConvergentDiscoveryReport report) {
        String canonical = new de.regelsuche.canonical.ExpressionCanonicalizer().canonicalize(expression);
        return canonical.equals(report.canonicalTargetExpression())
            || report.convergentStates().stream().anyMatch(state -> state.expression().equals(expression));
    }

    private boolean isPathEdge(EdgeDraft edge, ConvergentDiscoveryReport report, boolean macro) {
        return selectedPath(report, macro).stream().anyMatch(path -> {
            for (int index = 1; index < path.expressions().size(); index++) {
                String rule = path.ruleIds().get(index - 1);
                if (rule.equals(edge.ruleId())
                    && idForExpression(path.expressions().get(index - 1)).equals(edge.fromId())
                    && idForExpression(path.expressions().get(index)).equals(edge.toId())) {
                    return true;
                }
            }
            return false;
        });
    }

    private Set<String> protectedNodeIds(SearchProblem problem, ConvergentDiscoveryReport report) {
        Set<String> ids = new HashSet<>();
        ids.add(idForExpression(problem.rootExpression()));
        for (ConvergentPath path : report.pathsToTarget()) {
            for (String expression : path.expressions()) {
                ids.add(idForExpression(expression));
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

    private void expandBounded(SearchProblem problem, Map<String, SearchState> states, int maxDepth) {
        int cursor = 0;
        while (cursor < states.size() && states.size() < MAX_NODES) {
            SearchState current = new ArrayList<>(states.values()).get(cursor++);
            if (current.depth() >= maxDepth) {
                continue;
            }
            int generated = 0;
            for (Transformation transformation : problem.engine().transform(current.expression())) {
                if (generated >= problem.heuristic().maxCandidatesPerState() || states.size() >= MAX_NODES) {
                    break;
                }
                String nextExpression = transformation.transformedExpression();
                String nextId = idForExpression(nextExpression);
                if (nextExpression.equals(current.expression()) || states.containsKey(nextId)) {
                    continue;
                }
                ExpressionScore nextScore = problem.scorer().score(nextExpression);
                List<String> path = new ArrayList<>(current.path());
                path.add(nextExpression);
                List<String> rules = new ArrayList<>(current.appliedRuleIds());
                rules.add(transformation.rule());
                Set<String> applications = new LinkedHashSet<>(current.appliedRuleApplications());
                applications.add(transformation.applicationKey());
                List<RewriteKind> kinds = new ArrayList<>(current.appliedRuleKinds());
                kinds.add(transformation.kind());
                List<Boolean> flags = new ArrayList<>(current.equivalencePreservingFlags());
                flags.add(transformation.equivalencePreservingByConstruction());
                List<String> assumptions = new ArrayList<>(current.assumptions());
                assumptions.addAll(transformation.assumptions());
                states.put(nextId, new SearchState(
                    nextExpression,
                    current.depth() + 1,
                    nextScore,
                    path,
                    rules,
                    applications,
                    current.expandedStepCount() + (transformation.kind() == RewriteKind.EXPAND ? 1 : 0),
                    problem.canonicalizer().stableHash(nextExpression),
                    current.expression(),
                    transformation.rule(),
                    transformation.kind(),
                    transformation.mayIncreaseComplexity(),
                    transformation.estimatedCostDelta(),
                    transformation.equivalencePreservingByConstruction(),
                    current.score().weightedTotal() - nextScore.weightedTotal(),
                    kinds,
                    flags,
                    assumptions
                ));
                generated++;
            }
        }
    }

    private String idForExpression(String expression) {
        String normalized = (expression == null ? "" : expression).trim().replaceAll("\\s+", " ");
        return "space_" + sha256(normalized);
    }

    private String sha256(String expression) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(expression.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record EdgeDraft(String id, String fromId, String toId, String ruleId, RuleFamily family, int depth) {
    }
}
