package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.math.BigInteger;
import java.util.List;

/** Finds retained inverses of bounded powers; the independent checker proves the product identity. */
final class ModularInverseCandidates {
    private ModularInverseCandidates() { }
    static Expr replacement(Expr node,List<Expr> nodes,VerificationWork work) {
        if(JavaExpressions.operationOf(node).orElse(null)!=NumericOperation.MOD_INVERSE) return node;
        var source=JavaExpressions.operands(node);
        for(Expr known:nodes) {
            work.charge(1);
            if(known.equals(node) || JavaExpressions.operationOf(known).orElse(null)!=NumericOperation.MOD_INVERSE) continue;
            var args=JavaExpressions.operands(known);
            if(!source.get(1).equals(args.get(1))) continue;
            int power=power(args.getFirst(),source.getFirst(),work,0);
            if(power<2 || power>PolynomialProof.MAX_DEGREE) continue;
            Expr residual=power==2 ? source.getFirst() : JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.POW,source.getFirst(),JavaExpressions.literal(power-1));
            return JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MOD,
                JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,residual,known),source.get(1));
        }
        return node;
    }
    private static int power(Expr value,Expr base,VerificationWork work,int depth) {
        work.charge(1); if(depth>64) return -1;
        if(value.equals(base)) return 1;
        var op=JavaExpressions.operationOf(value).orElse(null); var args=JavaExpressions.operands(value);
        if(op==NumericOperation.POW && args.getFirst().equals(base) && JavaExpressions.isLiteral(args.get(1))) {
            BigInteger n=SemanticChecker.integer(JavaExpressions.literalValue(args.get(1)));
            return n.signum()>0 && n.compareTo(BigInteger.valueOf(PolynomialProof.MAX_DEGREE))<=0 ? n.intValue() : -1;
        }
        if(op==NumericOperation.MULTIPLY) {
            int a=power(args.getFirst(),base,work,depth+1), b=power(args.get(1),base,work,depth+1);
            return a>0 && b>0 && a+b<=PolynomialProof.MAX_DEGREE ? a+b : -1;
        }
        return -1;
    }
}
