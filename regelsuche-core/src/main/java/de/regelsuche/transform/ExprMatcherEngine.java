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
        List<State> states = evaluate(
            matcher,
            expression,
            initial,
            session,
            true
        );
        states = session.limit(states, matcher.canonicalDescriptor());
        List<ExprMatcher.MatchResult> results = states.stream()
            .map(State::toResult)
            .toList();
        return new ExprMatcher.MatchOutcome(
            results,
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
        if (matcher instanceof ExprMatcher.AllOf allOf) {
            return matchAllOf(allOf, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.AnyOf anyOf) {
            return matchAnyOf(anyOf, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.Not not) {
            return matchNot(not, expression, state, session, atRoot);
        }
        if (matcher instanceof ExprMatcher.Operation operation) {
            return matchOperation(
                operation,
                expression,
                state,
                session,
                atRoot
            );
        }
        if (matcher instanceof ExprMatcher.Function function) {
            return matchFunction(
                function,
                expression,
                state,
                session,
                atRoot
            );
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
        List<State> candidates = evaluate(
            where.matcher(),
            expression,
            state,
            session,
            atRoot
        );
        List<State> accepted = new ArrayList<>();
        for (State candidate : candidates) {
            accepted.addAll(evaluateConstraint(
                where.constraint(),
                candidate,
                session
            ));
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
        Map<String, Expr> exactBindings = new HashMap<>(state.bindings);
        if (matcher.pattern().match(expression, exactBindings)) {
            return List.of(state
                .withBindings(exactBindings)
                .recognized(
                    ExprMatcher.RecognitionStrength.EXACT,
                    expression,
                    0,
                    atRoot
                )
                .traced("pattern:exact"));
        }

        EquivalenceAwarePatternMatcher.MatchAttempt attempt =
            EquivalenceAwarePatternMatcher.matchDetailed(
                matcher.pattern(),
                expression,
                state.bindings,
                matcher.recognitionProfile(),
                session.options.maxPatternBranches()
            );
        session.patternBranches += attempt.visitedBranches();
        if (attempt.inconclusive()) {
            session.diagnostic(
                attempt.limitCode(),
                matcher.canonicalDescriptor()
            );
            return List.of();
        }
        if (!attempt.matched()) {
            return List.of();
        }
        return List.of(state
            .withBindings(attempt.bindings())
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
        List<State> inner = evaluate(
            bind.matcher(),
            expression,
            state,
            session,
            atRoot
        );
        List<State> bound = new ArrayList<>();
        for (State candidate : inner) {
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
                    .recognized(
                        comparison.strength,
                        candidate.representative,
                        candidate.representativeIndex,
                        false
                    )
                    .traced("rebind:" + bind.name()));
            }
        }
        return session.limit(bound, bind.canonicalDescriptor());
    }

    private static List<State> matchAllOf(
        ExprMatcher.AllOf allOf,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<State> current = List.of(state);
        for (ExprMatcher matcher : allOf.matchers()) {
            List<State> next = new ArrayList<>();
            for (State candidate : current) {
                next.addAll(evaluate(
                    matcher,
                    expression,
                    candidate,
                    session,
                    atRoot
                ));
            }
            current = session.limit(
                next,
                allOf.canonicalDescriptor()
            );
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    private static List<State> matchAnyOf(
        ExprMatcher.AnyOf anyOf,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        List<State> matches = new ArrayList<>();
        for (ExprMatcher matcher : anyOf.matchers()) {
            matches.addAll(evaluate(
                matcher,
                expression,
                state,
                session,
                atRoot
            ));
        }
        return session.limit(matches, anyOf.canonicalDescriptor());
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
            not.matcher(),
            expression,
            state,
            session,
            atRoot
        );
        if (!excluded.isEmpty()) {
            return List.of();
        }
        if (session.diagnostics.size() > diagnosticCount) {
            return List.of();
        }
        return List.of(state.traced("not"));
    }

    private static List<State> matchOperation(
        ExprMatcher.Operation operation,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        if (!(expression instanceof BinaryExpr binary)
                || binary.operator() != operation.operator()) {
            return List.of();
        }
        List<State> leftMatches = evaluate(
            operation.left(),
            binary.left(),
            state,
            session,
            false
        );
        List<State> matches = new ArrayList<>();
        for (State left : leftMatches) {
            matches.addAll(evaluate(
                operation.right(),
                binary.right(),
                left,
                session,
                false
            ));
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
        Session session,
        boolean atRoot
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
            current = session.limit(
                next,
                function.canonicalDescriptor()
            );
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
        List<State> local = evaluate(
            matcher,
            expression,
            state,
            session,
            false
        );
        String renderedPath = path.isEmpty()
            ? "root"
            : path.stream()
                .map(String::valueOf)
                .reduce((left, right) -> left + "." + right)
                .orElse("root");
        local.stream()
            .map(match -> match.traced("contains@" + renderedPath))
            .forEach(matches::add);
        if (matches.size() >= session.options.maxResults()) {
            session.diagnostic(
                "MATCH_RESULT_LIMIT",
                matcher.canonicalDescriptor()
            );
            return;
        }
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
            .representatives(
                expression,
                equivalent.recognitionProfile()
            );
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
            List<State> local = evaluate(
                equivalent.matcher(),
                representative,
                state,
                session,
                atRoot
            );
            boolean changed = index > 0 || !representative.equals(expression);
            for (State candidate : local) {
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
            if (expression == null) {
                return List.of();
            }
            return evaluate(
                bindingMatches.matcher(),
                expression,
                state,
                session,
                false
            );
        }
        if (constraint instanceof ExprMatcher.SameAs sameAs) {
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
                    .recognized(
                        comparison.strength,
                        state.representative,
                        state.representativeIndex,
                        false
                    )
                    .traced("same-as"))
                : List.of();
        }
        if (constraint instanceof ExprMatcher.AllConstraints all) {
            List<State> current = List.of(state);
            for (ExprMatcher.Constraint nested : all.constraints()) {
                List<State> next = new ArrayList<>();
                for (State candidate : current) {
                    next.addAll(evaluateConstraint(
                        nested,
                        candidate,
                        session
                    ));
                }
                current = session.limit(
                    next,
                    all.canonicalDescriptor()
                );
                if (current.isEmpty()) {
                    break;
                }
            }
            return current;
        }
        if (constraint instanceof ExprMatcher.AnyConstraint any) {
            List<State> matches = new ArrayList<>();
            for (ExprMatcher.Constraint nested : any.constraints()) {
                matches.addAll(evaluateConstraint(
                    nested,
                    state,
                    session
                ));
            }
            return session.limit(matches, any.canonicalDescriptor());
        }
        ExprMatcher.NotConstraint not =
            (ExprMatcher.NotConstraint) constraint;
        int diagnosticCount = session.diagnostics.size();
        List<State> excluded = evaluateConstraint(
            not.constraint(),
            state,
            session
        );
        if (!excluded.isEmpty()
                || session.diagnostics.size() > diagnosticCount) {
            return List.of();
        }
        return List.of(state.traced("not-constraint"));
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
        PatternExpr literalPattern = literalPattern(expected);
        List<Expr> representatives = profile.recognitionRuleIds().isEmpty()
                || profile.maxEquivalenceDepth() == 0
            ? List.of(candidate)
            : session.options.representativeProvider()
                .representatives(candidate, profile);
        if (representatives == null || representatives.isEmpty()) {
            session.diagnostic(
                "REPRESENTATIVE_PROVIDER_EMPTY",
                descriptor
            );
            return Comparison.noMatch();
        }
        boolean inconclusive = false;
        for (int index = 0; index < representatives.size(); index++) {
            Expr representative = representatives.get(index);
            if (representative == null) {
                session.diagnostic(
                    "REPRESENTATIVE_PROVIDER_NULL",
                    descriptor
                );
                inconclusive = true;
                continue;
            }
            EquivalenceAwarePatternMatcher.MatchAttempt attempt =
                EquivalenceAwarePatternMatcher.matchDetailed(
                    literalPattern,
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
                inconclusive = true;
            }
        }
        return inconclusive
            ? Comparison.inconclusive()
            : Comparison.noMatch();
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
        FunctionExpr function = (FunctionExpr) expression;
        PatternExpr[] arguments = function.arguments().stream()
            .map(ExprMatcherEngine::literalPattern)
            .toArray(PatternExpr[]::new);
        return PatternExpr.fn(function.name(), arguments);
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
            if (steps >= options.maxSteps()) {
                if (!stepLimitReported) {
                    diagnostic("MATCH_STEP_LIMIT", descriptor);
                    stepLimitReported = true;
                }
                return false;
            }
            steps++;
            return true;
        }

        private void diagnostic(String code, String descriptor) {
            diagnostics.add(new ExprMatcher.MatchDiagnostic(
                code,
                descriptor
            ));
        }

        private List<State> limit(
            List<State> states,
            String descriptor
        ) {
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
                    recognitionStrength,
                    strength
                ),
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
                true,
                ExprMatcher.RecognitionStrength.EXACT
            );
        }

        private static Comparison noMatch() {
            return new Comparison(
                false,
                ExprMatcher.RecognitionStrength.EXACT
            );
        }

        private static Comparison inconclusive() {
            return noMatch();
        }
    }
}
