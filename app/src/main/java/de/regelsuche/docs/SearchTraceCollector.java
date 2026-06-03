package de.regelsuche.docs;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.search.strategy.SearchState;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SearchTraceCollector {
    private final ExpressionCanonicalizer expressionCanonicalizer = new ExpressionCanonicalizer();
    private final Set<String> operatorRuleIds;
    private final DiscoveryOperatorRegistry operatorRegistry;

    SearchTraceCollector() {
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry().register(new DefaultDiscoveryOperatorProvider());
        this.operatorRegistry = registry;
        this.operatorRuleIds = registry.operatorRuleIds();
    }

    SearchTraceCollector(Set<String> operatorRuleIds) {
        this.operatorRuleIds = operatorRuleIds == null ? Set.of() : Set.copyOf(operatorRuleIds);
        this.operatorRegistry = null;
    }

    SearchTraceCollector(DiscoveryOperatorRegistry registry) {
        this.operatorRegistry = registry;
        this.operatorRuleIds = registry == null ? Set.of() : registry.operatorRuleIds();
    }

    TraceGraph collect(
            DiscoveryBenchmarkScenario scenario,
            SearchRunTrace withoutMacro,
            SearchRunTrace withMacro,
            List<String> learnedMacros,
            List<String> bridgeRules,
            Map<String, ScenarioRule> rulesById) {
        return collect(scenario, withoutMacro, withMacro, learnedMacros, bridgeRules, rulesById, Map.of(), Set.of());
    }

    TraceGraph collect(
            DiscoveryBenchmarkScenario scenario,
            SearchRunTrace withoutMacro,
            SearchRunTrace withMacro,
            List<String> learnedMacros,
            List<String> bridgeRules,
            Map<String, ScenarioRule> rulesById,
            Map<String, String> ruleIdToPackId,
            Set<String> enabledOperators) {
        SearchRunTrace primary = withoutMacro.success() ? withoutMacro : withMacro;
        Set<String> selectedNodeIds = toNodeIds(primary.selectedPath());
        Set<String> selectedEdgeIds = toEdgeIds(primary.selectedPath(), primary.selectedRuleIds());
        String inputId = canonical(scenario.inputExpression());
        String normalizedTarget = normalizeExpression(scenario.targetExpression());

        LinkedHashMap<String, MutableNode> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, MutableEdge> edges = new LinkedHashMap<>();
        for (SearchRunTrace run : List.of(withoutMacro, withMacro)) {
            for (SearchState state : run.exploredStates()) {
                upsertNode(nodes, state.expression(), state.depth());
                if (state.parentExpression() == null || state.appliedRuleId() == null || state.appliedRuleId().isBlank()) {
                    continue;
                }
                String fromId = canonical(state.parentExpression());
                String toId = canonical(state.expression());
                String edgeKey = edgeKey(fromId, toId, state.appliedRuleId());
                MutableEdge edge = edges.computeIfAbsent(
                        edgeKey,
                        ignored -> new MutableEdge(
                                fromId,
                                toId,
                                state.appliedRuleId(),
                                kindFor(state.appliedRuleId(), learnedMacros, bridgeRules),
                                sourceFor(state.appliedRuleId(), learnedMacros, rulesById, ruleIdToPackId),
                                packIdFor(state.appliedRuleId(), rulesById, ruleIdToPackId),
                                operatorIdFor(state.appliedRuleId()),
                                maturityFor(state.appliedRuleId(), learnedMacros, rulesById),
                                enabledByProfileFor(state.appliedRuleId(), enabledOperators),
                                inferredEffects(state.appliedRuleId(), rulesById)));
                if (selectedEdgeIds.contains(edgeKey)) {
                    edge.tags.add("selected-path");
                } else {
                    edge.tags.add("alternative-branch");
                }
                if ("macro".equals(edge.kind)) {
                    edge.tags.add("macro-shortcut");
                }
                upsertNode(nodes, state.parentExpression(), Math.max(0, state.depth() - 1));
            }
        }

        for (MutableNode node : nodes.values()) {
            if (node.id.equals(inputId)) {
                node.kind = "input";
                node.tags.add("input");
            } else if (normalizeExpression(node.label).equals(normalizedTarget)) {
                node.kind = "target";
                node.tags.add("target");
            }
            if (selectedNodeIds.contains(node.id)) {
                node.tags.add("selected-path");
            } else {
                node.tags.add("alternative-branch");
            }
        }

        Set<String> hasOutgoing = new LinkedHashSet<>();
        for (MutableEdge edge : edges.values()) {
            hasOutgoing.add(edge.from);
        }
        for (MutableNode node : nodes.values()) {
            if (!"target".equals(node.kind) && !hasOutgoing.contains(node.id)) {
                node.tags.add("dead-end");
            }
        }

        List<DiscoveryBenchmarkEvidence.EvidenceNode> evidenceNodes = nodes.values().stream()
                .sorted(Comparator.comparingInt((MutableNode node) -> node.depth)
                        .thenComparing(node -> node.label)
                        .thenComparing(node -> node.id))
                .map(node -> new DiscoveryBenchmarkEvidence.EvidenceNode(
                        node.id,
                        node.label,
                        node.kind,
                        node.depth,
                        sortedStrings(node.tags)))
                .toList();
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> evidenceEdges = edges.values().stream()
                .sorted(Comparator.comparing((MutableEdge edge) -> edge.from)
                        .thenComparing(edge -> edge.to)
                        .thenComparing(edge -> edge.ruleId)
                        .thenComparing(edge -> edge.kind)
                        .thenComparing(edge -> edge.source)
                        .thenComparing(edge -> edge.packId)
                        .thenComparing(edge -> searchEffectsKey(edge.searchEffects))
                        .thenComparing(edge -> tagsKey(edge.tags)))
                .map(edge -> new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        edge.from,
                        edge.to,
                        edge.ruleId,
                        edge.kind,
                        edge.source,
                        edge.packId,
                        edge.operatorId,
                        edge.maturity,
                        edge.enabledByProfile,
                        sortedSearchEffects(edge.searchEffects),
                        sortedStrings(edge.tags)))
                .toList();
        return new TraceGraph(evidenceNodes, evidenceEdges);
    }

    private void upsertNode(Map<String, MutableNode> nodes, String expression, int depth) {
        String id = canonical(expression);
        MutableNode node = nodes.computeIfAbsent(id, ignored -> new MutableNode(id, expression, "state", depth));
        if (node.label == null || node.label.isBlank()) {
            node.label = expression;
        }
        node.depth = Math.min(node.depth, depth);
    }

    private Set<String> toNodeIds(List<String> path) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String expression : path) {
            ids.add(canonical(expression));
        }
        return ids;
    }

    private Set<String> toEdgeIds(List<String> path, List<String> appliedRuleIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        int edgeCount = Math.min(appliedRuleIds.size(), Math.max(0, path.size() - 1));
        for (int i = 0; i < edgeCount; i++) {
            ids.add(edgeKey(canonical(path.get(i)), canonical(path.get(i + 1)), appliedRuleIds.get(i)));
        }
        return ids;
    }

    private String edgeKey(String from, String to, String ruleId) {
        return from + "->" + to + "|" + ruleId;
    }

    private String kindFor(String ruleId, List<String> learnedMacros, List<String> bridgeRules) {
        if (learnedMacros.contains(ruleId)) {
            return "macro";
        }
        if (bridgeRules.contains(ruleId)) {
            return "bridge";
        }
        return "rule";
    }

    private String sourceFor(String ruleId, List<String> learnedMacros, Map<String, ScenarioRule> rulesById, Map<String, String> ruleIdToPackId) {
        if (learnedMacros.contains(ruleId)) {
            return "macro";
        }
        if (ruleId != null && (ruleId.startsWith("hypothesis_sympy_")
                || "hypothesis_common_subexpression_discovery".equals(ruleId))) {
            return "sympy-derived";
        }
        if (operatorRuleIds.contains(ruleId)) {
            return "operator";
        }
        String packId = ruleIdToPackId.get(ruleId);
        if (packId != null) {
            return packId.toLowerCase(Locale.ROOT).contains("sympy") ? "sympy-derived" : "scenario";
        }
        return rulesById.containsKey(ruleId) ? "scenario-generic" : "core";
    }

    private String packIdFor(String ruleId, Map<String, ScenarioRule> rulesById, Map<String, String> ruleIdToPackId) {
        if ("hypothesis_sympy_factor_candidate".equals(ruleId)
                || "hypothesis_common_subexpression_discovery".equals(ruleId)) {
            return "sympy-polynomial-basic";
        }
        if ("hypothesis_sympy_rational_discovery".equals(ruleId)) {
            return "sympy-rational-basic";
        }
        if (operatorRuleIds.contains(ruleId)) {
            return "operator-derived";
        }
        String packId = ruleIdToPackId.get(ruleId);
        return packId != null ? packId : (rulesById.containsKey(ruleId) ? "scenario-generic" : "core");
    }

    private String operatorIdFor(String ruleId) {
        if (operatorRegistry != null) {
            return operatorRegistry.operatorIdForRule(ruleId);
        }
        return "";
    }

    private String maturityFor(String ruleId, List<String> learnedMacros, Map<String, ScenarioRule> rulesById) {
        if (learnedMacros.contains(ruleId)) {
            return "MACRO";
        }
        if (operatorRuleIds.contains(ruleId)) {
            return "OPERATOR_DERIVED";
        }
        ScenarioRule rule = rulesById.get(ruleId);
        return rule != null ? rule.status().name() : "CORE";
    }

    private boolean enabledByProfileFor(String ruleId, Set<String> enabledOperators) {
        if (!operatorRuleIds.contains(ruleId)) {
            return false;
        }
        if (operatorRegistry == null || enabledOperators.isEmpty()) {
            return false;
        }
        String operatorId = operatorRegistry.operatorIdForRule(ruleId);
        return !operatorId.isEmpty() && enabledOperators.contains(operatorId);
    }

    private List<SearchEffect> inferredEffects(String ruleId, Map<String, ScenarioRule> rulesById) {
        LinkedHashSet<SearchEffect> effects = new LinkedHashSet<>();
        ScenarioRule rule = rulesById.get(ruleId);
        if (rule != null) {
            effects.addAll(rule.effects());
        }
        String lower = ruleId.toLowerCase(Locale.ROOT);
        if (operatorRuleIds.contains(ruleId) || lower.contains("bridge")) {
            effects.add(SearchEffect.BRIDGING);
        }
        if (lower.contains("factor")) {
            effects.add(SearchEffect.FACTORIZING);
        }
        if (lower.contains("simplify")) {
            effects.add(SearchEffect.SIMPLIFYING);
        }
        if (lower.contains("normalize") || lower.contains("macro")) {
            effects.add(SearchEffect.NORMALIZING);
        }
        if (lower.contains("expand")) {
            effects.add(SearchEffect.EXPANDING);
        }
        if (effects.isEmpty()) {
            effects.add(SearchEffect.NORMALIZING);
        }
        return List.copyOf(effects);
    }

    private String canonical(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }

    private String normalizeExpression(String expression) {
        if (expression == null) {
            return "";
        }
        try {
            return expressionCanonicalizer.canonicalize(expression);
        } catch (IllegalArgumentException exception) {
            return expression.trim().replaceAll("\\s+", " ");
        }
    }

    private List<SearchEffect> sortedSearchEffects(List<SearchEffect> effects) {
        return effects == null ? List.of() : effects.stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();
    }

    private List<String> sortedStrings(Set<String> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private String searchEffectsKey(List<SearchEffect> effects) {
        return sortedSearchEffects(effects).stream().map(Enum::name).collect(java.util.stream.Collectors.joining("|"));
    }

    private String tagsKey(Set<String> tags) {
        return String.join("|", sortedStrings(tags));
    }

    record SearchRunTrace(boolean success, List<SearchState> exploredStates, List<String> selectedPath, List<String> selectedRuleIds) {
        SearchRunTrace {
            exploredStates = exploredStates == null ? List.of() : List.copyOf(exploredStates);
            selectedPath = selectedPath == null ? List.of() : List.copyOf(selectedPath);
            selectedRuleIds = selectedRuleIds == null ? List.of() : List.copyOf(selectedRuleIds);
        }
    }

    record TraceGraph(List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes, List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges) {
        TraceGraph {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    private static final class MutableNode {
        private final String id;
        private String label;
        private String kind;
        private int depth;
        private final LinkedHashSet<String> tags = new LinkedHashSet<>();

        private MutableNode(String id, String label, String kind, int depth) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.depth = depth;
        }
    }

    private static final class MutableEdge {
        private final String from;
        private final String to;
        private final String ruleId;
        private final String kind;
        private final String source;
        private final String packId;
        private final String operatorId;
        private final String maturity;
        private final boolean enabledByProfile;
        private final List<SearchEffect> searchEffects;
        private final LinkedHashSet<String> tags = new LinkedHashSet<>();

        private MutableEdge(
                String from,
                String to,
                String ruleId,
                String kind,
                String source,
                String packId,
                String operatorId,
                String maturity,
                boolean enabledByProfile,
                List<SearchEffect> searchEffects) {
            this.from = from;
            this.to = to;
            this.ruleId = ruleId;
            this.kind = kind;
            this.source = source;
            this.packId = packId;
            this.operatorId = operatorId == null ? "" : operatorId;
            this.maturity = maturity == null ? "" : maturity;
            this.enabledByProfile = enabledByProfile;
            this.searchEffects = searchEffects == null ? List.of()
                    : searchEffects.stream().sorted(Comparator.comparing(Enum::name)).toList();
        }
    }
}
