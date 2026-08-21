package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Produces an explainable, bounded analysis for one expression pattern.
 *
 * <p>The existing matcher APIs remain unchanged. This adapter first delegates
 * complete matching to {@link ExprMatcher}, preserving its exact,
 * equivalence-aware and bounded-representative semantics. Only after a complete
 * and conclusive non-match does it inspect the deterministic structural
 * skeleton and retain residual obligations for a later preparation solver.</p>
 *
 * <p>Residual analysis never invents an expression and performs no search. It
 * records the already matched structure, partial placeholder bindings and the
 * exact AST locations that still need to satisfy a pattern fragment.</p>
 */
public final class PatternMatchAnalyzer {

    public Analysis analyze(
        PatternExpr pattern,
        Expr expression,
        RecognitionProfile recognitionProfile
    ) {
        return analyze(
            pattern,
            expression,
            recognitionProfile,
            ExprMatcher.MatchOptions.defaults());
    }

    public Analysis analyze(
        PatternExpr pattern,
        Expr expression,
        RecognitionProfile recognitionProfile,
        ExprMatcher.MatchOptions options
    ) {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(expression, "expression");
        RecognitionProfile profile = recognitionProfile == null
            ? RecognitionProfile.exact()
            : recognitionProfile;
        Objects.requireNonNull(options, "options");

        ExprMatcher.MatchOutcome outcome =
            ExprMatcher.pattern(pattern, profile).match(expression, options);
        if (outcome.matched()) {
            return fullMatch(pattern, outcome);
        }
        if (!outcome.complete()) {
            return inconclusive(pattern, outcome);
        }
        return partialMatch(pattern, expression, outcome);
    }

    private static Analysis fullMatch(
        PatternExpr pattern,
        ExprMatcher.MatchOutcome outcome
    ) {
        ExprMatcher.MatchResult preferred = preferred(outcome.matches());
        boolean exact = outcome.matches().stream().anyMatch(result ->
            result.recognitionStrength()
                == ExprMatcher.RecognitionStrength.EXACT);
        return new Analysis(
            exact ? Status.EXACT_MATCH : Status.MATCH_MODULO_THEORY,
            outcome.matches(),
            preferred.bindings(),
            List.of(),
            outcome.diagnostics(),
            outcome.evaluatedSteps(),
            outcome.patternBranches(),
            0,
            0,
            patternNodeCount(pattern),
            exact
                ? "EXACT_PATTERN_MATCH"
                : "EQUIVALENCE_AWARE_PATTERN_MATCH");
    }

    private static Analysis inconclusive(
        PatternExpr pattern,
        ExprMatcher.MatchOutcome outcome
    ) {
        return new Analysis(
            Status.INCONCLUSIVE,
            List.of(),
            Map.of(),
            List.of(),
            outcome.diagnostics(),
            outcome.evaluatedSteps(),
            outcome.patternBranches(),
            0,
            0,
            patternNodeCount(pattern),
            "MATCH_BUDGET_INCONCLUSIVE");
    }

    private static Analysis partialMatch(
        PatternExpr pattern,
        Expr expression,
        ExprMatcher.MatchOutcome outcome
    ) {
        PartialState partial = new PartialState();
        compare(pattern, expression, List.of(), partial);
        int totalNodes = patternNodeCount(pattern);
        boolean unrelated = partial.obligations.isEmpty()
            || (partial.matchedPatternNodes == 0
                && partial.bindings.isEmpty());
        if (unrelated) {
            return new Analysis(
                Status.NOT_MATCHED,
                List.of(),
                Map.of(),
                List.of(),
                outcome.diagnostics(),
                outcome.evaluatedSteps(),
                outcome.patternBranches(),
                partial.structuralComparisons,
                partial.matchedPatternNodes,
                totalNodes,
                "CONCLUSIVE_PATTERN_NON_MATCH");
        }

        List<ResidualObligation> obligations = partial.obligations.stream()
            .map(obligation -> obligation.finish(partial.bindings.keySet()))
            .toList();
        return new Analysis(
            Status.RESIDUAL,
            List.of(),
            partial.bindings,
            obligations,
            outcome.diagnostics(),
            outcome.evaluatedSteps(),
            outcome.patternBranches(),
            partial.structuralComparisons,
            partial.matchedPatternNodes,
            totalNodes,
            "STRUCTURALLY_CLOSE_WITH_RESIDUAL_OBLIGATIONS");
    }

    private static ExprMatcher.MatchResult preferred(
        List<ExprMatcher.MatchResult> matches
    ) {
        return matches.stream()
            .min(Comparator
                .comparingInt((ExprMatcher.MatchResult result) ->
                    result.recognitionStrength().ordinal())
                .thenComparingInt(ExprMatcher.MatchResult::representativeIndex))
            .orElseThrow();
    }

