package de.regelsuche.demo;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.calculus.Differentiator;
import de.regelsuche.equation.LinearEquationSolver;
import de.regelsuche.inequality.Comparator;
import de.regelsuche.inequality.Inequality;
import de.regelsuche.inequality.LinearInequalitySolver;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.rules.TrigonometricRules;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Curated catalogue of the four "killer-app" math domain demos defined by
 * the project's next development stage:
 *
 * <ol>
 *   <li>linear equation: {@code x + 3 = 7 -> x = 4},</li>
 *   <li>derivative (power rule): {@code d/dx x^3 -> 3*x^2},</li>
 *   <li>inequality with sign flip: {@code -2*x < 4 -> x > -2},</li>
 *   <li>trigonometric identity: {@code 1 - sin(x)^2 -> cos(x)^2}.</li>
 * </ol>
 *
 * <p>This service is intentionally stand-alone and does not run through the
 * existing search-based {@link DemoService}: those demos rely on pattern
 * rewrite rules with cost-driven search, whereas the math-domain demos
 * exercise dedicated semantic engines ({@link LinearEquationSolver},
 * {@link LinearInequalitySolver}, {@link Differentiator}).</p>
 */
public final class MathDomainDemos {

    private final ExpressionParser parser = new ExpressionParser();
    private final LinearEquationSolver equationSolver = new LinearEquationSolver();
    private final LinearInequalitySolver inequalitySolver = new LinearInequalitySolver();
    private final Differentiator differentiator = new Differentiator();

    /** Run {@code x + 3 = 7 -> x = 4} and surface the solver outcome. */
    public Result linearEquation() {
        Equation equation = parser.parseEquation("x + 3 = 7");
        Optional<LinearEquationSolver.Solution> solution = equationSolver.solve(equation, "x");
        if (solution.isEmpty()) {
            throw new IllegalStateException("Linear equation demo failed to solve");
        }
        LinearEquationSolver.Solution solved = solution.get();
        return new Result(
            "linear-equation",
            "Lineare Gleichung",
            ExpressionFormatter.format(equation),
            ExpressionFormatter.format(solved.solved()),
            solved.assumptions()
        );
    }

    /** Run {@code d/dx x^3 -> 3 * x^2}. */
    public Result derivativePowerRule() {
        Expr input = parser.parseTerm("x^3");
        Expr derivative = differentiator.differentiate(input, "x");
        return new Result(
            "derivative-power",
            "Ableitung – Potenzregel",
            "d/dx " + ExpressionFormatter.format(input),
            ExpressionFormatter.format(derivative),
            List.of()
        );
    }

    /** Run {@code -2*x < 4 -> x > -2}. */
    public Result inequalitySignFlip() {
        Expr left = parser.parseTerm("-2*x");
        Expr right = parser.parseTerm("4");
        Inequality inequality = new Inequality(left, Comparator.LT, right);
        Optional<LinearInequalitySolver.Solution> solution = inequalitySolver.solve(inequality, "x");
        if (solution.isEmpty() || solution.get().solved() == null) {
            throw new IllegalStateException("Inequality demo failed to solve");
        }
        LinearInequalitySolver.Solution solved = solution.get();
        return new Result(
            "inequality-sign-flip",
            "Ungleichung mit negativem Faktor",
            inequality.formatted(),
            solved.solved().formatted(),
            solved.assumptions()
        );
    }

    /** Run {@code 1 - sin(x)^2 -> cos(x)^2} via the trig rule set. */
    public Result trigIdentity() {
        Expr input = parser.parseTerm("1 - sin(x)^2");
        RewriteRule rule = TrigonometricRules.rules().stream()
            .filter(r -> "trig_one_minus_sin_squared".equals(r.id()))
            .findFirst()
            .orElseThrow();
        if (!rule.matches(input)) {
            throw new IllegalStateException("Trigonometric demo rule failed to match");
        }
        Expr rewritten = rule.apply(input);
        return new Result(
            "trig-pythagorean",
            "Trigonometrische Identität",
            ExpressionFormatter.format(input),
            ExpressionFormatter.format(rewritten),
            List.of()
        );
    }

    /** Result of one math-domain demo. */
    public record Result(
        String id,
        String title,
        String inputExpression,
        String resultExpression,
        List<Assumption> assumptions
    ) {
        public Result {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(inputExpression, "inputExpression");
            Objects.requireNonNull(resultExpression, "resultExpression");
            assumptions = List.copyOf(assumptions == null ? List.of() : assumptions);
        }
    }
}
