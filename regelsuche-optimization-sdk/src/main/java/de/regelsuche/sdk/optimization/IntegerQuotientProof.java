package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;

/** Independent exact divisibility proof: N / D = Q follows from N = D * Q over integers
 * and an established D != 0. Truncating quotients remain opaque polynomial atoms.
 * The caller must establish exact arithmetic (BigInteger or both primitive trace range gates).
 */
final class IntegerQuotientProof {
    private final EstablishedNumericFacts facts;
    private final VerificationWork work;

    IntegerQuotientProof(EstablishedNumericFacts facts, VerificationWork work) {
        this.facts = facts;
        this.work = work;
    }
    boolean equivalent(Expr source, Expr target, NumericKind kind) {
        return quotient(source, target, kind) || quotient(target, source, kind);
    }
    private boolean quotient(Expr division, Expr quotient, NumericKind kind) {
        work.charge(1);
        if (JavaExpressions.operationOf(division).orElse(null) != NumericOperation.DIVIDE
                || JavaExpressions.resultKind(division) != kind) return false;
        var operands = JavaExpressions.operands(division);
        Expr divisor = operands.get(1);
        if (!facts.nonzero(divisor)) return false;
        Expr product = JavaExpressions.operation(kind, NumericOperation.MULTIPLY, divisor, quotient);
        return new PolynomialProof(kind, true, false, work).equivalent(operands.getFirst(), product);
    }
}