    private static void compare(
        PatternExpr pattern,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        state.structuralComparisons++;
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            comparePlaceholder(placeholder, expression, path, state);
            return;
        }
        if (pattern instanceof PatternExpr.LiteralNumber literal) {
            compareLiteralNumber(literal, expression, path, state);
            return;
        }
        if (pattern instanceof PatternExpr.LiteralVariable literal) {
            compareLiteralVariable(literal, expression, path, state);
            return;
        }
        if (pattern instanceof PatternExpr.Operation operation) {
            compareOperation(operation, expression, path, state);
            return;
        }
        compareFunction(
            (PatternExpr.Function) pattern,
            expression,
            path,
            state);
    }

    private static void comparePlaceholder(
        PatternExpr.Placeholder placeholder,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        Expr previous = state.bindings.get(placeholder.name());
        if (previous == null) {
            state.bindings.put(placeholder.name(), expression);
            state.matchedPatternNodes++;
            return;
        }
        if (previous.equals(expression)) {
            state.matchedPatternNodes++;
            return;
        }
        mismatch(
            ResidualKind.BINDING_CONFLICT,
            path,
            placeholder,
            expression,
            state);
    }

    private static void compareLiteralNumber(
        PatternExpr.LiteralNumber literal,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        boolean matched = expression instanceof NumberExpr number
            && Double.compare(number.value(), literal.value()) == 0;
        retainLiteralResult(literal, expression, path, state, matched);
    }

    private static void compareLiteralVariable(
        PatternExpr.LiteralVariable literal,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        boolean matched = expression instanceof VariableExpr variable
            && variable.name().equals(literal.name());
        retainLiteralResult(literal, expression, path, state, matched);
    }

    private static void retainLiteralResult(
        PatternExpr literal,
        Expr expression,
        List<Integer> path,
        PartialState state,
        boolean matched
    ) {
        if (matched) {
            state.matchedPatternNodes++;
            return;
        }
        mismatch(
            ResidualKind.LITERAL_MISMATCH,
            path,
            literal,
            expression,
            state);
    }

    private static void compareOperation(
        PatternExpr.Operation operation,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        if (!(expression instanceof BinaryExpr binary)
                || binary.operator() != operation.operator()) {
            mismatch(
                ResidualKind.SHAPE_MISMATCH,
                path,
                operation,
                expression,
                state);
            return;
        }
        state.matchedPatternNodes++;
        compare(operation.left(), binary.left(), append(path, 0), state);
        compare(operation.right(), binary.right(), append(path, 1), state);
    }

    private static void compareFunction(
        PatternExpr.Function function,
        Expr expression,
        List<Integer> path,
        PartialState state
    ) {
        if (!(expression instanceof FunctionExpr candidate)
                || !candidate.name().equals(function.name())
                || candidate.arguments().size()
                    != function.arguments().size()) {
            mismatch(
                ResidualKind.FUNCTION_SHAPE_MISMATCH,
                path,
                function,
                expression,
                state);
            return;
        }
        state.matchedPatternNodes++;
        for (int index = 0; index < function.arguments().size(); index++) {
            compare(
                function.arguments().get(index),
                candidate.arguments().get(index),
                append(path, index),
                state);
        }
    }

    private static void mismatch(
        ResidualKind kind,
        List<Integer> path,
        PatternExpr requiredPattern,
        Expr actualExpression,
        PartialState state
    ) {
        state.obligations.add(new DraftObligation(
            kind,
            path,
            requiredPattern,
            actualExpression));
    }

    private static List<Integer> append(List<Integer> path, int component) {
        List<Integer> result = new ArrayList<>(path);
        result.add(component);
        return List.copyOf(result);
    }

    private static int patternNodeCount(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Operation operation) {
            return 1
                + patternNodeCount(operation.left())
                + patternNodeCount(operation.right());
        }
        if (pattern instanceof PatternExpr.Function function) {
            return 1 + function.arguments().stream()
                .mapToInt(PatternMatchAnalyzer::patternNodeCount)
                .sum();
        }
        return 1;
    }

    private static Set<String> placeholderNames(PatternExpr pattern) {
        Set<String> names = new TreeSet<>();
        collectPlaceholderNames(pattern, names);
        return Set.copyOf(names);
    }

    private static void collectPlaceholderNames(
        PatternExpr pattern,
        Set<String> result
    ) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            result.add(placeholder.name());
        } else if (pattern instanceof PatternExpr.Operation operation) {
            collectPlaceholderNames(operation.left(), result);
            collectPlaceholderNames(operation.right(), result);
        } else if (pattern instanceof PatternExpr.Function function) {
            function.arguments().forEach(argument ->
                collectPlaceholderNames(argument, result));
        }
    }

    private static String pathKey(List<Integer> path) {
        return path.isEmpty()
            ? "root"
            : path.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static boolean fullMatchStatus(Status status) {
        return status == Status.EXACT_MATCH
            || status == Status.MATCH_MODULO_THEORY;
    }

    public enum Status {
        EXACT_MATCH,
        MATCH_MODULO_THEORY,
        RESIDUAL,
        NOT_MATCHED,
        INCONCLUSIVE
    }

    public enum ResidualKind {
        SHAPE_MISMATCH,
        FUNCTION_SHAPE_MISMATCH,
        LITERAL_MISMATCH,
        BINDING_CONFLICT
    }

    public record ResidualObligation(
        ResidualKind kind,
        String path,
        PatternExpr requiredPattern,
        Expr actualExpression,
        Set<String> unboundPlaceholders
    ) {
        public ResidualObligation {
            kind = Objects.requireNonNull(kind, "kind");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("path must not be blank");
            }
            requiredPattern = Objects.requireNonNull(
                requiredPattern,
                "requiredPattern");
            actualExpression = Objects.requireNonNull(
                actualExpression,
                "actualExpression");
            unboundPlaceholders = Collections.unmodifiableSet(
                new LinkedHashSet<>(new TreeSet<>(
                    Objects.requireNonNull(
                        unboundPlaceholders,
                        "unboundPlaceholders"))));
        }
    }

    public record Analysis(
        Status status,
        List<ExprMatcher.MatchResult> matches,
        Map<String, Expr> bindings,
        List<ResidualObligation> residualObligations,
        List<ExprMatcher.MatchDiagnostic> diagnostics,
        int evaluatedSteps,
        int patternBranches,
        int structuralComparisons,
        int matchedPatternNodes,
        int totalPatternNodes,
        String detailCode
    ) {
        public Analysis {
            status = Objects.requireNonNull(status, "status");
            matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
            bindings = Collections.unmodifiableMap(new LinkedHashMap<>(
                new TreeMap<>(Objects.requireNonNull(bindings, "bindings"))));
            residualObligations = List.copyOf(Objects.requireNonNull(
                residualObligations,
                "residualObligations"));
            diagnostics = List.copyOf(Objects.requireNonNull(
                diagnostics,
                "diagnostics"));
            if (evaluatedSteps < 0 || patternBranches < 0
                    || structuralComparisons < 0 || matchedPatternNodes < 0
                    || totalPatternNodes < 1
                    || matchedPatternNodes > totalPatternNodes) {
                throw new IllegalArgumentException(
                    "match-analysis work and node counts are invalid");
            }
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            boolean fullMatch = fullMatchStatus(status);
            boolean hasMatches = !matches.isEmpty();
            if (fullMatch != hasMatches) {
                throw new IllegalArgumentException(
                    "only full-match statuses may retain matches");
            }
            boolean residual = status == Status.RESIDUAL;
            boolean hasObligations = !residualObligations.isEmpty();
            if (residual != hasObligations) {
                throw new IllegalArgumentException(
                    "only residual status may retain obligations");
            }
            if (status == Status.INCONCLUSIVE && diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "inconclusive analysis requires diagnostics");
            }
        }

        public boolean matched() {
            return fullMatchStatus(status);
        }

        public boolean residual() {
            return status == Status.RESIDUAL;
        }

        public boolean inconclusive() {
            return status == Status.INCONCLUSIVE;
        }
    }

    private static final class PartialState {
        private final Map<String, Expr> bindings = new LinkedHashMap<>();
        private final List<DraftObligation> obligations = new ArrayList<>();
        private int structuralComparisons;
        private int matchedPatternNodes;
    }

    private record DraftObligation(
        ResidualKind kind,
        List<Integer> path,
        PatternExpr requiredPattern,
        Expr actualExpression
    ) {
        private DraftObligation {
            kind = Objects.requireNonNull(kind, "kind");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            requiredPattern = Objects.requireNonNull(
                requiredPattern,
                "requiredPattern");
            actualExpression = Objects.requireNonNull(
                actualExpression,
                "actualExpression");
        }

        private ResidualObligation finish(Set<String> boundNames) {
            Set<String> unbound = new TreeSet<>(
                placeholderNames(requiredPattern));
            unbound.removeAll(boundNames);
            return new ResidualObligation(
                kind,
                pathKey(path),
                requiredPattern,
                actualExpression,
                unbound);
        }
    }
}
