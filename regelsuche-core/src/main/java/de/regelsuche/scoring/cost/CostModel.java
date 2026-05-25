package de.regelsuche.scoring.cost;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Pluggable cost function used by goal-directed search strategies.
 *
 * <p>A {@link CostModel} maps a candidate result (its canonical AST,
 * canonical string and pre-computed {@link ExpressionScore}) onto a single
 * non-negative integer cost: lower is better. The score-driven
 * {@code priority(...)} computations in {@link
 * de.regelsuche.search.strategy.BestFirstSearchStrategy} and
 * {@link de.regelsuche.search.strategy.AStarSearchStrategy} consult the
 * model attached to the {@link de.regelsuche.search.strategy.SearchProblem}
 * to obtain the "g(n)" term, so different {@link TransformationGoal}s end
 * up steering the same search machinery toward different mathematical
 * targets — factored form, numerically stable form, teaching-friendly form,
 * and so on.</p>
 *
 * <p>Implementations must be pure (no side effects, no per-instance state
 * that depends on call order) so that cost values are reproducible across
 * runs and so that the search remains deterministic.</p>
 */
public interface CostModel {

    /**
     * Cost of {@code expression}. Smaller is better. The {@code parsedAst}
     * argument is supplied as a convenience so implementations do not have
     * to re-parse the input — if a model only needs the score, it can
     * ignore {@code parsedAst}.
     */
    int cost(String expression, Expr parsedAst, ExpressionScore score);

    /**
     * Stable, human-readable identifier of this cost model, e.g.
     * {@code "operator-count"}. Used in JSON exports and the UI dropdown.
     */
    String id();

    /**
     * Convenience: parse {@code expression} once and delegate to
     * {@link #cost(String, Expr, ExpressionScore)}. Returns {@link
     * Integer#MAX_VALUE} for syntactically invalid input so the caller's
     * comparator treats the broken state as the worst possible candidate
     * without throwing.
     */
    default int cost(String expression, ExpressionCanonicalizer canonicalizer, ExpressionScore score) {
        try {
            Expr ast = new ExpressionParser()
                .parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
            return cost(expression, ast, score);
        } catch (RuntimeException ex) {
            return Integer.MAX_VALUE;
        }
    }
}
