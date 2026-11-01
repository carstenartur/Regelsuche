package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import java.math.BigInteger;

/** Units are established from supplied domain facts and closure, never a prime/name assumption. */
final class ModularUnitFacts {
    private final OptimizationRequest request;
    private final VerificationWork work;
    ModularUnitFacts(OptimizationRequest request,VerificationWork work) { this.request=request; this.work=work; }
    boolean unit(Expr value,Expr modulus) { return unit(value,modulus,0); }
    private boolean unit(Expr value,Expr modulus,int depth) {
        work.charge(1); if(depth>64) return false;
        if(value instanceof VariableExpr v && modulus instanceof VariableExpr q) {
            for(var fact:request.assumptions()) {
                work.charge(1);
                if(fact.kind()==SemanticAssumption.Kind.MODULAR_UNIT_INPUT && fact.subject().equals(v.name()) && fact.parameter().equals(q.name())) return true;
            }
            return false;
        }
        if(JavaExpressions.isLiteral(value)) {
            BigInteger n=SemanticChecker.integer(JavaExpressions.literalValue(value));
            return n.equals(BigInteger.ONE) || n.equals(BigInteger.ONE.negate());
        }
        var op=JavaExpressions.operationOf(value).orElse(null); var args=JavaExpressions.operands(value);
        if(op==null) return false;
        return switch(op) {
            case MULTIPLY -> unit(args.getFirst(),modulus,depth+1) && unit(args.get(1),modulus,depth+1);
            case NEGATE -> unit(args.getFirst(),modulus,depth+1);
            case MOD, MOD_INVERSE -> args.get(1).equals(modulus) && unit(args.getFirst(),modulus,depth+1);
            case MOD_MULTIPLY -> args.get(2).equals(modulus) && unit(args.getFirst(),modulus,depth+1) && unit(args.get(1),modulus,depth+1);
            case POW -> JavaExpressions.isLiteral(args.get(1))
                && SemanticChecker.integer(JavaExpressions.literalValue(args.get(1))).signum()>=0 && unit(args.getFirst(),modulus,depth+1);
            default -> false;
        };
    }
}
