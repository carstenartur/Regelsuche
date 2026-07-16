package de.regelsuche.solver.ir;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.solver.ir.SolverIr.BackendDescriptor;
import de.regelsuche.solver.ir.SolverIr.Obligation;
import de.regelsuche.solver.ir.SolverIr.Relation;
import de.regelsuche.solver.ir.SolverIr.RequestedEvidence;
import de.regelsuche.solver.ir.SolverIr.ResultStatus;
import de.regelsuche.solver.ir.SolverIr.SolverResult;
import de.regelsuche.solver.ir.SolverIr.Theory;
import de.regelsuche.solver.ir.SolverIr.TranslationStatus;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal adapter that submits an equality obligation to Regelsuche search. */
public final class RegelsucheSearchBackend implements SolverBackend {
    private static final BackendDescriptor DESCRIPTOR = new BackendDescriptor(
        "regelsuche-search",
        "1",
        List.of(Theory.REAL_ARITHMETIC),
        List.of(Relation.EQUALS),
        List.of(RequestedEvidence.DECISION),
        true);

    private final CoreExpressionIrAdapter expressions = new CoreExpressionIrAdapter();
    private final SearchHeuristic heuristic;

    public RegelsucheSearchBackend() {
        this(new SearchHeuristic(6, 2_000, 1, 4, 80, 12));
    }

    public RegelsucheSearchBackend(SearchHeuristic heuristic) {
        this.heuristic = Objects.requireNonNull(heuristic, "heuristic");
    }

    @Override
    public BackendDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SolverExecution execute(Obligation obligation) {
        Objects.requireNonNull(obligation, "obligation");
        String left = expressions.render(obligation.goal().left());
        String right = expressions.render(obligation.goal().right());
        Map<String, String> terms = Map.of(
            "goal.left", left,
            "goal.right", right);
        List<String> issues = SolverBackendSupport.issues(
            obligation, DESCRIPTOR, false);
        if (!issues.isEmpty()) {
            return SolverBackendSupport.rejectedExecution(
                obligation, DESCRIPTOR, issues, terms);
        }
        SolverTranslation translation = SolverTranslation.create(
            obligation,
            DESCRIPTOR,
            TranslationStatus.LOSSLESS,
            List.of(),
            terms);
        SolverResult result;
        try {
            SearchProblem problem = new SearchProblem(
                left,
                new AstRewriteTransformationEngine(),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                heuristic)
                .withTarget(SearchTarget.syntaxExact(right));
            GoalSearchResult outcome = new BestFirstSearchStrategy()
                .searchWithDiagnostics(problem);
            ResultStatus status = outcome.reached()
                ? ResultStatus.CONFIRMED : ResultStatus.UNKNOWN;
            String certificate = outcome.reached()
                ? SolverIr.sha256(
                    "path=" + outcome.reachedState().path()
                        + "\nrules=" + outcome.reachedState().appliedRuleIds()
                        + "\nassumptions=" + outcome.reachedState().assumptions())
                : "";
            String message = "goalStatus=" + outcome.status().name()
                + "; exploredStates=" + outcome.metrics().exploredStates()
                + "; generatedTransformations="
                    + outcome.metrics().generatedTransformations()
                + "; reachedDepth="
                    + (outcome.reachedState() == null
                        ? -1 : outcome.reachedState().depth());
            result = SolverResult.create(
                obligation,
                DESCRIPTOR,
                status,
                TranslationStatus.LOSSLESS,
                List.of("BOUNDED_REWRITE_SEARCH", "EXACT_TARGET_MATCH"),
                List.of(),
                message,
                Map.of(),
                certificate);
        } catch (RuntimeException exception) {
            result = SolverResult.create(
                obligation,
                DESCRIPTOR,
                ResultStatus.ERROR,
                TranslationStatus.LOSSLESS,
                List.of("BOUNDED_REWRITE_SEARCH"),
                List.of(),
                exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                Map.of(),
                "");
        }
        return SolverExecution.create(obligation, translation, result);
    }
}
