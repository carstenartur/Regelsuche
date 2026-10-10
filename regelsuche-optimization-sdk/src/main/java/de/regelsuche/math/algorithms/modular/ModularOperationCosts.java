package de.regelsuche.math.algorithms.modular;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;

/** Shared static ranking estimates for the modular and Java computation backends, not timing claims. */
public final class ModularOperationCosts {
    public static final long MULTIPLY_WORK = 10;
    public static final long POWER_FALLBACK_WORK = 1_000;

    private ModularOperationCosts() {}

    /** Include setup/conversion work so a small literal power can compete with its multiplication chain. */
    public static long powerWork(Expr exponent) {
        if (exponent instanceof NumberExpr number && number.value().isInteger() && number.value().signum() >= 0)
            return 32L + 2L * number.value().numerator().bitLength();
        return POWER_FALLBACK_WORK;
    }
}
