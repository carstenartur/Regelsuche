package de.regelsuche.api.searchgraph;

/**
 * Kind of object that a search-graph node represents.
 *
 * <p>Originally nodes only ever held a single algebraic {@link #TERM};
 * with the integration of the math-domain solvers
 * ({@code LinearEquationSolver}, {@code LinearInequalitySolver},
 * derivative rewrites, matrix demos) the graph also needs to carry
 * equations, inequalities, vectors and matrices as first-class nodes so
 * that the visual search graph, replay overlay and exports can render
 * them properly — instead of squeezing everything through the "term"
 * code path.</p>
 *
 * <p>The enum is intentionally small and stable; renderers and JSON
 * serializers branch on it directly. New domains should reuse one of
 * the existing entries or add a new one explicitly.</p>
 */
public enum SearchExpression {
    /** Plain algebraic expression such as {@code 2*x + 3}. */
    TERM,
    /** Equation of two terms: {@code lhs = rhs}. */
    EQUATION,
    /** Inequality of two terms: {@code lhs ⋈ rhs}. */
    INEQUALITY,
    /** Vector literal: {@code [v1, v2, ...]}. */
    VECTOR,
    /** Matrix literal: {@code [[a, b], [c, d], ...]}. */
    MATRIX;

    /**
     * Cheap heuristic classifier used by adapters that only have access
     * to the rendered string form of an expression. It looks at the
     * structural separators ({@code =}, {@code <}/{@code >}, opening
     * matrix bracket {@code [[}) and defaults to {@link #TERM}.
     *
     * <p>The classifier is intentionally syntactic: it does not parse
     * the expression. Callers that need precise typing should construct
     * the {@link SearchExpression} explicitly when they know the source
     * domain.</p>
     */
    public static SearchExpression classify(String expression) {
        if (expression == null) {
            return TERM;
        }
        String trimmed = expression.trim();
        if (trimmed.startsWith("[[") || trimmed.startsWith("\\begin{bmatrix}")) {
            return MATRIX;
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && !trimmed.contains("=")) {
            return VECTOR;
        }
        // Inequality comparators take precedence over plain '=' because
        // '<=' / '>=' contain it too.
        if (trimmed.contains("<") || trimmed.contains(">")) {
            return INEQUALITY;
        }
        if (trimmed.contains("=")) {
            return EQUATION;
        }
        return TERM;
    }
}
