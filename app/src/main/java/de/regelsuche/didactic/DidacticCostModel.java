package de.regelsuche.didactic;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.cost.CostModel;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Richer didactic cost model (spec item 1).
 *
 * <p>"A mathematically valid path is not automatically understandable,
 * teachable, elegant, learnable." This cost model goes beyond the
 * existing {@link de.regelsuche.scoring.cost.TeachingFriendlinessCost} by
 * combining the criteria called out in the spec:</p>
 *
 * <ul>
 *   <li><b>Operator complexity</b> — exotic / advanced operators cost more
 *       than +, -, *.</li>
 *   <li><b>Expression depth</b> — deep nesting is mentally expensive.</li>
 *   <li><b>Symbolic overload</b> — many distinct variables in a single
 *       subterm increases mental load.</li>
 *   <li><b>Mental load / size</b> — total operator count, weighted by
 *       depth (deep operators count more than flat ones).</li>
 *   <li><b>Large coefficients</b> — penalised mildly; school-book
 *       expressions tend to keep numbers small.</li>
 *   <li><b>Difficulty cap</b> — expressions whose depth or operator
 *       complexity exceed the configured {@link DifficultyLevel} are
 *       penalised, not rejected.</li>
 *   <li><b>Profile bias</b> — {@link PedagogyProfile#CONCISE} weights
 *       size more heavily, {@link PedagogyProfile#VERY_DETAILED} weights
 *       it less.</li>
 * </ul>
 *
 * <p>The "new concepts per step", "sprunghaftigkeit" and "repetition of
 * similar steps" criteria are path-level properties, not single-expression
 * properties; they live on {@link de.regelsuche.search.TeachingPathScorer}
 * and on path-level analytics, not here. This class is the
 * <em>node</em>-level cost that plugs into the existing search
 * machinery.</p>
 */
public final class DidacticCostModel implements CostModel {

    private final DifficultyLevel level;
    private final PedagogyProfile profile;

    public DidacticCostModel() {
        this(DifficultyLevel.MITTELSTUFE, PedagogyProfile.SCHOOL);
    }

    public DidacticCostModel(DifficultyLevel level, PedagogyProfile profile) {
        this.level = Objects.requireNonNull(level, "level");
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    public DifficultyLevel level() {
        return level;
    }

    public PedagogyProfile profile() {
        return profile;
    }

    @Override
    public String id() {
        return "didactic";
    }

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            // Fall back to the score-based summary so the cost stays comparable.
            return Math.max(0, score.operatorCount() * 2 + score.nestingDepth() * 3);
        }
        int operators        = operatorCount(parsedAst);
        int depth            = depth(parsedAst);
        int operatorWeight   = operatorComplexity(parsedAst);
        int symbolicOverload = symbolicOverload(parsedAst);
        int largeCoeffs      = largeCoefficientPenalty(parsedAst);
        int divisionCost     = divisionPenalty(parsedAst);

        // Mental load: weighted operator count, made non-linear in depth so
        // a step that adds depth costs more than a step that adds width.
        int mentalLoad = operators + operatorWeight + depth * depth;

        int total = mentalLoad + symbolicOverload + largeCoeffs + divisionCost;

        // Difficulty cap: depth above the level budget is penalised.
        int depthBudget = depthBudgetFor(level);
        if (depth > depthBudget) {
            total += (depth - depthBudget) * 4;
        }

        // Profile bias: CONCISE punishes size harder, VERY_DETAILED relaxes it.
        return switch (profile) {
            case CONCISE       -> total + operators;            // +size weight
            case VERY_DETAILED -> Math.max(0, total - operators / 2);
            case ELEGANT       -> total + asymmetryPenalty(parsedAst);
            case EXAM_FRIENDLY -> total + divisionCost; // double-penalise divisions
            case SCHOOL        -> total;
        };
    }

    // -------- pure helpers --------

    private static int operatorCount(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + operatorCount(binary.left()) + operatorCount(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 1;
            for (Expr arg : function.arguments()) {
                total += operatorCount(arg);
            }
            return total;
        }
        return 0;
    }

    private static int depth(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + Math.max(depth(binary.left()), depth(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            int max = 0;
            for (Expr arg : function.arguments()) {
                max = Math.max(max, depth(arg));
            }
            return 1 + max;
        }
        return 0;
    }

    private static int operatorComplexity(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            int self = switch (binary.operator()) {
                case ADD, SUB -> 1;
                case MUL      -> 1;
                case DIV      -> 3;
                case POW      -> 2;
            };
            return self + operatorComplexity(binary.left()) + operatorComplexity(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int self = switch (function.name()) {
                case "log", "ln", "exp"  -> 4;
                case "sin", "cos", "tan" -> 3;
                case "sqrt"              -> 3;
                case "abs"               -> 2;
                default                  -> 2;
            };
            int total = self;
            for (Expr arg : function.arguments()) {
                total += operatorComplexity(arg);
            }
            return total;
        }
        return 0;
    }

    private static int symbolicOverload(Expr expression) {
        Set<String> variables = new HashSet<>();
        collectVariables(expression, variables);
        // 0, 1, 2 distinct vars are fine; beyond that we add 2 per extra var.
        int extra = variables.size() - 2;
        return Math.max(0, extra) * 2;
    }

    private static void collectVariables(Expr expression, Set<String> sink) {
        if (expression instanceof VariableExpr variable) {
            sink.add(variable.name());
        } else if (expression instanceof BinaryExpr binary) {
            collectVariables(binary.left(), sink);
            collectVariables(binary.right(), sink);
        } else if (expression instanceof FunctionExpr function) {
            for (Expr arg : function.arguments()) {
                collectVariables(arg, sink);
            }
        }
    }

    private static int largeCoefficientPenalty(Expr expression) {
        if (expression instanceof NumberExpr number) {
            double abs = Math.abs(number.value());
            if (abs > 1000) {
                return 4;
            }
            if (abs > 100) {
                return 2;
            }
            return 0;
        }
        if (expression instanceof BinaryExpr binary) {
            return largeCoefficientPenalty(binary.left()) + largeCoefficientPenalty(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 0;
            for (Expr arg : function.arguments()) {
                total += largeCoefficientPenalty(arg);
            }
            return total;
        }
        return 0;
    }

    private static int divisionPenalty(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            int self = binary.operator() == BinaryOperator.DIV ? 3 : 0;
            return self + divisionPenalty(binary.left()) + divisionPenalty(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 0;
            for (Expr arg : function.arguments()) {
                total += divisionPenalty(arg);
            }
            return total;
        }
        return 0;
    }

    private static int asymmetryPenalty(Expr expression) {
        if (expression instanceof BinaryExpr binary
            && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.MUL)) {
            int left = depth(binary.left());
            int right = depth(binary.right());
            return Math.abs(left - right)
                + asymmetryPenalty(binary.left())
                + asymmetryPenalty(binary.right());
        }
        if (expression instanceof BinaryExpr binary) {
            return asymmetryPenalty(binary.left()) + asymmetryPenalty(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 0;
            for (Expr arg : function.arguments()) {
                total += asymmetryPenalty(arg);
            }
            return total;
        }
        return 0;
    }

    private static int depthBudgetFor(DifficultyLevel level) {
        return switch (level) {
            case GRUNDSCHULE  -> 2;
            case MITTELSTUFE  -> 4;
            case OBERSTUFE    -> 6;
            case UNIVERSITAET -> 8;
            case EXPERTE      -> Integer.MAX_VALUE;
        };
    }
}
