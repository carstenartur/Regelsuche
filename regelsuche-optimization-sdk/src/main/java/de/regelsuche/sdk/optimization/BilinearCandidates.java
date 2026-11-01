package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import java.util.*;

/** Proposals over a joint pair of bilinear expressions. PolynomialProof owns acceptance. */
final class BilinearCandidates {
    private final VerificationWork work;
    private record Eligible(int index,Expr expression,Expr modulus) { }
    BilinearCandidates(VerificationWork work) { this.work=work; }
    List<List<Expr>> generate(List<Expr> outputs,int maximum) {
        var proposals=new ArrayList<List<Expr>>();
        if(maximum<=0) return List.of();
        var subtractions=new ArrayList<Eligible>(); var additions=new ArrayList<Eligible>();
        collectEligible(outputs,subtractions,additions);
        for(var subtraction:subtractions) for(var addition:additions) {
            if(proposals.size()>=maximum) return List.copyOf(proposals);
            addPairProposals(outputs,subtraction,addition,proposals);
        }
        return List.copyOf(proposals.subList(0,Math.min(maximum,proposals.size())));
    }
    private void addPairProposals(List<Expr> outputs,Eligible subtraction,Eligible addition,List<List<Expr>> proposals) {
        work.charge(1);
        if(!Objects.equals(subtraction.modulus(),addition.modulus())) return;
        Expr left=subtraction.expression(),right=addition.expression(),modulus=subtraction.modulus();
        Expr p=args(left).getFirst(), q=args(left).get(1);
        if(op(p)!=NumericOperation.MULTIPLY || op(q)!=NumericOperation.MULTIPLY) return;
        for(int first=0;first<2;first++) for(int second=0;second<2;second++) {
            work.charge(1);
            Expr a=args(p).get(first), c=args(p).get(1-first), b=args(q).get(second), d=args(q).get(1-second);
            if(!(product(args(right).getFirst(),a,d) && product(args(right).get(1),b,c))
                    && !(product(args(right).get(1),a,d) && product(args(right).getFirst(),b,c))) continue;
            Expr shared=operation(NumericOperation.MULTIPLY,operation(NumericOperation.ADD,a,b),operation(NumericOperation.ADD,c,d));
            Expr imaginary=operation(NumericOperation.SUBTRACT,operation(NumericOperation.SUBTRACT,shared,p),q);
            work.charge(2L*outputs.size());
            var changed=new ArrayList<>(outputs);
            changed.set(addition.index(),modulus==null?imaginary:operation(NumericOperation.MOD,imaginary,modulus));
            proposals.add(List.copyOf(changed));
            break; // Keep the next first-factor orientation, even when this one matched.
        }
    }
    private void collectEligible(List<Expr> outputs,List<Eligible> subtractions,List<Eligible> additions) {
        for(int index=0;index<outputs.size();index++) {
            work.charge(1);
            Expr expression=outputs.get(index),modulus=null;
            if(op(expression)==NumericOperation.MOD) {
                modulus=args(expression).get(1); expression=args(expression).getFirst();
            }
            var operation=op(expression);
            if((operation!=NumericOperation.SUBTRACT && operation!=NumericOperation.ADD)
                    || JavaExpressions.resultKind(expression)!=NumericKind.BIG_INTEGER) continue;
            if(op(args(expression).getFirst())!=NumericOperation.MULTIPLY || op(args(expression).get(1))!=NumericOperation.MULTIPLY) continue;
            (operation==NumericOperation.SUBTRACT?subtractions:additions).add(new Eligible(index,expression,modulus));
        }
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
