package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Small shared AST helpers used across the hypothesis generators. Treats every
 * subtree as a potential mathematical atom: parsing, formatting, flattening of
 * additive/multiplicative chains and structural substitution of a chosen atom
 * by a placeholder.
 */
final class HypothesisExpressions {

    private static final ExpressionParser PARSER = new ExpressionParser();

    private HypothesisExpressions() {
    }

    /** Parses a term, returning empty when it cannot be parsed. */
    static Optional<Expr> parseTerm(String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PARSER.parseTerm(expression));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Parses an equation, returning empty when it is not a parseable equation. */
    static Optional<Equation> parseEquation(String expression) {
        if (expression == null || !expression.contains("=")) {
            return Optional.empty();
        }
        try {
            return Optional.of(PARSER.parseEquation(expression));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Formats an AST node back into its infix string form. */
    static String format(Expr expr) {
        return ExpressionFormatter.format(expr);
    }

    /** @return {@code true} when the node is composite (binary or function). */
    static boolean isComposite(Expr expr) {
        return expr instanceof BinaryExpr || expr instanceof FunctionExpr;
    }

    /** @return {@code true} when the node is a candidate atom (composite or variable). */
    static boolean isAtomCandidate(Expr expr) {
        return isComposite(expr) || expr instanceof VariableExpr;
    }

    /** A signed additive term: {@code positive == false} marks a subtracted term. */
    record SignedTerm(boolean positive, Expr expr) {
    }

    /** Flattens an additive ({@code +}/{@code -}) chain into signed summands. */
    static List<SignedTerm> additiveTerms(Expr root) {
        List<SignedTerm> out = new ArrayList<>();
        flattenAdditive(root, true, out);
        return out;
    }

    private static void flattenAdditive(Expr expr, boolean positive, List<SignedTerm> out) {
        if (expr instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            flattenAdditive(binary.left(), positive, out);
            flattenAdditive(binary.right(), positive, out);
        } else if (expr instanceof BinaryExpr binary && binary.operator() == BinaryOperator.SUB) {
            flattenAdditive(binary.left(), positive, out);
            flattenAdditive(binary.right(), !positive, out);
        } else {
            out.add(new SignedTerm(positive, expr));
        }
    }

    /** Flattens a multiplicative ({@code *}) chain into its factors. */
    static List<Expr> multiplicativeFactors(Expr root) {
        List<Expr> out = new ArrayList<>();
        flattenMultiplicative(root, out);
        return out;
    }

    private static void flattenMultiplicative(Expr expr, List<Expr> out) {
        if (expr instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            flattenMultiplicative(binary.left(), out);
            flattenMultiplicative(binary.right(), out);
        } else {
            out.add(expr);
        }
    }

    /**
     * Replaces every occurrence of the atom (matched by its canonical form) with
     * a {@link VariableExpr} placeholder, leaving the rest of the tree intact.
     */
    static Expr replaceAtom(Expr node, String atomCanonical, String placeholder) {
        if (format(node).equals(atomCanonical)) {
            return new VariableExpr(placeholder);
        }
        if (node instanceof BinaryExpr binary) {
            return new BinaryExpr(
                    replaceAtom(binary.left(), atomCanonical, placeholder),
                    binary.operator(),
                    replaceAtom(binary.right(), atomCanonical, placeholder));
        }
        if (node instanceof FunctionExpr function) {
            List<Expr> arguments = new ArrayList<>(function.arguments().size());
            for (Expr argument : function.arguments()) {
                arguments.add(replaceAtom(argument, atomCanonical, placeholder));
            }
            return new FunctionExpr(function.name(), arguments);
        }
        return node;
    }

    /** Formats a {@code double}, preferring an integer rendering when exact. */
    static String formatNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** @return {@code true} when the node is a zero literal. */
    static boolean isZero(Expr expr) {
        return expr instanceof NumberExpr number && number.value() == 0.0;
    }
}
