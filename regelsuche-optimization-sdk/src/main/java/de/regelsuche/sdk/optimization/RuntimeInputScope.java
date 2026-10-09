package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.PreparedJointComputation;
import java.util.IdentityHashMap;
import java.util.Map;

/** Input-dependency policy shared by mathematical providers, not a source-pattern recognizer. */
final class RuntimeInputScope {
    private final VerificationWork work;
    private final Map<Expr,Boolean> memo=new IdentityHashMap<>();
    RuntimeInputScope(VerificationWork work){this.work=work;}
    boolean depends(Expr expression){return depends(expression,0);}
    private boolean depends(Expr expression,int depth){
        work.charge(1);
        if(depth>128)throw new IllegalArgumentException("RUNTIME_SCOPE_STRUCTURAL_BOUND");
        Boolean known=memo.get(expression);
        if(known!=null)return known;
        boolean result=expression instanceof VariableExpr;
        if(!result && !JavaExpressions.isLiteral(expression))
            for(Expr child:JavaExpressions.operands(expression))
                if(depends(child,depth+1)){result=true;break;}
        memo.put(expression,result);
        return result;
    }
    boolean improves(OptimizationRequest request,PreparedJointComputation candidate){
        var backend=new JavaNumericBackend(request.plan().inputs());
        long original=0,replacement=0;
        for(var occurrence:request.sourceTrace().occurrences()){
            work.charge(1);
            if(depends(occurrence.expression()))
                original=Math.addExact(original,backend.operation(occurrence.expression()).work());
        }
        for(var node:candidate.nodes()){
            work.charge(1);
            if(node.operation()!=null && depends(node.expression()))
                replacement=Math.addExact(replacement,node.operation().work());
        }
        // Static evaluation alone is not a useful source-code edit. This policy
        // does not assert that a rejected bounded search has no better solution.
        return replacement<original;
    }
}
