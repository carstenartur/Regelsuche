package de.regelsuche.egraph;

import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.Collection;
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

    private record MatchCacheKey(String patternId, EClassId root, long version) {
    }

    public record MatcherStats(
        long classesScanned,
        long nodesScanned,
        long candidateClassesSkipped,
        long matchesFound,
        long matcherCacheHits,
        long matcherCacheMisses
    ) {
    }

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
    private final Map<MatchCacheKey, List<Map<String, EClassId>>> matchCache = new HashMap<>();
    private long cacheVersion = -1L;
    private long classesScanned = 0L;
    private long nodesScanned = 0L;
    private long candidateClassesSkipped = 0L;
    private long matchesFound = 0L;
    private long matcherCacheHits = 0L;
    private long matcherCacheMisses = 0L;

    public EGraphPatternMatcher(EGraph eGraph) {
        this.eGraph = eGraph;
    }

    /** Every {@link Match} of {@code pattern} anywhere in the graph. */
    public List<Match> matchAll(PatternExpr pattern) {
        return matchAll(pattern.toString(), pattern, null);
    }

    /** Every {@link Match} of {@code pattern} on the given candidate roots. */
    public List<Match> matchAll(String patternId, PatternExpr pattern, Collection<EClassId> candidates) {
        clearCacheIfVersionChanged();
        return collectMatches(patternId, pattern, selectCandidateRoots(pattern, candidates));
    }

    /**
     * Control path for benchmarks/regression tests: execute the same logical
     * matcher without root-signature candidate selection.
     */
    public List<Match> matchAllFullScan(String patternId, PatternExpr pattern) {
        clearCacheIfVersionChanged();
        return collectMatches(patternId, pattern, allRoots());
    }

    private List<Match> collectMatches(String patternId, PatternExpr pattern, Collection<EClassId> roots) {
        List<Match> results = new ArrayList<>();
        // De-duplicate matches sharing the same root + bindings — multiple
        // e-nodes inside the same class can produce the same logical match.
        Set<String> seen = new LinkedHashSet<>();
        classesScanned += roots.size();
        for (EClassId rootId : roots) {
            for (Map<String, EClassId> bindings : matchInClassMemoized(patternId, pattern, rootId)) {
                String key = rootId + "|" + canonicalBindingsKey(bindings);
                if (seen.add(key)) {
                    results.add(new Match(rootId, bindings));
                    matchesFound++;
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
            EClass eclass = eGraph.classOrThrow(canonical);
            for (ENode node : eclass.nodes()) {
                nodesScanned++;
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
        EClass eclass = eGraph.classOrThrow(targetClass);
        List<Map<String, EClassId>> results = new ArrayList<>();
        for (ENode node : eclass.nodes()) {
            nodesScanned++;
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

    private List<Map<String, EClassId>> matchInClassMemoized(String patternId, PatternExpr pattern, EClassId rootId) {
        MatchCacheKey key = new MatchCacheKey(patternId, eGraph.find(rootId), eGraph.version());
        List<Map<String, EClassId>> hit = matchCache.get(key);
        if (hit != null) {
            matcherCacheHits++;
            return hit;
        }
        matcherCacheMisses++;
        List<Map<String, EClassId>> computed = List.copyOf(matchInClass(pattern, rootId));
        matchCache.put(key, computed);
        return computed;
    }

    private Collection<EClassId> selectCandidateRoots(PatternExpr pattern, Collection<EClassId> candidates) {
        Collection<EClassId> signatureCandidates = candidatesForPatternRoot(pattern);
        if ((candidates == null || candidates.isEmpty()) && signatureCandidates != null) {
            List<EClassId> roots = canonicalize(signatureCandidates);
            candidateClassesSkipped += Math.max(0, eGraph.classCount() - roots.size());
            return roots;
        }
        Collection<EClassId> base;
        if (candidates == null || candidates.isEmpty()) {
            List<EClassId> all = new ArrayList<>();
            for (EClass eClass : eGraph.classes()) {
                all.add(eClass.id());
            }
            base = all;
        } else {
            base = candidates;
        }
        if (signatureCandidates == null) {
            return canonicalize(base);
        }
        Set<EClassId> bySignature = new LinkedHashSet<>(canonicalize(signatureCandidates));
        List<EClassId> filtered = new ArrayList<>();
        for (EClassId root : canonicalize(base)) {
            if (bySignature.contains(root)) {
                filtered.add(root);
            } else {
                candidateClassesSkipped++;
            }
        }
        return filtered;
    }

    private List<EClassId> allRoots() {
        List<EClassId> all = new ArrayList<>();
        for (EClass eClass : eGraph.classes()) {
            all.add(eClass.id());
        }
        return canonicalize(all);
    }

    private Collection<EClassId> candidatesForPatternRoot(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Placeholder) {
            return null;
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            return eGraph.classesWith(new ENodeSignature("op:" + operation.operator().name(), 2));
        }
        if (pattern instanceof PatternExpr.Function function) {
            return eGraph.classesWith(new ENodeSignature("fn:" + function.name(), function.arguments().size()));
        }
        if (pattern instanceof PatternExpr.LiteralNumber literal) {
            String symbol = "num:" + formatNumber(literal.value());
            Collection<EClassId> exact = eGraph.classesWith(new ENodeSignature(symbol, 0));
            if (!exact.isEmpty()) {
                return exact;
            }
            return eGraph.classesWithSymbolPrefix("num:", 0);
        }
        return null;
    }

    private List<EClassId> canonicalize(Collection<EClassId> classes) {
        LinkedHashSet<EClassId> canonical = new LinkedHashSet<>();
        for (EClassId id : classes) {
            canonical.add(eGraph.find(id));
        }
        List<EClassId> sorted = new ArrayList<>(canonical);
        Collections.sort(sorted);
        return sorted;
    }

    public MatcherStats stats() {
        return new MatcherStats(
            classesScanned,
            nodesScanned,
            candidateClassesSkipped,
            matchesFound,
            matcherCacheHits,
            matcherCacheMisses
        );
    }

    public void resetStats() {
        classesScanned = 0L;
        nodesScanned = 0L;
        candidateClassesSkipped = 0L;
        matchesFound = 0L;
        matcherCacheHits = 0L;
        matcherCacheMisses = 0L;
    }

    private void clearCacheIfVersionChanged() {
        long currentVersion = eGraph.version();
        if (cacheVersion != currentVersion) {
            matchCache.clear();
            cacheVersion = currentVersion;
        }
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
