package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
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
 * exact AST locations that still need to satisfy a pattern fragment. Matcher
 * and structural work share the configured matcher step limit; exhausted
 * residual work is {@link Status#INCONCLUSIVE}, never a negative fact.</p>
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

        int totalPatternNodes = patternNodeCount(pattern);
        ExprMatcher.MatchOutcome outcome =
            ExprMatcher.pattern(pattern, profile).match(expression, options);
        if (outcome.matched()) {
            return fullMatch(totalPatternNodes, outcome);
        }
        if (!outcome.complete()) {
            return matcherInconclusive(totalPatternNodes, outcome);
        }
        int remainingStructuralSteps = Math.max(
            0,
            options.maxSteps() - outcome.evaluatedSteps());
        return partialMatch(
            pattern,
            expression,
            totalPatternNodes,
            remainingStructuralSteps,
            outcome);
    }

    private static Analysis fullMatch(
        int totalPatternNodes,
        ExprMatcher.MatchOutcome outcome
    ) {
        ExprMatcher.MatchResult preferred = preferred(outcome.matches());
        ExprMatcher.RecognitionStrength strength =
            preferred.recognitionStrength();
        String detailCode = switch (strength) {
            case EXACT -> "EXACT_PATTERN_MATCH";
            case EQUIVALENCE_AWARE -> "EQUIVALENCE_AWARE_PATTERN_MATCH";
            case BOUNDED_REPRESENTATIVE ->
                "BOUNDED_REPRESENTATIVE_PATTERN_MATCH";
        };
        return new Analysis(
            strength == ExprMatcher.RecognitionStrength.EXACT
                ? Status.EXACT_MATCH
                : Status.MATCH_MODULO_THEORY,
            outcome.matches(),
            preferred.bindings(),
            List.of(),
            outcome.diagnostics(),
            outcome.evaluatedSteps(),
            outcome.patternBranches(),
            0,
            totalPatternNodes,
            totalPatternNodes,
            detailCode);
    }

    private static Analysis matcherInconclusive(
        int totalPatternNodes,
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
            totalPatternNodes,
            "MATCH_BUDGET_INCONCLUSIVE");
    }

    private static Analysis partialMatch(
        PatternExpr pattern,
        Expr expression,
        int totalPatternNodes,
        int structuralLimit,
        ExprMatcher.MatchOutcome outcome
    ) {
        PartialState partial = structuralCompare(
            pattern,
            expression,
            structuralLimit);
        if (partial.exhausted) {
            List<ExprMatcher.MatchDiagnostic> diagnostics =
                new ArrayList<>(outcome.diagnostics());
            diagnostics.add(new ExprMatcher.MatchDiagnostic(
                "STRUCTURAL_COMPARISON_LIMIT",
                "pattern-match-analyzer"));
            return new Analysis(
                Status.INCONCLUSIVE,
                List.of(),
                Map.of(),
                List.of(),
                diagnostics,
                outcome.evaluatedSteps(),
                outcome.patternBranches(),
                partial.structuralComparisons,
                partial.matchedPatternNodes,
                totalPatternNodes,
                "STRUCTURAL_COMPARISON_BUDGET_INCONCLUSIVE");
        }

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
                totalPatternNodes,
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
            totalPatternNodes,
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

    private static PartialState structuralCompare(
        PatternExpr pattern,
        Expr expression,
        int limit
    ) {
        PartialState state = new PartialState();
        Deque<Frame> pending = new ArrayDeque<>();
        pending.push(new Frame(pattern, expression, List.of()));
        while (!pending.isEmpty()) {
            if (state.structuralComparisons >= limit) {
                state.exhausted = true;
                break;
            }
            Frame frame = pending.pop();
            state.structuralComparisons++;
            PatternExpr required = frame.pattern();
            Expr actual = frame.expression();

            if (required instanceof PatternExpr.Placeholder placeholder) {
                Expr previous = state.bindings.get(placeholder.name());
                if (previous == null) {
                    state.bindings.put(placeholder.name(), actual);
                    state.matchedPatternNodes++;
                } else if (previous.equals(actual)) {
                    state.matchedPatternNodes++;
                } else {
                    state.mismatch(
                        ResidualKind.BINDING_CONFLICT,
                        frame,
                        required,
                        actual);
                }
                continue;
            }
            if (required instanceof PatternExpr.LiteralNumber literal) {
                if (actual instanceof NumberExpr number
                        && Double.compare(
                            number.value(),
                            literal.value()) == 0) {
                    state.matchedPatternNodes++;
                } else {
                    state.mismatch(
                        ResidualKind.LITERAL_MISMATCH,
                        frame,
                        required,
                        actual);
                }
                continue;
            }
            if (required instanceof PatternExpr.LiteralVariable literal) {
                if (actual instanceof VariableExpr variable
                        && variable.name().equals(literal.name())) {
                    state.matchedPatternNodes++;
                } else {
                    state.mismatch(
                        ResidualKind.LITERAL_MISMATCH,
                        frame,
                        required,
                        actual);
                }
                continue;
            }
            if (required instanceof PatternExpr.Operation operation) {
                if (!(actual instanceof BinaryExpr binary)
                        || binary.operator() != operation.operator()) {
                    state.mismatch(
                        ResidualKind.SHAPE_MISMATCH,
                        frame,
                        required,
                        actual);
                    continue;
                }
                state.matchedPatternNodes++;
                pending.push(new Frame(
                    operation.right(),
                    binary.right(),
                    append(frame.path(), 1)));
                pending.push(new Frame(
                    operation.left(),
                    binary.left(),
                    append(frame.path(), 0)));
                continue;
            }

            PatternExpr.Function function = (PatternExpr.Function) required;
            if (!(actual instanceof FunctionExpr candidate)
                    || !candidate.name().equals(function.name())
                    || candidate.arguments().size()
                        != function.arguments().size()) {
                state.mismatch(
                    ResidualKind.FUNCTION_SHAPE_MISMATCH,
                    frame,
                    required,
                    actual);
                continue;
            }
            state.matchedPatternNodes++;
            for (int index = function.arguments().size() - 1;
                    index >= 0;
                    index--) {
                pending.push(new Frame(
                    function.arguments().get(index),
                    candidate.arguments().get(index),
                    append(frame.path(), index)));
            }
        }
        return state;
    }

    private static List<Integer> append(List<Integer> path, int component) {
        List<Integer> result = new ArrayList<>(path);
        result.add(component);
        return List.copyOf(result);
    }

    private static int patternNodeCount(PatternExpr pattern) {
        int count = 0;
        Deque<PatternExpr> pending = new ArrayDeque<>();
        pending.push(pattern);
        while (!pending.isEmpty()) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("pattern is too large");
            }
            PatternExpr current = pending.pop();
            count++;
            if (current instanceof PatternExpr.Operation operation) {
                pending.push(operation.right());
                pending.push(operation.left());
            } else if (current instanceof PatternExpr.Function function) {
                for (int index = function.arguments().size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(function.arguments().get(index));
                }
            }
        }
        return count;
    }

    private static Set<String> placeholderNames(PatternExpr pattern) {
        Set<String> names = new TreeSet<>();
        Deque<PatternExpr> pending = new ArrayDeque<>();
        pending.push(pattern);
        while (!pending.isEmpty()) {
            PatternExpr current = pending.pop();
            if (current instanceof PatternExpr.Placeholder placeholder) {
                names.add(placeholder.name());
            } else if (current instanceof PatternExpr.Operation operation) {
                pending.push(operation.right());
                pending.push(operation.left());
            } else if (current instanceof PatternExpr.Function function) {
                for (int index = function.arguments().size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(function.arguments().get(index));
                }
            }
        }
        return Set.copyOf(names);
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
            if (fullMatch != !matches.isEmpty()) {
                throw new IllegalArgumentException(
                    "only full-match statuses may retain matches");
            }
            boolean residual = status == Status.RESIDUAL;
            if (residual != !residualObligations.isEmpty()) {
                throw new IllegalArgumentException(
                    "only residual status may retain obligations");
            }
            if (status == Status.INCONCLUSIVE && diagnostics.isEmpty()) {
                throw new IllegalArgumentException(
                    "inconclusive analysis requires diagnostics");
            }
            if (!fullMatch && !residual && !bindings.isEmpty()) {
                throw new IllegalArgumentException(
                    "only matched or residual analyses may retain bindings");
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
        private boolean exhausted;

        private void mismatch(
            ResidualKind kind,
            Frame frame,
            PatternExpr requiredPattern,
            Expr actualExpression
        ) {
            obligations.add(new DraftObligation(
                kind,
                frame.path(),
                requiredPattern,
                actualExpression));
        }
    }

    private record Frame(
        PatternExpr pattern,
        Expr expression,
        List<Integer> path
    ) {
        private Frame {
            pattern = Objects.requireNonNull(pattern, "pattern");
            expression = Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
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
