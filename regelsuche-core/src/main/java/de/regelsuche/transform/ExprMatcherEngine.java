package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
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
        session.initial = new State(
            Map.of(),
            expression,
            0,
            ExprMatcher.RecognitionStrength.EXACT,
            List.of()
        );
        try (var owned = RetainedOperation.retainCompleted(3,matcher,expression,session)) {
            try {
                session.rawStates = evaluate(matcher,expression,session.initial,session,true);
                session.states = session.limit(session.rawStates,matcher);
                // limit pays its own copy; these are the two caller assignments.
                RetainedOperation.work(2);
                // Keep both actual lists until publication. Root assembly only
                // adds owners, so that observation includes its earlier peaks.
                session.results = new ArrayList<>(session.states.size());
                RetainedOperation.work(1);
                for (State state : session.states) {
                    session.results.add(state.toResult());
                    // Result, sorted binding owner/backing, copied entries, and list insertion.
                    RetainedOperation.work(4L + state.bindings().size());
                }
                session.outcome = new ExprMatcher.MatchOutcome(session.results,List.copyOf(session.diagnostics),
                    session.steps,session.patternBranches);
                RetainedOperation.work(3L + session.results.size() + session.diagnostics.size());
                RetainedOperation.checkpoint();
                return session.outcome;
            } catch (RuntimeException | Error failure) {
                try { RetainedOperation.checkpoint(); }
                catch (RuntimeException | Error observation) {
                    if (observation != failure) failure.addSuppressed(observation);
                }
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            // Only returned outcomes delegate counters. This also covers a failed
            // final frame close; a nested failed pattern attempt settles itself.
            try { RetainedOperation.work((long) session.steps + session.patternBranches); }
            catch (RuntimeException | Error accounting) {
                if (accounting != failure) failure.addSuppressed(accounting);
            }
            throw failure;
        }
    }

    private static List<State> evaluate(
        ExprMatcher matcher,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        if (!session.consumeStep()) {
            if (!session.stepLimitReported) {
                session.reportStepLimit(matcher.canonicalDescriptor());
            }
            return List.of();
        }
        if (matcher instanceof ExprMatcher.Any) {
            return List.of(state.traced("any"));
        }
        if (matcher instanceof ExprMatcher.LiteralNumber literal) {
            return expression instanceof NumberExpr number
                    && number.value().equals(literal.value())
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
                    MatcherTrace.text("number-property:",property.kind().name())))
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
            return matchContains(contains,expression,state,session);
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
        return matchWhere((ExprMatcher.Where) matcher,expression,state,session,atRoot);
    }

    private static List<State> matchPattern(
        ExprMatcher.Pattern matcher,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        var work = new PatternAttemptWork();
        try (var owned = RetainedOperation.retainCompleted(2,matcher,expression,state,session,work)) {
            try {
                work.attempt = EquivalenceAwarePatternMatcher.matchDetailed(matcher.pattern(),expression,
                    state.bindings,work.exactProfile,session.options.maxPatternBranches());
                session.patternBranches += work.attempt.visitedBranches();
                RetainedOperation.work(1);
                RetainedOperation.checkpoint();
                if (work.attempt.matched()) {
                    return work.completed(state.withBindings(work.attempt.bindings())
                        .recognized(ExprMatcher.RecognitionStrength.EXACT,expression,0,atRoot)
                        .traced("pattern:exact"));
                }
                if (work.attempt.inconclusive()) {
                    session.diagnostic(work.attempt.limitCode(),matcher.canonicalDescriptor());
                    return List.of();
                }
                if (matcher.recognitionProfile().equals(work.exactProfile)) return List.of();
                // The conclusive failed exact attempt is no longer needed.
                work.attempt = null;
                RetainedOperation.work(1);
                work.attempt = EquivalenceAwarePatternMatcher.matchDetailed(matcher.pattern(),expression,
                    state.bindings,matcher.recognitionProfile(),session.options.maxPatternBranches());
                // Completed returned work is delegated before the next debit.
                session.patternBranches += work.attempt.visitedBranches();
                RetainedOperation.work(1);
                RetainedOperation.checkpoint();
                if (work.attempt.inconclusive()) {
                    session.diagnostic(work.attempt.limitCode(),matcher.canonicalDescriptor());
                    return List.of();
                }
                if (!work.attempt.matched()) return List.of();
                return work.completed(state.withBindings(work.attempt.bindings())
                    .recognized(ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE,expression,0,atRoot)
                    .traced("pattern:equivalence-aware"));
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static List<State> matchBind(
        ExprMatcher.Bind bind,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,bind,expression,state,session,lists)) {
            try {
                lists.begin();
                lists.current = evaluate(bind.matcher(),expression,state,session,atRoot);
                RetainedOperation.work(1);
                for (State candidate : lists.current) {
                    Expr previous = candidate.bindings.get(bind.name());
                    if (previous == null) {
                        RetainedOperation.work(1);
                        if (lists.trace == null) {
                            lists.trace = MatcherTrace.text("bind:",bind.name());
                            RetainedOperation.work(1);
                        }
                        lists.add(candidate.withBinding(bind.name(),expression).traced(lists.trace));
                        continue;
                    }
                    ExprMatcher.RecognitionStrength strength = compare(previous,expression,bind.equalityProfile(),session,bind);
                    if (strength != null) {
                        RetainedOperation.work(1);
                        if (lists.rebindTrace == null) {
                            lists.rebindTrace = MatcherTrace.text("rebind:",bind.name());
                            RetainedOperation.work(1);
                        }
                        lists.add(candidate.withStrength(strength).traced(lists.rebindTrace));
                    }
                }
                lists.freeze(session,bind);
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static List<State> matchWhere(
        ExprMatcher.Where where,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,where,expression,state,session,lists)) {
            try {
                lists.begin();
                lists.current = evaluate(where.matcher(),expression,state,session,atRoot);
                RetainedOperation.work(1);
                for (State candidate : lists.current) {
                    lists.add(evaluateConstraint(where.constraint(),candidate,session));
                }
                lists.freeze(session,where);
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static List<State> matchAll(
        ExprMatcher.AllOf all,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        var lists = new StateLists(); lists.current = List.of(state);
        try (var owned = RetainedOperation.retainCompleted(3,all,expression,state,session,lists)) {
            try {
                for (ExprMatcher matcher : all.matchers()) {
                    lists.begin();
                    for (State candidate : lists.current) {
                        lists.add(evaluate(matcher,expression,candidate,session,atRoot));
                    }
                    lists.freeze(session,all);
                    lists.advance();
                    if (lists.current.isEmpty()) break;
                }
                return lists.current;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static List<State> matchAny(
        ExprMatcher.AnyOf any,
        Expr expression,
        State state,
        Session session,
        boolean atRoot
    ) {
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,any,expression,state,session,lists)) {
            try {
                lists.begin();
                for (ExprMatcher matcher : any.matchers()) {
                    lists.add(evaluate(matcher,expression,state,session,atRoot));
                }
                lists.freeze(session,any);
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
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
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,operation,expression,state,session,lists)) {
            try {
                lists.begin();
                lists.current = evaluate(operation.left(),binary.left(),state,session,false);
                RetainedOperation.work(1);
                for (State left : lists.current) {
                    lists.add(evaluate(operation.right(),binary.right(),left,session,false));
                }
                lists.freeze(session,operation);
                lists.advance();
                lists.begin();
                RetainedOperation.work(1);
                if (!lists.current.isEmpty()) {
                    lists.trace = MatcherTrace.text("operation:",operation.operator().name());
                    RetainedOperation.work(1);
                    for (State candidate : lists.current) lists.add(candidate.traced(lists.trace));
                }
                lists.freeze();
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
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
        var lists = new StateLists(); lists.current = List.of(state);
        try (var owned = RetainedOperation.retainCompleted(3,function,expression,state,session,lists)) {
            try {
                for (int index = 0; index < function.arguments().size(); index++) {
                    lists.begin();
                    for (State match : lists.current) {
                        lists.add(evaluate(function.arguments().get(index),candidate.arguments().get(index),
                            match,session,false));
                    }
                    lists.freeze(session,function);
                    lists.advance();
                    if (lists.current.isEmpty()) return List.of();
                }
                lists.begin();
                lists.trace = MatcherTrace.text("function:",function.name());
                RetainedOperation.work(1);
                for (State match : lists.current) lists.add(match.traced(lists.trace));
                lists.freeze();
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static List<State> matchContains(
        ExprMatcher.Contains contains,
        Expr expression,
        State state,
        Session session
    ) {
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,contains,expression,state,session,lists)) {
            try {
                lists.begin();
                collectContained(contains.matcher(),expression,state,session,List.of(),lists);
                lists.freeze(session,contains);
                return lists.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static void collectContained(
        ExprMatcher matcher,
        Expr expression,
        State state,
        Session session,
        List<Integer> path,
        StateLists matches
    ) {
        var visit = new ContainedVisit(path);
        try (var owned = RetainedOperation.retainCompleted(1,matcher,expression,state,session,visit,matches)) {
            try {
                if (matches.next.size() >= session.options.maxResults()) {
                    session.diagnostic("MATCH_RESULT_LIMIT",matcher.canonicalDescriptor());
                    return;
                }
                visit.candidates = evaluate(matcher,expression,state,session,false);
                RetainedOperation.work(1);
                if (!visit.candidates.isEmpty()) {
                    visit.renderedPath = MatcherOccurrencePath.render(path);
                    RetainedOperation.work(1);
                    visit.trace = MatcherTrace.text("contains@",visit.renderedPath);
                    RetainedOperation.work(1);
                    for (State match : visit.candidates) matches.add(match.traced(visit.trace));
                }
                // Even an empty result is observed before this visit releases it.
                RetainedOperation.checkpoint();
                visit.candidates = null; visit.renderedPath = null; visit.trace = null;
                RetainedOperation.work(3);
                if (expression instanceof BinaryExpr binary) {
                    collectContained(matcher,binary.left(),state,session,append(path,0),matches);
                    collectContained(matcher,binary.right(),state,session,append(path,1),matches);
                } else if (expression instanceof FunctionExpr function) {
                    for (int index = 0; index < function.arguments().size(); index++) {
                        collectContained(matcher,function.arguments().get(index),state,session,append(path,index),matches);
                    }
                }
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
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
        var lists = new StateLists();
        try (var owned = RetainedOperation.retainCompleted(1,equivalent,expression,state,session,lists)) {
            List<Expr> representatives = session.options.representativeProvider()
                .representatives(expression,equivalent.recognitionProfile());
            try (var representativeOwner = RetainedOperation.retain(representatives)) {
                try {
                    if (representatives == null || representatives.isEmpty()) {
                        session.diagnostic("REPRESENTATIVE_PROVIDER_EMPTY",equivalent.canonicalDescriptor());
                        return List.of();
                    }
                    lists.begin();
                    for (int index = 0; index < representatives.size(); index++) {
                        Expr representative = representatives.get(index);
                        if (representative == null) {
                            session.diagnostic("REPRESENTATIVE_PROVIDER_NULL",equivalent.canonicalDescriptor());
                            continue;
                        }
                        boolean changed = index > 0 || !representative.equals(expression);
                        lists.current = evaluate(equivalent.matcher(),representative,state,session,atRoot);
                        RetainedOperation.work(1);
                        if (changed && !lists.current.isEmpty()) {
                            lists.trace = MatcherTrace.ordinal("representative:",index);
                            RetainedOperation.work(1);
                        }
                        for (State candidate : lists.current) {
                            lists.add(changed ? candidate.recognized(ExprMatcher.RecognitionStrength.BOUNDED_REPRESENTATIVE,
                                representative,index,atRoot).traced(lists.trace) : candidate);
                        }
                        // Observe empty as well as successful child lists before replacing them.
                        RetainedOperation.checkpoint();
                    }
                    lists.freeze(session,equivalent);
                    return lists.result;
                } catch (RuntimeException | Error failure) {
                    observeFailure(failure); throw failure;
                }
            }
        }
    }

    private static List<State> evaluateConstraint(
        ExprMatcher.Constraint constraint,
        State state,
        Session session
    ) {
        if (!session.consumeStep()) {
            if (!session.stepLimitReported) {
                session.reportStepLimit(constraint.canonicalDescriptor());
            }
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
        ExprMatcher.RecognitionStrength strength = compare(
            left,
            right,
            sameAs.recognitionProfile(),
            session,
            sameAs
        );
        return strength != null
            ? List.of(state
                .withStrength(strength)
                .traced("same-as"))
            : List.of();
    }

    /** Returns the existing recognition strength, or null for no match; no result wrapper is allocated. */
    private static ExprMatcher.RecognitionStrength compare(
        Expr expected,
        Expr candidate,
        RecognitionProfile profile,
        Session session,
        MatcherDescriptor.Source source
    ) {
        if (expected.equals(candidate)) {
            return ExprMatcher.RecognitionStrength.EXACT;
        }
        var comparison = new ComparisonWork(expected,candidate,profile,source);
        try (var owned = RetainedOperation.retainCompleted(1,comparison,session)) {
            try {
                boolean identityOnly = profile.recognitionRuleIds().isEmpty() || profile.maxEquivalenceDepth() == 0;
                comparison.representatives = identityOnly ? List.of(candidate)
                    : session.options.representativeProvider().representatives(candidate,profile);
                // Only the local singleton's allocation belongs to this caller.
                RetainedOperation.work(identityOnly ? 2 : 1);
                if (comparison.representatives == null || comparison.representatives.isEmpty()) {
                    comparison.diagnostic(session,"REPRESENTATIVE_PROVIDER_EMPTY");
                    return null;
                }
                comparison.pattern = literalPattern(expected);
                RetainedOperation.work(1);
                for (int index = 0; index < comparison.representatives.size(); index++) {
                    Expr representative = comparison.representatives.get(index);
                    if (representative == null) {
                        comparison.diagnostic(session,"REPRESENTATIVE_PROVIDER_NULL");
                        continue;
                    }
                    comparison.attempt = EquivalenceAwarePatternMatcher.matchDetailed(comparison.pattern,representative,
                        Map.of(),profile,session.options.maxPatternBranches());
                    // Take delegated branches before any subsequent debit can fail.
                    session.patternBranches += comparison.attempt.visitedBranches();
                    RetainedOperation.work(1);
                    RetainedOperation.checkpoint();
                    if (comparison.attempt.matched()) {
                        return index > 0 || !representative.equals(candidate)
                            ? ExprMatcher.RecognitionStrength.BOUNDED_REPRESENTATIVE
                            : ExprMatcher.RecognitionStrength.EQUIVALENCE_AWARE;
                    }
                    if (comparison.attempt.inconclusive()) comparison.diagnostic(session,comparison.attempt.limitCode());
                }
                return null;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static PatternExpr literalPattern(Expr expression) {
        if (expression instanceof NumberExpr number) {
            PatternExpr result = PatternExpr.num(number.value());
            try (var owned = RetainedOperation.retainCompleted(1,expression,result)) { return result; }
        }
        if (expression instanceof VariableExpr variable) {
            PatternExpr result = PatternExpr.variable(variable.name());
            try (var owned = RetainedOperation.retainCompleted(1,expression,result)) { return result; }
        }
        var assembly = new LiteralPatternAssembly(expression);
        try (var owned = RetainedOperation.retainCompleted(1,assembly)) {
            try {
                if (expression instanceof BinaryExpr binary) {
                    assembly.left = literalPattern(binary.left());
                    RetainedOperation.work(1);
                    assembly.right = literalPattern(binary.right());
                    RetainedOperation.work(1);
                    assembly.result = PatternExpr.op(binary.operator(),assembly.left,assembly.right);
                    RetainedOperation.work(1);
                } else if (expression instanceof FunctionExpr function) {
                    assembly.arguments = new ArrayList<>();
                    RetainedOperation.work(1);
                    for (Expr argument : function.arguments()) {
                        assembly.arguments.add(literalPattern(argument));
                        RetainedOperation.work(1);
                    }
                    assembly.result = new PatternExpr.Function(function.name(),assembly.arguments);
                    RetainedOperation.work(3L + assembly.arguments.size());
                } else {
                    throw new IllegalArgumentException("Unsupported expression type: " + expression.getClass().getName());
                }
                RetainedOperation.checkpoint();
                return assembly.result;
            } catch (RuntimeException | Error failure) {
                observeFailure(failure); throw failure;
            }
        }
    }

    private static boolean matchesNumberProperty(
        Expr expression,
        ExprMatcher.NumberPropertyKind kind
    ) {
        if (!(expression instanceof NumberExpr number)) {
            return false;
        }
        return switch (kind) {
            case NUMBER_LITERAL -> true;
            case INTEGER_LITERAL -> number.value().isInteger();
            case NON_ZERO_NUMBER_LITERAL -> !number.value().equalsInteger(0);
        };
    }

    private static List<Integer> append(List<Integer> path, int index) {
        List<Integer> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(index);
        List<Integer> frozen = List.copyOf(result);
        try (var owned = RetainedOperation.retainCompleted(5L + path.size() + frozen.size(),path,result,frozen)) {
            return frozen;
        }
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }

    private static final class PatternAttemptWork implements RetainedGraph.View {
        private final RecognitionProfile exactProfile = RecognitionProfile.exact();
        private EquivalenceAwarePatternMatcher.MatchAttempt attempt;
        private List<State> result;

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(exactProfile); visitor.reference(attempt); visitor.reference(result);
        }

        private List<State> completed(State state) {
            result = List.of(state);
            RetainedOperation.work(2);
            RetainedOperation.checkpoint();
            return result;
        }
    }

    private static final class ComparisonWork implements RetainedGraph.View {
        private final Expr expected, candidate;
        private final RecognitionProfile profile;
        private final MatcherDescriptor.Source source;
        private List<Expr> representatives;
        private PatternExpr pattern;
        private EquivalenceAwarePatternMatcher.MatchAttempt attempt;
        private String descriptor;

        private ComparisonWork(Expr expected,Expr candidate,RecognitionProfile profile,MatcherDescriptor.Source source) {
            this.expected = expected; this.candidate = candidate; this.profile = profile; this.source = source;
        }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(expected); visitor.reference(candidate); visitor.reference(profile); visitor.reference(source);
            visitor.reference(representatives); visitor.reference(pattern); visitor.reference(attempt); visitor.reference(descriptor);
        }

        private void diagnostic(Session session,String code) {
            RetainedOperation.work(1);
            if (descriptor == null) {
                descriptor = source.canonicalDescriptor();
                RetainedOperation.work(1);
            }
            session.diagnostic(code,descriptor);
        }
    }

    private static final class LiteralPatternAssembly implements RetainedGraph.View {
        private final Expr source;
        private PatternExpr left, right, result;
        private ArrayList<PatternExpr> arguments;

        private LiteralPatternAssembly(Expr source) { this.source = source; }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(left); visitor.reference(right);
            visitor.reference(result); visitor.reference(arguments);
        }
    }

    private static final class ContainedVisit implements RetainedGraph.View {
        private final List<Integer> path;
        private String renderedPath;
        private String trace;
        private List<State> candidates;

        private ContainedVisit(List<Integer> path) { this.path = path; }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(path); visitor.reference(renderedPath); visitor.reference(candidates); visitor.reference(trace);
        }
    }

    /** Actual per-call owners; no completed result is registered beyond the caller's frame. */
    private static final class StateLists implements RetainedGraph.View {
        private List<State> current;
        private ArrayList<State> next;
        private List<State> child;
        private List<State> result;
        private String trace;
        private String rebindTrace;

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(current); visitor.reference(next);
            visitor.reference(child); visitor.reference(result);
            visitor.reference(trace); visitor.reference(rebindTrace);
        }

        private void begin() {
            next = new ArrayList<>(); child = null; result = null;
            RetainedOperation.work(4);
        }

        private void add(List<State> values) {
            child = values;
            RetainedOperation.work(1);
            next.addAll(values);
            RetainedOperation.work(1L + values.size());
            // Observe before the next child replaces its actual result list.
            RetainedOperation.checkpoint();
            child = null;
            RetainedOperation.work(1);
        }

        private void add(State value) {
            next.add(value);
            RetainedOperation.work(1);
            RetainedOperation.checkpoint();
        }

        private void freeze(Session session,ExprMatcher matcher) {
            result = session.limit(next,matcher);
            // The limiter owns and pays the copy before this handoff.
            RetainedOperation.work(1);
            RetainedOperation.checkpoint();
        }

        private void freeze() {
            result = List.copyOf(next);
            observeFrozen();
        }

        private void observeFrozen() {
            RetainedOperation.work(2L + result.size());
            RetainedOperation.checkpoint();
        }

        private void advance() {
            current = result;
            RetainedOperation.work(1);
        }
    }

    private static final class LimitedStates implements RetainedGraph.View {
        private final List<State> source;
        private State[] prefix;
        private List<State> result;

        private LimitedStates(List<State> source) { this.source = source; }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(source); visitor.reference(prefix); visitor.reference(result);
        }
    }

    private static final class Session implements RetainedGraph.View {
        private final ExprMatcher.MatchOptions options;
        private final Set<ExprMatcher.MatchDiagnostic> diagnostics =
            new LinkedHashSet<>();
        private int steps;
        private int patternBranches;
        private boolean stepLimitReported;
        private State initial;
        private List<State> rawStates;
        private List<State> states;
        private List<ExprMatcher.MatchResult> results;
        private ExprMatcher.MatchOutcome outcome;

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(options); visitor.reference(diagnostics); visitor.reference(initial);
            visitor.reference(rawStates); visitor.reference(states); visitor.reference(results); visitor.reference(outcome);
        }

        private Session(ExprMatcher.MatchOptions options) {
            this.options = options;
        }

        private boolean consumeStep() {
            if (steps < options.maxSteps()) {
                steps++;
                return true;
            }
            return false;
        }

        private void reportStepLimit(String descriptor) {
            diagnostic("MATCH_STEP_LIMIT", descriptor);
            stepLimitReported = true;
        }

        private void diagnostic(String code, String descriptor) {
            var diagnostic = new ExprMatcher.MatchDiagnostic(code,descriptor);
            diagnostics.add(diagnostic);
            // Include attempted duplicates as well as inserted records. The
            // current visit/provider owner must still overlap the changed set.
            try (var owned = RetainedOperation.retainCompleted(2,diagnostic,diagnostics)) { }
        }

        private List<State> limit(List<State> states, ExprMatcher matcher) {
            if (states.size() <= options.maxResults()) {
                List<State> result = List.copyOf(states);
                if (result == states) {
                    RetainedOperation.work(1);
                    return result;
                }
                try (var owned = RetainedOperation.retainCompleted(1L + result.size(),states,result)) {
                    return result;
                }
            }
            diagnostic("MATCH_RESULT_LIMIT", matcher.canonicalDescriptor());
            var limited = new LimitedStates(states);
            try (var owned = RetainedOperation.retainCompleted(1,this,matcher,limited)) {
                try {
                    limited.prefix = new State[options.maxResults()];
                    RetainedOperation.work(1L + limited.prefix.length);
                    for (int index = 0; index < limited.prefix.length; index++) {
                        limited.prefix[index] = states.get(index);
                        RetainedOperation.work(1);
                    }
                    limited.result = List.of(limited.prefix);
                    RetainedOperation.work(2L + limited.prefix.length);
                    RetainedOperation.checkpoint();
                    return limited.result;
                } catch (RuntimeException | Error failure) {
                    observeFailure(failure); throw failure;
                }
            }
        }
    }

    private record State(
        Map<String, Expr> bindings,
        Expr representative,
        int representativeIndex,
        ExprMatcher.RecognitionStrength recognitionStrength,
        List<String> trace
    ) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(bindings); visitor.reference(representative);
            visitor.reference(recognitionStrength); visitor.reference(trace);
        }
        private State {
            bindings = Map.copyOf(bindings);
            representative = Objects.requireNonNull(
                representative, "representative");
            recognitionStrength = Objects.requireNonNull(
                recognitionStrength, "recognitionStrength");
            trace = List.copyOf(trace);
        }

        private State withBindings(Map<String, Expr> replacements) {
            return updated(
                replacements,
                representative,
                representativeIndex,
                recognitionStrength,
                trace,
                0
            );
        }

        private State withBinding(String name, Expr expression) {
            Map<String, Expr> updated = new HashMap<>(bindings);
            updated.put(name, expression);
            return updated(updated,representative,representativeIndex,recognitionStrength,trace,
                3L + bindings.size());
        }

        private State withStrength(
            ExprMatcher.RecognitionStrength strength
        ) {
            return updated(
                bindings,
                representative,
                representativeIndex,
                ExprMatcher.RecognitionStrength.strongest(
                    recognitionStrength, strength),
                trace,
                0
            );
        }

        private State recognized(
            ExprMatcher.RecognitionStrength strength,
            Expr matchedRepresentative,
            int matchedRepresentativeIndex,
            boolean replaceRootRepresentative
        ) {
            return updated(
                bindings,
                replaceRootRepresentative
                    ? matchedRepresentative
                    : representative,
                replaceRootRepresentative
                    ? matchedRepresentativeIndex
                    : representativeIndex,
                ExprMatcher.RecognitionStrength.strongest(
                    recognitionStrength, strength),
                trace,
                0
            );
        }

        private State traced(String entry) {
            List<String> updated = new ArrayList<>(trace.size() + 1);
            updated.addAll(trace);
            updated.add(entry);
            return updated(
                bindings,
                representative,
                representativeIndex,
                recognitionStrength,
                updated,
                3L + trace.size()
            );
        }

        private State updated(
            Map<String, Expr> nextBindings,
            Expr nextRepresentative,
            int nextRepresentativeIndex,
            ExprMatcher.RecognitionStrength nextStrength,
            List<String> nextTrace,
            long assemblyWork
        ) {
            // Evaluate all five field identities; mathematical equivalence is
            // insufficient for reusing an execution state.
            boolean unchanged = (nextBindings == bindings)
                & (nextRepresentative == representative)
                & (nextRepresentativeIndex == representativeIndex)
                & (nextStrength == recognitionStrength)
                & (nextTrace == trace);
            if (unchanged) {
                RetainedOperation.work(assemblyWork + 5);
                return this;
            }
            State result = new State(nextBindings,nextRepresentative,nextRepresentativeIndex,nextStrength,nextTrace);
            // Identity checks, State construction and immutable-copy operations. Charge
            // additional copied entries only when the returned owners differ.
            long freezingWork = 8;
            if (result.bindings != nextBindings) freezingWork += 2L + nextBindings.size();
            if (result.trace != nextTrace) freezingWork += 2L + nextTrace.size();
            try (var owned = RetainedOperation.retainCompleted(assemblyWork + freezingWork,
                    this,nextBindings,nextTrace,result)) {
                return result;
            }
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

}
