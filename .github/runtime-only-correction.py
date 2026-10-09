"""One-time source edit for the isolated repair workspace, never shipped or used as a test."""
from pathlib import Path
import sys
root=Path(sys.argv[1])/"regelsuche-optimization-sdk/src/main/java/de/regelsuche/sdk/optimization"
def edit(name,old,new):
    path=root/name
    text=path.read_text()
    if text.count(old)!=1: raise SystemExit("Source anchor mismatch: "+name+" / "+old[:100])
    path.write_text(text.replace(old,new))
path=root/"RuntimeInputScope.java"
if path.exists(): raise SystemExit("Refusing to overwrite an existing runtime scope")
path.write_text('''package de.regelsuche.sdk.optimization;

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
''')
edit('ComputationOptimizer.java',
'''    public OptimizationResult optimize(OptimizationRequest request, CancellationToken token) {
        Objects.requireNonNull(request); var work=new VerificationWork(request,token);''',
'''    public OptimizationResult optimize(OptimizationRequest request, CancellationToken token) {
        return optimize(request,token,false);
    }
    /** Same general search and proof boundary, excluding edits justified only by static evaluation. */
    public OptimizationResult optimizeRuntime(OptimizationRequest request, CancellationToken token) {
        return optimize(request,token,true);
    }
    private OptimizationResult optimize(OptimizationRequest request, CancellationToken token, boolean runtimeOnly) {
        Objects.requireNonNull(request); var work=new VerificationWork(request,token);''')
edit('ComputationOptimizer.java','var generator=new JavaCandidateGenerator(request,work);','var generator=new JavaCandidateGenerator(request,work,runtimeOnly);')
edit('ComputationOptimizer.java',
'''            var cost=cost(request,found.plan(),obligations);
            long preparationWork''',
'''            var cost=cost(request,found.plan(),obligations);
            if(runtimeOnly) {
                long beforePolicy=work.used();
                boolean useful=new RuntimeInputScope(work).improves(request,found.prepared());
                total=Math.max(work.used(),Math.addExact(total,work.used()-beforePolicy));
                if(total>budget.maximumWork()) return new OptimizationResult.BudgetExceeded("RUNTIME_POLICY_BUDGET_EXCEEDED",total);
                if(!useful) return new OptimizationResult.NoImprovement("NO_RUNTIME_DEPENDENT_IMPROVEMENT",
                        OptimizationResult.SearchCompletion.EXHAUSTED_BOUNDED_SPACE,total);
            }
            long preparationWork''')
edit('JavaCandidateGenerator.java','java-general-algebra-proposals/v7','java-general-algebra-proposals/v8')
edit('JavaCandidateGenerator.java','    private final CoreAlgebraCandidates algebra;','    private final CoreAlgebraCandidates algebra;\n    private final RuntimeInputScope runtime;')
edit('JavaCandidateGenerator.java',
'''    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work) {
        this.request = request; this.work = work; backend = new JavaNumericBackend(request.plan().inputs());
        algebra = new CoreAlgebraCandidates(request, work);
    }''',
'''    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work) {
        this(request,work,false);
    }
    JavaCandidateGenerator(OptimizationRequest request, VerificationWork work, boolean runtimeOnly) {
        this.request = request; this.work = work; backend = new JavaNumericBackend(request.plan().inputs());
        runtime = runtimeOnly ? new RuntimeInputScope(work) : null;
        algebra = new CoreAlgebraCandidates(request, work, runtimeOnly);
    }''')
edit('JavaCandidateGenerator.java',
'''        work.charge(1);
        if (!changed.equals(original) && seen.add(changed))''',
'''        work.charge(1);
        if (runtime != null) {
            var retained = new ArrayList<>(changed);
            for (int i=0;i<original.size();i++)
                if (!runtime.depends(original.get(i))) retained.set(i,original.get(i));
            changed = retained;
        }
        if (!changed.equals(original) && seen.add(changed))''')
edit('JavaCandidateGenerator.java',
'''    private Expr simplify(Expr expression, int depth) {
        work.charge(1);''',
'''    private Expr simplify(Expr expression, int depth) {
        work.charge(1);
        if (runtime != null && !runtime.depends(expression)) return expression;''')
edit('JavaCandidateGenerator.java',
'''    private Expr simplifyHere(Expr result) {
        work.charge(1);''',
'''    private Expr simplifyHere(Expr result) {
        work.charge(1);
        if (runtime != null && !runtime.depends(result)) return result;''')
edit('CoreAlgebraCandidates.java','    private final List<RewriteRule> rules;','    private final List<RewriteRule> rules;\n    private final RuntimeInputScope runtime;')
edit('CoreAlgebraCandidates.java',
'''    CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work, List<RewriteRule> rules) {
        this.request = request;
        this.work = work;
        this.rules = List.copyOf(rules);
    }''',
'''    CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work, List<RewriteRule> rules) {
        this(request,work,rules,false);
    }
    CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work, boolean runtimeOnly) {
        this(request,work,AstRewriteTransformationEngine.defaultRules(),runtimeOnly);
    }
    private CoreAlgebraCandidates(OptimizationRequest request, VerificationWork work, List<RewriteRule> rules, boolean runtimeOnly) {
        this.request = request;
        this.work = work;
        this.rules = List.copyOf(rules);
        this.runtime = runtimeOnly ? new RuntimeInputScope(work) : null;
    }''')
edit('CoreAlgebraCandidates.java',
'''        for (Expr site : sites) {
            NumericKind kind''',
'''        for (Expr site : sites) {
            if (runtime != null && !runtime.depends(site)) continue;
            NumericKind kind''')
edit('CoreAlgebraCandidates.java',
'''            if (++nodes > MAX_ISLAND_NODES || depth > 64) throw new OutsideBridge();
            if (JavaExpressions.kindOf''',
'''            if (++nodes > MAX_ISLAND_NODES || depth > 64) throw new OutsideBridge();
            // Preserve authored closed subgraphs while still using literal identities.
            if (runtime != null && !JavaExpressions.isLiteral(expression) && !runtime.depends(expression)) return atom(expression);
            if (JavaExpressions.kindOf''')
