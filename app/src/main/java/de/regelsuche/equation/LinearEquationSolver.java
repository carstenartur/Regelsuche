package de.regelsuche.equation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Solves linear equations of the form {@code a*x + b = c*x + d} by collecting
 * the linear coefficients of one chosen variable on each side, then applying
 * the dedicated equation rewrites (subtract / divide on both sides) to
 * isolate the variable.
 *
 * <p>Side conditions raised during the symbolic solution (in particular the
 * {@code a - c != 0} guard for the final division) are surfaced as
 * {@link Assumption}s on the produced {@link Solution}.</p>
 *
 * <p>The solver is deliberately narrow: it only handles equations whose
 * algebraic shape is genuinely linear in the target variable. Non-linear
 * equations (e.g. with a {@code x^2} or {@code sin(x)} term) yield
 * {@link Optional#empty()} rather than producing an incorrect rewrite.</p>
 */
public final class LinearEquationSolver {

    public Optional<Solution> solve(Equation equation, String variable) {
        Objects.requireNonNull(equation, "equation");
        Objects.requireNonNull(variable, "variable");
        Optional<LinearForm> leftForm = LinearForm.of(equation.left(), variable);
        Optional<LinearForm> rightForm = LinearForm.of(equation.right(), variable);
        if (leftForm.isEmpty() || rightForm.isEmpty()) {
            return Optional.empty();
        }
        double aCoeff = leftForm.get().coefficient() - rightForm.get().coefficient();
        double bConst = rightForm.get().constant() - leftForm.get().constant();

        List<EquationStep> steps = new ArrayList<>();
        List<Assumption> assumptions = new ArrayList<>();

        // Subtract the right-hand side variable term (if any) so the variable
        // only appears on the left.
        Expr currentLeft = equation.left();
        Expr currentRight = equation.right();
        if (rightForm.get().coefficient() != 0.0) {
            Expr term = makeTerm(rightForm.get().coefficient(), variable);
            Equation next = new Equation(
                new BinaryExpr(currentLeft, BinaryOperator.SUB, term),
                new BinaryExpr(currentRight, BinaryOperator.SUB, term)
            );
            steps.add(new EquationStep(
                "equation_subtract_both_sides",
                next,
                "Subtrahiere " + ExpressionFormatter.format(term) + " auf beiden Seiten",
                List.of()
            ));
            currentLeft = next.left();
            currentRight = next.right();
        }

        // Subtract the left-hand-side constant so the variable side becomes a*x.
        if (leftForm.get().constant() != 0.0) {
            Expr constant = number(leftForm.get().constant());
            Equation next = new Equation(
                new BinaryExpr(currentLeft, BinaryOperator.SUB, constant),
                new BinaryExpr(currentRight, BinaryOperator.SUB, constant)
            );
            steps.add(new EquationStep(
                "equation_subtract_both_sides",
                next,
                "Subtrahiere " + ExpressionFormatter.format(constant) + " auf beiden Seiten",
                List.of()
            ));
        }

        // At this point the equation is a*x = b.
        Equation isolated = new Equation(
            makeTerm(aCoeff, variable),
            number(bConst)
        );

        if (aCoeff == 0.0) {
            if (bConst == 0.0) {
                return Optional.of(new Solution(Status.IDENTITY, equation, isolated, null, List.copyOf(steps), List.copyOf(assumptions)));
            }
            return Optional.of(new Solution(Status.NO_SOLUTION, equation, isolated, null, List.copyOf(steps), List.copyOf(assumptions)));
        }

        // Divide both sides by aCoeff.
        Expr divisor = number(aCoeff);
        Equation divided = new Equation(
            new VariableExpr(variable),
            number(bConst / aCoeff)
        );
        Assumption divisorAssumption = Assumption.nonZero(ExpressionFormatter.format(divisor));
        assumptions.add(divisorAssumption);
        steps.add(new EquationStep(
            "equation_divide_both_sides",
            divided,
            "Dividiere beide Seiten durch " + ExpressionFormatter.format(divisor),
            List.of(divisorAssumption)
        ));

        return Optional.of(new Solution(
            Status.UNIQUE,
            equation,
            divided,
            bConst / aCoeff,
            List.copyOf(steps),
            List.copyOf(assumptions)
        ));
    }

    private static Expr makeTerm(double coefficient, String variable) {
        if (coefficient == 0.0) {
            return new NumberExpr(0);
        }
        if (coefficient == 1.0) {
            return new VariableExpr(variable);
        }
        return new BinaryExpr(new NumberExpr(coefficient), BinaryOperator.MUL, new VariableExpr(variable));
    }

    private static Expr number(double value) {
        return new NumberExpr(value);
    }

    /** Outcome of {@link LinearEquationSolver#solve}. */
    public record Solution(
        Status status,
        Equation original,
        Equation solved,
        Double value,
        List<EquationStep> steps,
        List<Assumption> assumptions
    ) {
        public Solution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(solved, "solved");
            steps = List.copyOf(steps == null ? List.of() : steps);
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }

    public enum Status {
        /** Single solution: {@code x = value}. */
        UNIQUE,
        /** Both sides reduce to the same expression — every {@code x} satisfies it. */
        IDENTITY,
        /** Coefficients cancel but constants differ — no solution. */
        NO_SOLUTION
    }

    /**
     * Internal helper: linear form {@code coefficient * x + constant} extracted
     * from an expression tree. Returns {@link Optional#empty()} when the
     * expression is not linear in {@code variable}.
     */
    public record LinearForm(double coefficient, double constant) {
        public static Optional<LinearForm> of(Expr expr, String variable) {
            if (expr instanceof NumberExpr number) {
                return Optional.of(new LinearForm(0.0, number.value()));
            }
            if (expr instanceof VariableExpr variableExpr) {
                if (variableExpr.name().equals(variable)) {
                    return Optional.of(new LinearForm(1.0, 0.0));
                }
                return Optional.empty();
            }
            if (expr instanceof BinaryExpr binary) {
                Optional<LinearForm> left = of(binary.left(), variable);
                Optional<LinearForm> right = of(binary.right(), variable);
                if (left.isEmpty() || right.isEmpty()) {
                    return Optional.empty();
                }
                LinearForm l = left.get();
                LinearForm r = right.get();
                return switch (binary.operator()) {
                    case ADD -> Optional.of(new LinearForm(l.coefficient + r.coefficient, l.constant + r.constant));
                    case SUB -> Optional.of(new LinearForm(l.coefficient - r.coefficient, l.constant - r.constant));
                    case MUL -> {
                        if (l.coefficient == 0.0) {
                            // l is a pure constant, multiply r by it
                            yield Optional.of(new LinearForm(l.constant * r.coefficient, l.constant * r.constant));
                        }
                        if (r.coefficient == 0.0) {
                            yield Optional.of(new LinearForm(r.constant * l.coefficient, r.constant * l.constant));
                        }
                        // x * x or similar — non-linear.
                        yield Optional.empty();
                    }
                    case DIV -> {
                        if (r.coefficient != 0.0 || r.constant == 0.0) {
                            // Division by anything containing the variable, or by zero, is rejected.
                            yield Optional.empty();
                        }
                        yield Optional.of(new LinearForm(l.coefficient / r.constant, l.constant / r.constant));
                    }
                    case POW -> Optional.empty();
                };
            }
            // Function calls (sin, cos, ...) are non-linear in the variable.
            return Optional.empty();
        }
    }
}
