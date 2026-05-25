package de.regelsuche.search.index;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Root-symbol implementation with extension points for richer feature-vector/discrimination-tree indexes. */
public class RootSymbolTermRuleIndex implements TermRuleIndex, RuleCandidateIndex {
    private final Map<String, List<RewriteRule>> atomicByRoot = new LinkedHashMap<>();
    private final Map<String, List<MacroEntry>> macroByRoot = new LinkedHashMap<>();
    private final boolean multiStageFiltersEnabled;

    public RootSymbolTermRuleIndex() {
        this(true);
    }

    public RootSymbolTermRuleIndex(boolean multiStageFiltersEnabled) {
        this.multiStageFiltersEnabled = multiStageFiltersEnabled;
    }

    @Override
    public void addAtomicRule(String rootSymbol, RewriteRule rule) {
        atomicByRoot.computeIfAbsent(normalizeRoot(rootSymbol), ignored -> new ArrayList<>()).add(rule);
    }

    @Override
    public void addMacroMove(IndexedMacroMove rule) {
        macroByRoot.computeIfAbsent(rootSymbol(rule.leftPattern()), ignored -> new ArrayList<>()).add(MacroEntry.from(rule));
    }

    @Override
    public QueryResult query(String expression, Query query) {
        CandidateSet candidates = candidateSetForExpression(
            expression,
            SearchContext.from(query),
            CandidateBudget.unbounded()
        );
        return new QueryResult(candidates.atomicRules(), candidates.macroMoves(), candidates.metrics().asTermRuleMetrics());
    }

    @Override
    public CandidateSet candidatesFor(Expr subtree, SearchContext context, CandidateBudget budget) {
        return candidateSetForExpression(
            subtree == null ? "" : ExpressionFormatter.format(subtree),
            context == null ? SearchContext.all() : context,
            budget == null ? CandidateBudget.unbounded() : budget
        );
    }

    public CandidateSet candidateSetForExpression(String expression, SearchContext context, CandidateBudget budget) {
        SearchContext effectiveQuery = context == null ? SearchContext.all() : context;
        CandidateBudget effectiveBudget = budget == null ? CandidateBudget.unbounded() : budget;
        String root = rootSymbol(expression);
        OperatorSignature querySignature = OperatorSignature.parse(expression);
        ExpressionFeatureVector queryFeatures = ExpressionFeatureVector.parse(expression);
        DiscriminationTreeKey queryDiscriminationKey = DiscriminationTreeKey.parse(expression);
        List<RewriteRule> atomicCandidates = effectiveQuery.includeAtomicRules()
            ? atomicByRoot.getOrDefault(root, List.of())
            : List.of();
        List<MacroEntry> macroCandidates = effectiveQuery.includeMacroMoves()
            ? macroByRoot.getOrDefault(root, List.of())
            : List.of();
        int totalAtomic = atomicByRoot.values().stream().mapToInt(List::size).sum();
        int totalMacros = macroByRoot.values().stream().mapToInt(List::size).sum();
        int considered = totalAtomic
            + macroByRoot.values().stream().mapToInt(List::size).sum();
        int skippedByRoot = Math.max(0, totalAtomic - atomicCandidates.size())
            + Math.max(0, totalMacros - macroCandidates.size());
        List<RewriteRule> budgetedAtomic = atomicCandidates.stream()
            .limit(effectiveBudget.maxAtomicRules())
            .toList();
        int skippedByBudget = Math.max(0, atomicCandidates.size() - budgetedAtomic.size());
        List<IndexedMacroMove> macros = new ArrayList<>();
        int skippedByGoal = 0;
        int skippedBySignature = 0;
        int skippedByFeature = 0;
        int skippedByDiscrimination = 0;
        for (MacroEntry entry : macroCandidates) {
            IndexedMacroMove rule = entry.rule();
            if (rule.proofStatus().ordinal() < effectiveQuery.minimumProofStatus().ordinal()
                    || (!effectiveQuery.domain().isBlank() && !domainMatches(rule, effectiveQuery.domain()))
                    || (!effectiveQuery.goalExpression().isBlank() && !goalAware(rule, effectiveQuery.goalExpression()))) {
                skippedByGoal++;
                continue;
            }
            if (multiStageFiltersEnabled && !entry.signature().compatibleWith(querySignature)) {
                skippedBySignature++;
                continue;
            }
            if (multiStageFiltersEnabled && !entry.features().canMatch(queryFeatures)) {
                skippedByFeature++;
                continue;
            }
            if (multiStageFiltersEnabled && !entry.discriminationKey().compatibleWith(queryDiscriminationKey)) {
                skippedByDiscrimination++;
                continue;
            }
            macros.add(rule);
        }
        List<IndexedMacroMove> budgetedMacros = macros.stream()
            .sorted(goalRanking(effectiveQuery.goalExpression()))
            .limit(effectiveBudget.maxMacroMoves())
            .toList();
        skippedByBudget += Math.max(0, macros.size() - budgetedMacros.size());
        int matched = budgetedAtomic.size() + budgetedMacros.size();
        return new CandidateSet(
            budgetedAtomic,
            budgetedMacros,
            new IndexMetrics(
                considered,
                skippedByRoot,
                skippedBySignature,
                skippedByFeature,
                skippedByDiscrimination,
                skippedByGoal,
                skippedByBudget,
                matched,
                matched
            )
        );
    }

    /** Extension point: feature-vector indexes can override the coarse root symbol with richer signatures. */
    protected String featureVector(String expression) {
        return rootSymbol(expression);
    }

    /** Extension point: discrimination-tree implementations can route by the normalized pattern tree. */
    protected String discriminationTreeKey(String expression) {
        return DiscriminationTreeKey.parse(expression).requiredPaths().toString();
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

    private static boolean domainMatches(IndexedMacroMove rule, String domain) {
        String haystack = (rule.domain() + " " + rule.id() + " " + rule.leftPattern() + " " + rule.rightPattern())
            .toLowerCase(Locale.ROOT);
        return haystack.contains(domain.toLowerCase(Locale.ROOT));
    }

    private static boolean goalAware(IndexedMacroMove rule, String goalExpression) {
        String root = rootSymbol(goalExpression);
        return root.isBlank() || root.equals(rootSymbol(rule.rightPattern())) || goalExpression.contains(rule.rightPattern());
    }

    private static Comparator<IndexedMacroMove> goalRanking(String goalExpression) {
        String goalRoot = rootSymbol(goalExpression);
        return Comparator
            .comparing((IndexedMacroMove rule) -> !goalRoot.isBlank() && goalRoot.equals(rootSymbol(rule.rightPattern())) ? 0 : 1)
            .thenComparing(IndexedMacroMove::id);
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

    private record MacroEntry(
        IndexedMacroMove rule,
        OperatorSignature signature,
        ExpressionFeatureVector features,
        DiscriminationTreeKey discriminationKey
    ) {
        private static MacroEntry from(IndexedMacroMove rule) {
            String left = rule.leftPattern();
            Expr parsed = parse(left);
            if (parsed != null) {
                return new MacroEntry(
                    rule,
                    OperatorSignature.of(parsed),
                    ExpressionFeatureVector.of(parsed),
                    DiscriminationTreeKey.of(parsed)
                );
            }
            return new MacroEntry(
                rule,
                OperatorSignature.parse(left),
                ExpressionFeatureVector.parse(left),
                DiscriminationTreeKey.parse(left)
            );
        }

        private static Expr parse(String expression) {
            try {
                return new ExpressionParser().parseTerm(expression == null ? "" : expression);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
