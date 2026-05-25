package de.regelsuche.index;

import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Root-symbol implementation with extension points for richer feature-vector/discrimination-tree indexes. */
public final class RootSymbolTermRuleIndex implements TermRuleIndex {
    private final Map<String, List<RewriteRule>> atomicByRoot = new LinkedHashMap<>();
    private final Map<String, List<ReusableRule>> macroByRoot = new LinkedHashMap<>();

    @Override
    public void addAtomicRule(String rootSymbol, RewriteRule rule) {
        atomicByRoot.computeIfAbsent(normalizeRoot(rootSymbol), ignored -> new ArrayList<>()).add(rule);
    }

    @Override
    public void addMacroMove(ReusableRule rule) {
        macroByRoot.computeIfAbsent(rootSymbol(rule.leftPattern()), ignored -> new ArrayList<>()).add(rule);
    }

    @Override
    public QueryResult query(String expression, Query query) {
        Query effectiveQuery = query == null ? Query.all() : query;
        String root = rootSymbol(expression);
        List<RewriteRule> atomicCandidates = effectiveQuery.includeAtomicRules()
            ? atomicByRoot.getOrDefault(root, List.of())
            : List.of();
        List<ReusableRule> macroCandidates = effectiveQuery.includeMacroMoves()
            ? macroByRoot.getOrDefault(root, List.of())
            : List.of();
        int considered = atomicByRoot.values().stream().mapToInt(List::size).sum()
            + macroByRoot.values().stream().mapToInt(List::size).sum();
        List<ReusableRule> macros = macroCandidates.stream()
            .filter(rule -> rule.proofStatus().ordinal() >= effectiveQuery.minimumProofStatus().ordinal())
            .filter(rule -> effectiveQuery.domain().isBlank() || domainMatches(rule, effectiveQuery.domain()))
            .filter(rule -> effectiveQuery.goalExpression().isBlank() || goalAware(rule, effectiveQuery.goalExpression()))
            .toList();
        int matched = atomicCandidates.size() + macros.size();
        return new QueryResult(
            atomicCandidates,
            macros,
            new Metrics(considered, Math.max(0, considered - matched), matched)
        );
    }

    /** Extension point: feature-vector indexes can override the coarse root symbol with richer signatures. */
    protected String featureVector(String expression) {
        return rootSymbol(expression);
    }

    /** Extension point: discrimination-tree implementations can route by the normalized pattern tree. */
    protected String discriminationTreeKey(String expression) {
        return rootSymbol(expression);
    }

    static String rootSymbol(String expression) {
        if (expression == null || expression.isBlank()) {
            return "";
        }
        String trimmed = stripOuterParentheses(expression.trim());
        int depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && "+-".indexOf(ch) >= 0 && i > 0) return String.valueOf(ch);
        }
        depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && "*/".indexOf(ch) >= 0) return String.valueOf(ch);
        }
        depth = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && ch == '^') return "^";
        }
        int open = trimmed.indexOf('(');
        if (open > 0) return normalizeRoot(trimmed.substring(0, open));
        return normalizeRoot(trimmed.replaceAll("[^A-Za-z0-9_].*$", ""));
    }

    private static boolean domainMatches(ReusableRule rule, String domain) {
        String haystack = (rule.id() + " " + rule.leftPattern() + " " + rule.rightPattern()).toLowerCase(Locale.ROOT);
        return haystack.contains(domain.toLowerCase(Locale.ROOT));
    }

    private static boolean goalAware(ReusableRule rule, String goalExpression) {
        String root = rootSymbol(goalExpression);
        return root.isBlank() || root.equals(rootSymbol(rule.rightPattern())) || goalExpression.contains(rule.rightPattern());
    }

    private static String normalizeRoot(String root) {
        return root == null ? "" : root.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripOuterParentheses(String value) {
        while (value.startsWith("(") && value.endsWith(")") && enclosesWholeExpression(value)) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static boolean enclosesWholeExpression(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (depth == 0 && i < value.length() - 1) {
                return false;
            }
        }
        return depth == 0;
    }
}
