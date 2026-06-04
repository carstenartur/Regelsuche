package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Locale;

/** Assumption-aware exp/log inverse under strictly positive argument. */
public final class ExpLogInverseOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.log.basic.exp_log_inverse";
    private static final String PACK_ID = "sympy-log-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        if (!(root instanceof FunctionExpr exp) || !"exp".equals(exp.name()) || exp.arguments().size() != 1) {
            return List.of();
        }
        Expr expArg = exp.argument();
        if (!(expArg instanceof FunctionExpr log) || !"log".equals(log.name()) || log.arguments().size() != 1) {
            return List.of();
        }
        String argument = ExpressionFormatter.format(log.argument());
        if (argument.isBlank() || argument.toLowerCase(Locale.ROOT).contains("unknown")) {
            return List.of();
        }
        return List.of(new Transformation(
            RULE_ID,
            argument,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
            List.of(argument + " > 0"),
            PACK_ID,
            LICENSE
        ));
    }
}
