package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.math.BigInteger;

/** Java's signed remainder coincides with low bits only under a proved sign premise. */
final class NonnegativeRemainderProof {
    private final EstablishedNumericFacts facts;
    private final VerificationWork work;
    NonnegativeRemainderProof(EstablishedNumericFacts facts, VerificationWork work) { this.facts=facts; this.work=work; }
    boolean equivalent(Expr a, Expr b, NumericKind kind) { return directed(a,b,kind) || directed(b,a,kind); }
    private boolean directed(Expr a, Expr b, NumericKind kind) {
        work.charge(1);
        if (JavaExpressions.operationOf(a).orElse(null)!=NumericOperation.REMAINDER
                || JavaExpressions.operationOf(b).orElse(null)!=NumericOperation.AND
                || JavaExpressions.resultKind(a)!=kind || JavaExpressions.resultKind(b)!=kind) return false;
        var source=JavaExpressions.operands(a); var target=JavaExpressions.operands(b);
        if (!JavaExpressions.isLiteral(source.get(1)) || !facts.nonnegative(source.getFirst())) return false;
        BigInteger divisor=SemanticChecker.integer(JavaExpressions.literalValue(source.get(1)));
        if (divisor.signum()<=0 || divisor.bitCount()!=1) return false;
        for (int i=0;i<2;i++) if (target.get(i).equals(source.getFirst()) && JavaExpressions.isLiteral(target.get(1-i))
                && SemanticChecker.integer(JavaExpressions.literalValue(target.get(1-i))).equals(divisor.subtract(BigInteger.ONE))) return true;
        return false;
    }
}
