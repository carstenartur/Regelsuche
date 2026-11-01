package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;

/** If x*r=y and an original y inverse succeeds, r*y^-1 is an inverse of x in Z/mZ. */
final class ModularInverseProof {
    private final OptimizationRequest request;
    private final VerificationWork work;
    ModularInverseProof(OptimizationRequest request,VerificationWork work) { this.request=request; this.work=work; }
    boolean equivalent(Expr a,Expr b) { return directed(a,b) || directed(b,a); }
    private boolean directed(Expr original,Expr replacement) {
        work.charge(1);
        if(op(original)!=NumericOperation.MOD_INVERSE) return false;
        var source=JavaExpressions.operands(original); Expr modulus=source.get(1);
        Expr product;
        if(op(replacement)==NumericOperation.MOD && JavaExpressions.operands(replacement).get(1).equals(modulus))
            product=JavaExpressions.operands(replacement).getFirst();
        else if(op(replacement)==NumericOperation.MOD_MULTIPLY && JavaExpressions.operands(replacement).get(2).equals(modulus))
            product=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,
                JavaExpressions.operands(replacement).getFirst(),JavaExpressions.operands(replacement).get(1));
        else return false;
        if(op(product)!=NumericOperation.MULTIPLY) return false;
        var factors=JavaExpressions.operands(product);
        for(int i=0;i<2;i++) {
            Expr known=factors.get(i);
            if(op(known)!=NumericOperation.MOD_INVERSE || !JavaExpressions.operands(known).get(1).equals(modulus)) continue;
            boolean retained=false;
            for(var occurrence:request.sourceTrace().occurrences()) {
                work.charge(1); if(occurrence.expression().equals(known)) { retained=true; break; }
            }
            if(!retained) continue;
            Expr multiplied=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,source.getFirst(),factors.get(1-i));
            try {
                if(new PolynomialProof(modulus,work).equivalent(multiplied,JavaExpressions.operands(known).getFirst())) return true;
            } catch(PolynomialProof.OutsideFragment bounded) { /* Unknown within finite caps. */ }
        }
        return false;
    }
    private static NumericOperation op(Expr e) { return JavaExpressions.operationOf(e).orElse(null); }
}
