package de.regelsuche.equation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Engine that applies first-class equation transformations: add a term on
 * both sides, multiply by a non-zero term on both sides, and apply an
 * injective function to both sides.
 *
 * <p>Implemented as dedicated semantics rather than ordinary term
 * rewrites — those would lose the "do the same on both sides" symmetry and
 * could not enforce the {@code c != 0} side condition on multiplication.</p>
 */
public final class EquationRewriteEngine {
    private final List<EquationRule> rules;

    public EquationRewriteEngine() {
        this(List.of(new AddBothSidesRule(), new MultiplyBothSidesRule(), new ApplyInjectiveFunctionRule()));
    }

    public EquationRewriteEngine(List<EquationRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<EquationRule> rules() {
        return rules;
    }

    public List<EquationStep> step(Equation equation, EquationRewriteContext context) {
        List<EquationStep> all = new ArrayList<>();
        for (EquationRule rule : rules) {
            all.addAll(rule.apply(equation, context));
        }
        return Collections.unmodifiableList(all);
    }

    /** {@code a = b ⇒ a + c = b + c}. */
    public static final class AddBothSidesRule implements EquationRule {
        @Override
        public String id() {
            return "equation_add_both_sides";
        }

        @Override
        public String description() {
            return "Auf beiden Seiten denselben Term addieren";
        }

        @Override
        public List<EquationStep> apply(Equation equation, EquationRewriteContext context) {
            List<EquationStep> steps = new ArrayList<>();
            for (Expr operand : context.candidateOperands()) {
                BinaryExpr left = new BinaryExpr(equation.left(), BinaryOperator.ADD, operand);
                BinaryExpr right = new BinaryExpr(equation.right(), BinaryOperator.ADD, operand);
                steps.add(new EquationStep(
                    id(),
                    new Equation(left, right),
                    "Addiere " + ExpressionFormatter.format(operand) + " auf beiden Seiten",
                    List.of()
                ));
            }
            return steps;
        }
    }

    /** {@code a = b ⇒ a*c = b*c} with the assumption {@code c != 0}. */
    public static final class MultiplyBothSidesRule implements EquationRule {
        @Override
        public String id() {
            return "equation_multiply_both_sides";
        }

        @Override
        public String description() {
            return "Beide Seiten mit demselben (von 0 verschiedenen) Faktor multiplizieren";
        }

        @Override
        public List<EquationStep> apply(Equation equation, EquationRewriteContext context) {
            List<EquationStep> steps = new ArrayList<>();
            for (Expr operand : context.candidateOperands()) {
                if (operand instanceof NumberExpr numberExpr && numberExpr.value() == 0.0) {
                    // Multiplying by zero collapses to 0 = 0, which is unsound.
                    continue;
                }
                BinaryExpr left = new BinaryExpr(equation.left(), BinaryOperator.MUL, operand);
                BinaryExpr right = new BinaryExpr(equation.right(), BinaryOperator.MUL, operand);
                String operandString = ExpressionFormatter.format(operand);
                steps.add(new EquationStep(
                    id(),
                    new Equation(left, right),
                    "Multipliziere beide Seiten mit " + operandString,
                    List.of(Assumption.nonZero(operandString))
                ));
            }
            return steps;
        }
    }

    /**
     * {@code a = b ⇒ f(a) = f(b)} when {@code f} is in the injective list.
     * Sound for any injective {@code f} (e.g. {@code exp}); non-injective
     * functions like squaring are <em>not</em> emitted because they would
     * only be sound under additional assumptions.
     */
    public static final class ApplyInjectiveFunctionRule implements EquationRule {
        @Override
        public String id() {
            return "equation_apply_injective_function";
        }

        @Override
        public String description() {
            return "Auf beide Seiten eine injektive Funktion anwenden (z.B. exp)";
        }

        @Override
        public List<EquationStep> apply(Equation equation, EquationRewriteContext context) {
            List<EquationStep> steps = new ArrayList<>();
            for (String name : context.injectiveFunctions()) {
                Expr left = new FunctionExpr(name, equation.left());
                Expr right = new FunctionExpr(name, equation.right());
                steps.add(new EquationStep(
                    id() + ":" + name,
                    new Equation(left, right),
                    "Wende " + name + "(·) auf beide Seiten an",
                    List.of()
                ));
            }
            return steps;
        }
    }
}
