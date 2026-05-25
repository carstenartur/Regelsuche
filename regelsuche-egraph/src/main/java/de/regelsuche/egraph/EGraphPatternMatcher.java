package de.regelsuche.egraph;

import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Egg-style "Searcher": find every way a {@link PatternExpr} matches
 * somewhere inside an {@link EGraph}.
 *
 * <p>Unlike AST pattern matching (which descends a single tree), e-graph
 * matching has to consider <em>every</em> e-node inside an e-class —
 * because an e-class is the set of expressions known to be equivalent,
 * and any of those representations may be the one the pattern fits.
 * The matcher therefore enumerates, for every e-class {@code c}, every
 * way the pattern's root can structurally line up with one of {@code c}'s
 * e-nodes.</p>
 *
 * <p>Placeholders bind to {@link EClassId}s (not to concrete AST sub-terms),
 * which is the entire point of equality saturation: rewrites operate on
 * equivalence classes, so two textually different inputs that happen to
 * have been unioned earlier are treated as the same binding.</p>
 */
public final class EGraphPatternMatcher {

    /**
     * One successful match: the e-class the pattern root anchored on,
     * plus the placeholder bindings — each placeholder maps to the
     * canonical id of the e-class it matched.
     */
    public record Match(EClassId root, Map<String, EClassId> bindings) {
        public Match {
            bindings = Map.copyOf(bindings);
        }
    }

    private final EGraph eGraph;

    public EGraphPatternMatcher(EGraph eGraph) {
        this.eGraph = eGraph;
    }

    /** Every {@link Match} of {@code pattern} anywhere in the graph. */
    public List<Match> matchAll(PatternExpr pattern) {
        List<Match> results = new ArrayList<>();
        // De-duplicate matches sharing the same root + bindings — multiple
        // e-nodes inside the same class can produce the same logical match.
        Set<String> seen = new LinkedHashSet<>();
        for (EClass eclass : eGraph.classes()) {
            EClassId rootId = eclass.id();
            for (Map<String, EClassId> bindings : matchInClass(pattern, rootId)) {
                String key = rootId + "|" + canonicalBindingsKey(bindings);
                if (seen.add(key)) {
                    results.add(new Match(rootId, bindings));
                }
            }
        }
        return results;
    }

    /**
     * All bindings under which {@code pattern}'s root matches the e-class
     * {@code targetClass}. Returns an empty list when the pattern does
     * not match.
     */
    public List<Map<String, EClassId>> matchInClass(PatternExpr pattern, EClassId targetClass) {
        EClassId canonical = eGraph.find(targetClass);
        return matchAgainst(pattern, canonical, Collections.emptyMap());
    }

    private List<Map<String, EClassId>> matchAgainst(
        PatternExpr pattern,
        EClassId targetClass,
        Map<String, EClassId> bindings
    ) {
        EClassId canonical = eGraph.find(targetClass);
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            EClassId existing = bindings.get(placeholder.name());
            if (existing != null) {
                if (!eGraph.find(existing).equals(canonical)) {
                    return List.of();
                }
                return List.of(bindings);
            }
            Map<String, EClassId> next = new LinkedHashMap<>(bindings);
            next.put(placeholder.name(), canonical);
            return List.of(next);
        }
        if (pattern instanceof PatternExpr.LiteralNumber literal) {
            String symbol = "num:" + formatNumber(literal.value());
            EClass eclass = classOrThrow(canonical);
            for (ENode node : eclass.nodes()) {
                if (node.isLeaf() && node.symbol().equals(symbol)) {
                    return List.of(bindings);
                }
            }
            return List.of();
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            String symbol = "op:" + operation.operator().name();
            return matchChildren(canonical, symbol, List.of(operation.left(), operation.right()), bindings);
        }
        if (pattern instanceof PatternExpr.Function function) {
            String symbol = "fn:" + function.name();
            return matchChildren(canonical, symbol, function.arguments(), bindings);
        }
        throw new IllegalArgumentException("Unsupported pattern: " + pattern.getClass());
    }

    private List<Map<String, EClassId>> matchChildren(
        EClassId targetClass,
        String expectedSymbol,
        List<PatternExpr> argumentPatterns,
        Map<String, EClassId> bindings
    ) {
        EClass eclass = classOrThrow(targetClass);
        List<Map<String, EClassId>> results = new ArrayList<>();
        for (ENode node : eclass.nodes()) {
            if (!node.symbol().equals(expectedSymbol) || node.children().size() != argumentPatterns.size()) {
                continue;
            }
            List<Map<String, EClassId>> currents = List.of(bindings);
            for (int i = 0; i < argumentPatterns.size(); i++) {
                List<Map<String, EClassId>> next = new ArrayList<>();
                EClassId childClass = eGraph.find(node.children().get(i));
                for (Map<String, EClassId> current : currents) {
                    next.addAll(matchAgainst(argumentPatterns.get(i), childClass, current));
                }
                currents = next;
                if (currents.isEmpty()) {
                    break;
                }
            }
            results.addAll(currents);
        }
        return results;
    }

    private EClass classOrThrow(EClassId id) {
        for (EClass eclass : eGraph.classes()) {
            if (eclass.id().equals(id)) {
                return eclass;
            }
        }
        throw new IllegalStateException("Unknown e-class " + id);
    }

    private String canonicalBindingsKey(Map<String, EClassId> bindings) {
        Map<String, EClassId> canonical = new HashMap<>();
        for (Map.Entry<String, EClassId> entry : bindings.entrySet()) {
            canonical.put(entry.getKey(), eGraph.find(entry.getValue()));
        }
        // Stable ordering for the key.
        StringBuilder builder = new StringBuilder();
        canonical.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';'));
        return builder.toString();
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
