package de.regelsuche.search.learning;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Small deterministic feature vector derived from one expression AST. */
public record ExpressionFeatures(
    int nodeCount,
    int maxDepth,
    int variableOccurrences,
    int distinctVariables,
    int numericLiterals,
    int additions,
    int subtractions,
    int multiplications,
    int divisions,
    int powers,
    int functions,
    boolean parseable
) {
    public static ExpressionFeatures of(String expression) {
        try {
            return of(new ExpressionParser().parseTerm(expression));
        } catch (IllegalArgumentException exception) {
            return unavailable();
        }
    }

    static ExpressionFeatures of(Expr expression) {
        MutableFeatures features = new MutableFeatures();
        collect(Objects.requireNonNull(expression, "expression"), 1, features);
        return features.freeze(true);
    }

    static ExpressionFeatures unavailable() {
        return new MutableFeatures().freeze(false);
    }

    private static void collect(Expr expression, int depth, MutableFeatures features) {
        features.nodeCount++;
        features.maxDepth = Math.max(features.maxDepth, depth);
        if (expression instanceof VariableExpr variable) {
            features.variableOccurrences++;
            features.variables.add(variable.name());
            return;
        }
        if (expression instanceof NumberExpr) {
            features.numericLiterals++;
            return;
        }
        if (expression instanceof FunctionExpr function) {
            features.functions++;
            function.arguments().forEach(argument -> collect(argument, depth + 1, features));
            return;
        }
        BinaryExpr binary = (BinaryExpr) expression;
        switch (binary.operator()) {
            case ADD -> features.additions++;
            case SUB -> features.subtractions++;
            case MUL -> features.multiplications++;
            case DIV -> features.divisions++;
            case POW -> features.powers++;
        }
        collect(binary.left(), depth + 1, features);
        collect(binary.right(), depth + 1, features);
    }

    private static final class MutableFeatures {
        private int nodeCount;
        private int maxDepth;
        private int variableOccurrences;
        private int numericLiterals;
        private int additions;
        private int subtractions;
        private int multiplications;
        private int divisions;
        private int powers;
        private int functions;
        private final Set<String> variables = new LinkedHashSet<>();

        private ExpressionFeatures freeze(boolean parseable) {
            return new ExpressionFeatures(
                nodeCount,
                maxDepth,
                variableOccurrences,
                variables.size(),
                numericLiterals,
                additions,
                subtractions,
                multiplications,
                divisions,
                powers,
                functions,
                parseable);
        }
    }
}
