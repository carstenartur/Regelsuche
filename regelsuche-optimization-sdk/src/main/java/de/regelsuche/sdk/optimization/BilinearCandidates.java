package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.*;

/** Proposals over a joint pair of bilinear expressions. PolynomialProof owns acceptance. */
final class BilinearCandidates {
    private final VerificationWork work;
    BilinearCandidates(VerificationWork work) { this.work=work; }
    List<List<Expr>> generate(List<Expr> outputs,int maximum) {
        var proposals=new ArrayList<List<Expr>>();
        for(int i=0;i<outputs.size() && proposals.size()<maximum;i++) for(int j=0;j<outputs.size() && proposals.size()<maximum;j++) {
            work.charge(1); if(i==j) continue;
            Expr left=outputs.get(i), right=outputs.get(j), modulus=null;
            if(op(left)==NumericOperation.MOD && op(right)==NumericOperation.MOD) {
                if(!args(left).get(1).equals(args(right).get(1))) continue;
                modulus=args(left).get(1); left=args(left).getFirst(); right=args(right).getFirst();
            }
            if(op(left)!=NumericOperation.SUBTRACT || op(right)!=NumericOperation.ADD
                    || JavaExpressions.resultKind(left)!=NumericKind.BIG_INTEGER || JavaExpressions.resultKind(right)!=NumericKind.BIG_INTEGER) continue;
            Expr p=args(left).getFirst(), q=args(left).get(1);
            if(op(p)!=NumericOperation.MULTIPLY || op(q)!=NumericOperation.MULTIPLY) continue;
            for(int first=0;first<2;first++) for(int second=0;second<2;second++) {
                work.charge(1);
                Expr a=args(p).get(first), c=args(p).get(1-first), b=args(q).get(second), d=args(q).get(1-second);
                if(!(product(args(right).getFirst(),a,d) && product(args(right).get(1),b,c))
                        && !(product(args(right).get(1),a,d) && product(args(right).getFirst(),b,c))) continue;
                Expr shared=operation(NumericOperation.MULTIPLY,operation(NumericOperation.ADD,a,b),operation(NumericOperation.ADD,c,d));
                Expr imaginary=operation(NumericOperation.SUBTRACT,operation(NumericOperation.SUBTRACT,shared,p),q);
                var changed=new ArrayList<>(outputs);
                changed.set(j,modulus==null?imaginary:operation(NumericOperation.MOD,imaginary,modulus));
                proposals.add(List.copyOf(changed));
                break;
            }
        }
        return List.copyOf(proposals.subList(0,Math.min(maximum,proposals.size())));
    }
    private static boolean product(Expr expression,Expr a,Expr b) {
        if(op(expression)!=NumericOperation.MULTIPLY) return false;
        return args(expression).getFirst().equals(a) && args(expression).get(1).equals(b)
            || args(expression).getFirst().equals(b) && args(expression).get(1).equals(a);
    }
    private static NumericOperation op(Expr e) { return JavaExpressions.operationOf(e).orElse(null); }
    private static List<Expr> args(Expr e) { return JavaExpressions.operands(e); }
    private static Expr operation(NumericOperation op,Expr... args) { return JavaExpressions.operation(NumericKind.BIG_INTEGER,op,args); }
}
