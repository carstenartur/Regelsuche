package de.regelsuche.inequality;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.equation.LinearEquationSolver;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Solves linear inequalities of the form {@code a*x + b ⋈ c*x + d} by
 * collecting the linear form on each side and dividing by the leading
 * coefficient.
 *
 * <p>When that coefficient is negative the comparator flips (per the
 * standard rule). The resulting {@link Solution} carries the final
 * inequality, the value bound, and the assumptions raised by the
 * intermediate steps.</p>
 */
public final class LinearInequalitySolver {

    private final InequalityRewriteEngine engine = new InequalityRewriteEngine();

    public Optional<Solution> solve(Inequality inequality, String variable) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(variable, "variable");
        Optional<LinearEquationSolver.LinearForm> leftForm =
            LinearEquationSolver.LinearForm.of(inequality.left(), variable);
        Optional<LinearEquationSolver.LinearForm> rightForm =
            LinearEquationSolver.LinearForm.of(inequality.right(), variable);
        if (leftForm.isEmpty() || rightForm.isEmpty()) {
            return Optional.empty();
        }
        double aCoeff = leftForm.get().coefficient() - rightForm.get().coefficient();
        double bConst = rightForm.get().constant() - leftForm.get().constant();

        if (aCoeff == 0.0) {
            // The variable cancels: comparator depends purely on the
            // constants. We do not produce a bound — caller may inspect
            // status to discover the trivial outcome.
            boolean trivial = evaluateTrivial(inequality.comparator(), bConst);
            return Optional.of(new Solution(
                trivial ? Status.TRIVIALLY_TRUE : Status.TRIVIALLY_FALSE,
                inequality,
                null,
                null,
                List.of(),
                List.of()
            ));
        }

        List<InequalityStep> steps = new ArrayList<>();
        List<Assumption> assumptions = new ArrayList<>();

        Comparator comparator = inequality.comparator();
        if (aCoeff < 0) {
            comparator = comparator.flip();
        }
        // Final isolated inequality x ⋈ value.
        double value = bConst / aCoeff;
        Inequality solved = new Inequality(
            new VariableExpr(variable),
            comparator,
            new NumberExpr(value)
        );

        Expr divisor = new NumberExpr(aCoeff);
        Assumption nonZero = Assumption.nonZero(ExpressionFormatter.format(divisor));
        assumptions.add(nonZero);
        String description = aCoeff < 0
            ? "Dividiere beide Seiten durch " + ExpressionFormatter.format(divisor)
                + " — Vergleichszeichen wird gedreht"
            : "Dividiere beide Seiten durch " + ExpressionFormatter.format(divisor);
        steps.add(new InequalityStep(
            "inequality_divide_both_sides",
            solved,
            description,
            List.of(nonZero)
        ));

        return Optional.of(new Solution(
            Status.BOUNDED,
            inequality,
            solved,
            value,
            List.copyOf(steps),
            List.copyOf(assumptions)
        ));
    }

    private static boolean evaluateTrivial(Comparator comparator, double bConst) {
        return switch (comparator) {
            case LT -> 0.0 < bConst;
            case LE -> 0.0 <= bConst;
            case GT -> 0.0 > bConst;
            case GE -> 0.0 >= bConst;
        };
    }

    /** Bridging helper that pairs the rewrite engine with the solver. */
    public InequalityRewriteEngine engine() {
        return engine;
    }

    /** Suppress warning for the literal builder helper. */
    @SuppressWarnings("unused")
    private static Expr literal(double v) {
        return new BinaryExpr(new NumberExpr(0), BinaryOperator.ADD, new NumberExpr(v));
    }

    public record Solution(
        Status status,
        Inequality original,
        Inequality solved,
        Double value,
        List<InequalityStep> steps,
        List<Assumption> assumptions
    ) {
        public Solution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(original, "original");
            steps = List.copyOf(steps == null ? List.of() : steps);
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }

    public enum Status {
        /** Solution is {@code x ⋈ value} for a single bound. */
        BOUNDED,
        /** Variable cancels and the residual constant inequality holds. */
        TRIVIALLY_TRUE,
        /** Variable cancels and the residual constant inequality fails. */
        TRIVIALLY_FALSE
    }
}
