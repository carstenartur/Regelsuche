package de.regelsuche.benchmark;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.EvaluationTask;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.MacroCandidate;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.PairedEvaluation;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.SearchRun;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.UtilityOutcome;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Paired production best-first utility evaluation for formed TRAIN macros. */
final class CandidateIndependentMacroUtilityEvaluator {
    private final TransformationEngine primitiveEngine;
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();
    private final ExpressionParser expressionParser = new ExpressionParser();
    private final PolynomialNormalFormEquivalenceService polynomialEquivalence =
        new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry());
    private final RationalFunctionNormalFormEquivalenceService rationalEquivalence =
        new RationalFunctionNormalFormEquivalenceService();

    CandidateIndependentMacroUtilityEvaluator(
        Map<String, List<String>> operationRuleIds
    ) {
        CandidateIndependentMacroReplayAdapter replay =
            new CandidateIndependentMacroReplayAdapter(operationRuleIds);
        Set<String> allowed = replay.operationRuleIds().values().stream()
            .flatMap(List::stream)
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new));
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules()
            .stream()
            .filter(rule -> allowed.contains(rule.id()))
            .sorted(Comparator.comparing(RewriteRule::id))
            .toList();
        primitiveEngine = new AstRewriteTransformationEngine(rules, 16, 120);
    }

    PairedEvaluation evaluate(
        EvaluationTask task,
        FormationResult formation
    ) {
        if (task == null || formation == null) {
            throw new NullPointerException("task and formation are required");
        }
        if (formation.status() != FormationStatus.SELECTED) {
            SearchRun empty = SearchRun.notRun(
                task, "candidate formation did not select macros");
            return new PairedEvaluation(
                task.taskId(), UtilityOutcome.CANDIDATE_NOT_FORMED,
                empty, empty, false,
                "paired evaluation requires selected TRAIN macros");
        }

        SearchRun baseline = search(task, primitiveEngine);
        TransformationEngine macroEngine = expression -> {
            List<Transformation> transformations = new ArrayList<>(
                primitiveEngine.transform(expression));
            for (MacroCandidate candidate : formation.macros()) {
                learnedPatternMacro(expression, candidate)
                    .ifPresent(transformations::add);
            }
            return List.copyOf(transformations);
        };
        SearchRun withMacro = search(task, macroEngine);
        UtilityOutcome outcome = utility(baseline, withMacro);
        boolean correctnessRegression = baseline.success()
            && !withMacro.success();
        return new PairedEvaluation(
            task.taskId(), outcome, baseline, withMacro,
            correctnessRegression,
            detail(outcome, baseline, withMacro));
    }

    private Optional<Transformation> learnedPatternMacro(
        String expression,
        MacroCandidate candidate
    ) {
        Expr pattern;
        Expr concrete;
        Expr target;
        try {
            pattern = expressionParser.parseTerm(
                candidate.rule().leftPattern());
            concrete = expressionParser.parseTerm(expression);
            target = expressionParser.parseTerm(
                candidate.rule().rightPattern());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        LinkedHashMap<String, Expr> bindings = new LinkedHashMap<>();
        if (!matchMacroPattern(pattern, concrete, bindings)) {
            return Optional.empty();
        }
        String transformed = ExpressionFormatter.format(
            substitute(target, bindings));
        if (same(expression, transformed)) {
            return Optional.empty();
        }
        return Optional.of(new Transformation(
            candidate.macroId(),
            transformed,
            RewriteKind.NORMALIZE,
            false,
            scorer.score(transformed).weightedTotal()
                - scorer.score(expression).weightedTotal(),
            true,
            candidate.macroId() + ':'
                + canonicalizer.stableHash(expression) + "->"
                + canonicalizer.stableHash(transformed),
            candidate.rule().assumptions()));
    }

    private boolean matchMacroPattern(
        Expr pattern,
        Expr concrete,
        Map<String, Expr> bindings
    ) {
        if (pattern instanceof VariableExpr variable
                && variable.name().matches("[A-Z]")) {
            Expr retained = bindings.get(variable.name());
            if (retained == null) {
                bindings.put(variable.name(), concrete);
                return true;
            }
            return same(
                ExpressionFormatter.format(retained),
                ExpressionFormatter.format(concrete));
        }
        if (pattern instanceof NumberExpr left
                && concrete instanceof NumberExpr right) {
            return Double.compare(left.value(), right.value()) == 0;
        }
        if (pattern instanceof VariableExpr left
                && concrete instanceof VariableExpr right) {
            return left.name().equals(right.name());
        }
        if (pattern instanceof FunctionExpr left
                && concrete instanceof FunctionExpr right) {
            if (!left.name().equals(right.name())
                    || left.arguments().size() != right.arguments().size()) {
                return false;
            }
            for (int index = 0; index < left.arguments().size(); index++) {
                if (!matchMacroPattern(
                        left.arguments().get(index),
                        right.arguments().get(index), bindings)) {
                    return false;
                }
            }
            return true;
        }
        if (!(pattern instanceof BinaryExpr left)
                || !(concrete instanceof BinaryExpr right)) {
            return false;
        }
        if (left.operator() == right.operator()) {
            return matchMacroPattern(left.left(), right.left(), bindings)
                && matchMacroPattern(left.right(), right.right(), bindings);
        }
        if (left.operator() == de.regelsuche.ast.BinaryOperator.ADD
                && right.operator() == de.regelsuche.ast.BinaryOperator.SUB) {
            return matchMacroPattern(left.left(), right.left(), bindings)
                && matchMacroPattern(
                    left.right(), negate(right.right()), bindings);
        }
        return false;
    }

    private Expr negate(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return new NumberExpr(-number.value());
        }
        return new BinaryExpr(
            new NumberExpr(0),
            de.regelsuche.ast.BinaryOperator.SUB,
            expression);
    }

    private Expr substitute(Expr expression, Map<String, Expr> bindings) {
        if (expression instanceof VariableExpr variable) {
            return bindings.getOrDefault(variable.name(), variable);
        }
        if (expression instanceof NumberExpr) {
            return expression;
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(
                function.name(),
                function.arguments().stream()
                    .map(argument -> substitute(argument, bindings))
                    .toList());
        }
        BinaryExpr binary = (BinaryExpr) expression;
        return new BinaryExpr(
            substitute(binary.left(), bindings),
            binary.operator(),
            substitute(binary.right(), bindings));
    }

    private SearchRun search(
        EvaluationTask task,
        TransformationEngine engine
    ) {
        if (sameUnderAssumptions(
                task.source(), task.target(), task.assumptions())) {
            return new SearchRun(
                true,
                task.source(),
                List.of(task.source()),
                List.of(),
                1,
                0,
                false,
                "root already satisfies the target under frozen assumptions");
        }
        TransformationEngine validatedEngine = expression ->
            engine.transform(expression).stream()
                .filter(candidate -> assumptionsCovered(
                    candidate.assumptions(), task.assumptions()))
                .filter(candidate -> stepEquivalent(
                    expression,
                    candidate.transformedExpression(),
                    task.assumptions()))
                .toList();
        SearchHeuristic heuristic = new SearchHeuristic(
            task.maxDepth(),
            task.maxExpandedStates(),
            1,
            task.maxDepth(),
            120,
            120);
        SearchProblem problem = new SearchProblem(
            task.source(),
            validatedEngine,
            scorer,
            canonicalizer,
            heuristic)
            .withTarget(task.target());
        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        SearchState retained = result.reached()
            ? result.reachedState()
            : result.bestState();
        boolean exhausted = result.status() == GoalStatus.STATE_BUDGET
            || result.status() == GoalStatus.CANDIDATE_BUDGET
            || result.status() == GoalStatus.DEPTH_BUDGET;
        return new SearchRun(
            result.reached(),
            result.reached() ? retained.expression() : "",
            retained.path(),
            retained.appliedRuleIds(),
            result.metrics().exploredStates(),
            result.metrics().generatedTransformations(),
            exhausted,
            "production best-first terminal status: " + result.status());
    }

    private boolean stepEquivalent(
        String left,
        String right,
        List<String> assumptions
    ) {
        if (left.contains("/") || right.contains("/")) {
            return rationalEquivalence.evaluate(left, right, assumptions).status()
                == RationalFunctionNormalFormEquivalenceService.Status.CONFIRMED;
        }
        return polynomialEquivalence.areEquivalent(
            substituteEqualities(left, assumptions),
            substituteEqualities(right, assumptions));
    }

    private boolean sameUnderAssumptions(
        String left,
        String right,
        List<String> assumptions
    ) {
        return same(
            substituteEqualities(left, assumptions),
            substituteEqualities(right, assumptions));
    }

    private boolean same(String left, String right) {
        return canonicalizer.canonicalize(left)
            .equals(canonicalizer.canonicalize(right));
    }

    private String substituteEqualities(
        String expression,
        List<String> assumptions
    ) {
        String result = expression;
        for (String assumption : assumptions) {
            int separator = assumption.indexOf('=');
            if (separator < 1 || assumption.contains("!=")
                    || assumption.indexOf('=', separator + 1) >= 0) {
                continue;
            }
            String left = assumption.substring(0, separator).trim();
            String right = assumption.substring(separator + 1).trim();
            if (left.matches("[A-Za-z][A-Za-z0-9_]*")) {
                result = result.replaceAll(
                    "\\b" + Pattern.quote(left) + "\\b",
                    Matcher.quoteReplacement("(" + right + ")"));
            }
        }
        return result;
    }

    private boolean assumptionsCovered(
        List<String> required,
        List<String> available
    ) {
        if (required == null || required.isEmpty()) {
            return true;
        }
        if (available == null || available.isEmpty()) {
            return false;
        }
        Set<String> normalized = new LinkedHashSet<>(
            AssumptionSignature.ofExpressions(available)
                .normalizedAssumptions());
        return normalized.containsAll(
            AssumptionSignature.ofExpressions(required)
                .normalizedAssumptions());
    }

    private UtilityOutcome utility(SearchRun baseline, SearchRun macro) {
        if (baseline.success() && !macro.success()) {
            return UtilityOutcome.CORRECTNESS_REGRESSION;
        }
        if (!baseline.success() && macro.success()) {
            return UtilityOutcome.REACHABILITY_GAIN;
        }
        if (!baseline.success()) {
            return UtilityOutcome.NO_RESULT;
        }
        if (macro.expandedStates() < baseline.expandedStates()
                || macro.ruleIds().size() < baseline.ruleIds().size()) {
            return UtilityOutcome.IMPROVED;
        }
        return UtilityOutcome.NO_IMPROVEMENT;
    }

    private String detail(
        UtilityOutcome outcome,
        SearchRun baseline,
        SearchRun macro
    ) {
        String summary = switch (outcome) {
            case IMPROVED ->
                "macro run reaches the target with lower paired search cost";
            case REACHABILITY_GAIN ->
                "macro run reaches a target missed by the paired baseline";
            case NO_IMPROVEMENT ->
                "both runs reach the target without measured macro improvement";
            case NO_RESULT ->
                "neither paired run reaches the target";
            case CORRECTNESS_REGRESSION ->
                "baseline reaches the target but macro-enabled run does not";
            case CANDIDATE_NOT_FORMED ->
                "TRAIN formation did not produce reusable macros";
        };
        return summary
            + "; baselineExpanded=" + baseline.expandedStates()
            + "; macroExpanded=" + macro.expandedStates();
    }
}
