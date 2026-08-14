package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes the sealed {@link ExprMatcher} algebra deterministically. */
final class ExprMatcherEngine {
    private ExprMatcherEngine() {
    }

    static ExprMatcher.MatchOutcome match(
        ExprMatcher matcher,
        Expr expression,
        ExprMatcher.MatchOptions options
    ) {
        Session session = new Session(options);
        State initial = new State(
            Map.of(),
            expression,
            0,
            ExprMatcher.RecognitionStrength.EXACT,
            List.of()
        );
        List<State> states = session.limit(
            evaluate(matcher, expression, initial, session, true),
            matcher.canonicalDescriptor()
        );
        return new ExprMatcher.MatchOutcome(
            states.stream().map(State::toResult).toList(),
            List.copyOf(session.diagnostics),
            session.steps,
            session.patternBranches
        );
    }

    private static List<State> evaluate(
        ExprMatcher matcher,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        if (!session.consumeStep(matcher.canonicalDescriptor())) {
            return List.of();
        }
        if (matcher instanceof ExprMatcher.Any) {
            return List.of(state.traced("any"));
        }
        if (matcher instanceof ExprMatcher.LiteralNumber literal) {
            return expression instanceof NumberExpr number
                    && Double.compare(number.value(), literal.value()) == 0
                ? List.of(state.traced("literal-number"))
                : List.of();
        }
        if (matcher instanceof ExprMatcher.LiteralVariable literal) {
            return expression instanceof VariableExpr variable
                    && variable.name().equals(literal.name())
                ? List.of(state.traced("literal-variable"))
                : List.of();
        }
        if (matcher instanceof ExprMatcher.NumberProperty property) {
            return matchesNumberProperty(expression, property.kind())
                ? List.of(state.traced(
                    "number-property:" + property.kind().name()))
                : List.of();
        }
        if (matcher instanceof ExprMatcher.Pattern pattern) {
            return matchPattern(pattern, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.Bind bind) {
            return matchBind(bind, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.AllOf all) {
            return matchAll(all, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.AnyOf any) {
            return matchAny(any, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.Not not) {
            return matchNot(not, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.Operation operation) {
            return matchOperation(operation, expression, state, session);
        }
        if (matcher instanceof ExprMatcher.Function function) {
            return matchFunction(function, expression, state, session);
        }
        if (matcher instanceof ExprMatcher.Contains contains) {
            List<State> matches = new ArrayList<>();
            collectContained(
                contains.matcher(),
                expression,
                state,
                session,
                List.of(),
                matches
            );
            return session.limit(matches, matcher.canonicalDescriptor());
        }
        if (matcher instanceof ExprMatcher.Equivalent equivalent) {
            return matchEquivalent(
                equivalent,
                expression,
                state,
                session,
                atRoot
            );
        }
        ExprMatcher.Where where = (ExprMatcher.Where) matcher;
        List<State> accepted = new ArrayList<>();
        for (State candidate : evaluate(
                where.matcher(), expression, state, session, atRoot)) {
            accepted.addAll(evaluateConstraint(
                where.constraint(), candidate, session));
        }
        return session.limit(accepted, matcher.canonicalDescriptor());
    }

    private static List<State> matchPattern(
        ExprMatcher.Pattern matcher,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        EquivalenceAwarePatternMatcher.MatchAttempt exact =
            EquivalenceAwarePatternMatcher.matchDetailed(
                matcher.pattern(),
                expression,
                state.bindings,
                RecognitionProfile.exact(),
                session.options.maxPatternBranches()
            );
        session.patternBranches += exact.visitedBranches();
        if (exact.matched()) {
            return List.of(state
                .withBindings(exact.bindings())
                .recognized(
                    ExprMatcher.RecognitionStrength.EXACT,
                    expression,
                    0,
                    atRoot
                )
                .traced("pattern:exact"));
        }
        if (exact.inconclusive()) {
            session.diagnostic(exact.limitCode(), matcher.canonicalDescriptor());
            return List.of();
        }
        if (matcher.recognitionProfile().equals(RecognitionProfile.exact())) {
            return List.of();
        }
        EquivalenceAwarePatternMatcher.MatchAttempt equivalent =
            EquivalenceAwarePatternMatcher.matchDetailed(
                matcher.pattern(),
                expression,
                state.bindings,
                matcher.recognitionProfile(),
                session.options.maxPatternBranches()
            );
        session.patternBranches += equivalent.visitedBranches();
        if (equivalent.inconclusive()) {
            session.diagnostic(
                equivalent.limitCode(), matcher.canonicalDescriptor());
            return List.of();
        }
        if (!equivalent.matched()) {
            return List.of();
        }
        return List.of(state
            .withBindings(equivalent.bindings())
            .recognized(
                ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE,
                expression,
                0,
                atRoot
            )
            .traced("pattern:equivalence-aware"));
    }

    private static List<State> matchBind(
        ExprMatcher.Bind bind,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<State> bound = new ArrayList<>();
        for (State candidate : evaluate(
                bind.matcher(), expression, state, session, atRoot)) {
            Expr previous = candidate.bindings.get(bind.name());
            if (previous == null) {
                bound.add(candidate
                    .withBinding(bind.name(), expression)
                    .traced("bind:" + bind.name()));
                continue;
            }
            Comparison comparison = compare(
                previous,
                expression,
                bind.equalityProfile(),
                session,
                bind.canonicalDescriptor()
            );
            if (comparison.matched) {
                bound.add(candidate
                    .withStrength(comparison.strength)
                    .traced("rebind:" + bind.name()));
            }
        }
        return session.limit(bound, bind.canonicalDescriptor());
    }

    private static List<State> matchAll(
        ExprMatcher.AllOf all,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<State> current = List.of(state);
        for (ExprMatcher matcher : all.matchers()) {
            List<State> next = new ArrayList<>();
            for (State candidate : current) {
                next.addAll(evaluate(
                    matcher, expression, candidate, session, atRoot));
            }
            current = session.limit(next, all.canonicalDescriptor());
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    private static List<State> matchAny(
        ExprMatcher.AnyOf any,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<State> matches = new ArrayList<>();
        for (ExprMatcher matcher : any.matchers()) {
            matches.addAll(evaluate(
                matcher, expression, state, session, atRoot));
        }
        return session.limit(matches, any.canonicalDescriptor());
    }

    private static List<State> matchNot(
        ExprMatcher.Not not,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        int diagnosticCount = session.diagnostics.size();
        List<State> excluded = evaluate(
            not.matcher(), expression, state, session, atRoot);
        if (!excluded.isEmpty()
                || session.diagnostics.size() > diagnosticCount) {
            return List.of();
        }
        return List.of(state.traced("not"));
    }

    private static List<State> matchOperation(
        ExprMatcher.Operation operation,
        Expr expression,
        State state,
        Session session
    ) {
        if (!(expression instanceof BinaryExpr binary)
                || binary.operator() != operation.operator()) {
            return List.of();
        }
        List<State> matches = new ArrayList<>();
        for (State left : evaluate(
                operation.left(), binary.left(), state, session, false)) {
            matches.addAll(evaluate(
                operation.right(), binary.right(), left, session, false));
        }
        return session.limit(matches, operation.canonicalDescriptor()).stream()
            .map(candidate -> candidate.traced(
                "operation:" + operation.operator().name()))
            .toList();
    }

    private static List<State> matchFunction(
        ExprMatcher.Function function,
        Expr expression,
        State state,
        Session session
    ) {
        if (!(expression instanceof FunctionExpr candidate)
                || !candidate.name().equals(function.name())
                || candidate.arguments().size()
                    != function.arguments().size()) {
            return List.of();
        }
        List<State> current = List.of(state);
        for (int index = 0; index < function.arguments().size(); index++) {
            List<State> next = new ArrayList<>();
            for (State match : current) {
                next.addAll(evaluate(
                    function.arguments().get(index),
                    candidate.arguments().get(index),
                    match,
                    session,
                    false
                ));
            }
            current = session.limit(next, function.canonicalDescriptor());
            if (current.isEmpty()) {
                return List.of();
            }
        }
        return current.stream()
            .map(match -> match.traced("function:" + function.name()))
            .toList();
    }

    private static void collectContained(
        ExprMatcher matcher,
        Expr expression,
        State state,
        Session session,
        List<Integer> path,
        List<State> matches
    ) {
        if (matches.size() >= session.options.maxResults()) {
            session.diagnostic(
                "MATCH_RESULT_LIMIT", matcher.canonicalDescriptor());
            return;
        }
        String renderedPath = path.isEmpty()
            ? "root"
            : path.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "." + right)
                .orElse("root");
        evaluate(matcher, expression, state, session, false).stream()
            .map(match -> match.traced("contains@" + renderedPath))
            .forEach(matches::add);
        if (expression instanceof BinaryExpr binary) {
            collectContained(
                matcher,
                binary.left(),
                state,
                session,
                append(path, 0),
                matches
            );
            collectContained(
                matcher,
                binary.right(),
                state,
                session,
                append(path, 1),
                matches
            );
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0;
                    index < function.arguments().size();
                    index++) {
                collectContained(
                    matcher,
                    function.arguments().get(index),
                    state,
                    session,
                    append(path, index),
                    matches
                );
            }
        }
    }

    private static List<State> matchEquivalent(
        ExprMatcher.Equivalent equivalent,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<Expr> representatives = session.options
            .representativeProvider()
            .representatives(expression, equivalent.recognitionProfile());
        if (representatives == null || representatives.isEmpty()) {
            session.diagnostic(
                "REPRESENTATIVE_PROVIDER_EMPTY",
                equivalent.canonicalDescriptor()
            );
            return List.of();
        }
        List<State> matches = new ArrayList<>();
        for (int index = 0; index < representatives.size(); index++) {
            Expr representative = representatives.get(index);
            if (representative == null) {
                session.diagnostic(
                    "REPRESENTATIVE_PROVIDER_NULL",
                    equivalent.canonicalDescriptor()
                );
                continue;
            }
            boolean changed = index > 0 || !representative.equals(expression);
            for (State candidate : evaluate(
                    equivalent.matcher(),
                    representative,
                    state,
                    session,
                    atRoot)) {
                matches.add(changed
                    ? candidate
                        .recognized(
                            ExprMatcher.RecognitionStrength
                                .BOUNDED_REPRESENTATIVE,
                            representative,
                            index,
                            atRoot
                        )
                        .traced("representative:" + index)
                    : candidate);
            }
        }
        return session.limit(matches, equivalent.canonicalDescriptor());
    }

    private static List<State> evaluateConstraint(
        ExprMatcher.Constraint constraint,
        State state,
        Session session
    ) {
        if (!session.consumeStep(constraint.canonicalDescriptor())) {
            return List.of();
        }
        if (constraint instanceof ExprMatcher.BindingMatches bindingMatches) {
            Expr expression = state.bindings.get(bindingMatches.bindingName());
            return expression == null
                ? List.of()
                : evaluate(
                    bindingMatches.matcher(),
                    expression,
                    state,
                    session,
                    false
                );
        }
        ExprMatcher.SameAs sameAs = (ExprMatcher.SameAs) constraint;
        Expr left = state.bindings.get(sameAs.leftBinding());
        Expr right = state.bindings.get(sameAs.rightBinding());
        if (left == null || right == null) {
            return List.of();
        }
        Comparison comparison = compare(
            left,
            right,
            sameAs.recognitionProfile(),
            session,
            sameAs.canonicalDescriptor()
        );
        return comparison.matched
            ? List.of(state
                .withStrength(comparison.strength)
                .traced("same-as"))
            : List.of();
    }

    private static Comparison compare(
        Expr expected,
        Expr candidate,
        RecognitionProfile profile,
        Session session,
        String descriptor
    ) {
        if (expected.equals(candidate)) {
            return Comparison.exact();
        }
        List<Expr> representatives = profile.recognitionRuleIds().isEmpty()
                || profile.maxEquivalenceDepth() == 0
            ? List.of(candidate)
            : session.options.representativeProvider()
                .representatives(candidate, profile);
        if (representatives == null || representatives.isEmpty()) {
            session.diagnostic("REPRESENTATIVE_PROVIDER_EMPTY", descriptor);
            return Comparison.noMatch();
        }
        PatternExpr pattern = literalPattern(expected);
        for (int index = 0; index < representatives.size(); index++) {
            Expr representative = representatives.get(index);
            if (representative == null) {
                session.diagnostic("REPRESENTATIVE_PROVIDER_NULL", descriptor);
                continue;
            }
            EquivalenceAwarePatternMatcher.MatchAttempt attempt =
                EquivalenceAwarePatternMatcher.matchDetailed(
                    pattern,
                    representative,
                    Map.of(),
                    profile,
                    session.options.maxPatternBranches()
                );
            session.patternBranches += attempt.visitedBranches();
            if (attempt.matched()) {
                return new Comparison(
                    true,
                    index > 0 || !representative.equals(candidate)
                        ? ExprMatcher.RecognitionStrength
                            .BOUNDED_REPRESENTATIVE
                        : ExprMatcher.RecognitionStrength
                            .EQUIVALENCE_AWARE
                );
            }
            if (attempt.inconclusive()) {
                session.diagnostic(attempt.limitCode(), descriptor);
            }
        }
        return Comparison.noMatch();
    }

    private static PatternExpr literalPattern(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return PatternExpr.num(number.value());
        }
        if (expression instanceof VariableExpr variable) {
            return PatternExpr.variable(variable.name());
        }
        if (expression instanceof BinaryExpr binary) {
            return PatternExpr.op(
                binary.operator(),
                literalPattern(binary.left()),
                literalPattern(binary.right())
            );
        }
        if (expression instanceof FunctionExpr function) {
            return PatternExpr.fn(
                function.name(),
                function.arguments().stream()
                    .map(ExprMatcherEngine::literalPattern)
                    .toArray(PatternExpr[]::new)
            );
        }
        throw new IllegalArgumentException(
            "Unsupported expression type: " + expression.getClass().getName());
    }

    private static boolean matchesNumberProperty(
        Expr expression,
        ExprMatcher.NumberPropertyKind kind
    ) {
        if (!(expression instanceof NumberExpr number)
                || !Double.isFinite(number.value())) {
            return false;
        }
        return switch (kind) {
            case NUMBER_LITERAL -> true;
            case INTEGER_LITERAL -> number.value() == Math.rint(number.value());
            case NON_ZERO_NUMBER_LITERAL -> number.value() != 0.0;
        };
    }

    private static List<Integer> append(List<Integer> path, int index) {
        List<Integer> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(index);
        return List.copyOf(result);
    }

    private static final class Session {
        private final ExprMatcher.MatchOptions options;
        private final Set<ExprMatcher.MatchDiagnostic> diagnostics =
            new LinkedHashSet<>();
        private int steps;
        private int patternBranches;
        private boolean stepLimitReported;

        private Session(ExprMatcher.MatchOptions options) {
            this.options = options;
        }

        private boolean consumeStep(String descriptor) {
            if (steps < options.maxSteps()) {
                steps++;
                return true;
            }
            if (!stepLimitReported) {
                diagnostic("MATCH_STEP_LIMIT", descriptor);
                stepLimitReported = true;
            }
            return false;
        }

        private void diagnostic(String code, String descriptor) {
            diagnostics.add(new ExprMatcher.MatchDiagnostic(code, descriptor));
        }

        private List<State> limit(List<State> states, String descriptor) {
            if (states.size() <= options.maxResults()) {
                return List.copyOf(states);
            }
            diagnostic("MATCH_RESULT_LIMIT", descriptor);
            return List.copyOf(states.subList(0, options.maxResults()));
        }
    }

    private record State(
        Map<String, Expr> bindings,
        Expr representative,
        int representativeIndex,
        ExprMatcher.RecognitionStrength recognitionStrength,
        List<String> trace
    ) {
        private State {
            bindings = Map.copyOf(bindings);
            representative = Objects.requireNonNull(
                representative, "representative");
            recognitionStrength = Objects.requireNonNull(
                recognitionStrength, "recognitionStrength");
            trace = List.copyOf(trace);
        }

        private State withBindings(Map<String, Expr> replacements) {
            return new State(
                replacements,
                representative,
                representativeIndex,
                recognitionStrength,
                trace
            );
        }

        private State withBinding(String name, Expr expression) {
            Map<String, Expr> updated = new HashMap<>(bindings);
            updated.put(name, expression);
            return withBindings(updated);
        }

        private State withStrength(
            ExprMatcher.RecognitionStrength strength
        ) {
            return new State(
                bindings,
                representative,
                representativeIndex,
                ExprMatcher.RecognitionStrength.strongest(
                    recognitionStrength, strength),
                trace
            );
        }

        private State recognized(
            ExprMatcher.RecognitionStrength strength,
            Expr matchedRepresentative,
            int matchedRepresentativeIndex,
            boolean replaceRootRepresentative
        ) {
            return new State(
                bindings,
                replaceRootRepresentative
                    ? matchedRepresentative
                    : representative,
                replaceRootRepresentative
                    ? matchedRepresentativeIndex
                    : representativeIndex,
                ExprMatcher.RecognitionStrength.strongest(
                    recognitionStrength, strength),
                trace
            );
        }

        private State traced(String entry) {
            List<String> updated = new ArrayList<>(trace.size() + 1);
            updated.addAll(trace);
            updated.add(entry);
            return new State(
                bindings,
                representative,
                representativeIndex,
                recognitionStrength,
                updated
            );
        }

        private ExprMatcher.MatchResult toResult() {
            return new ExprMatcher.MatchResult(
                bindings,
                representative,
                representativeIndex,
                recognitionStrength,
                trace
            );
        }
    }

    private record Comparison(
        boolean matched,
        ExprMatcher.RecognitionStrength strength
    ) {
        private static Comparison exact() {
            return new Comparison(
                true, ExprMatcher.RecognitionStrength.EXACT);
        }

        private static Comparison noMatch() {
            return new Comparison(
                false, ExprMatcher.RecognitionStrength.EXACT);
        }
    }
}
