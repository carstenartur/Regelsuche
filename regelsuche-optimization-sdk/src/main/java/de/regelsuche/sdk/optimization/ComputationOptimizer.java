package de.regelsuche.sdk.optimization;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.*;
import de.regelsuche.search.moves.MoveSearch;
import java.math.BigInteger;
import java.util.*;

/** Synchronous, headless facade over JointPlanSearch and the existing prepared computation DAG.
 * It never executes consumer source. Search completion, proof and estimated cost are separate data.
 */
public final class ComputationOptimizer {
    public static final String API_REVISION = "1";
    public static final String SEMANTICS_REVISION = "java25-numeric/v1";
    public static PreparedJointComputation prepare(JointComputationPlan plan) { return plan.prepare(new JavaNumericBackend(plan.inputs())); }
    public OptimizationResult optimize(OptimizationRequest request, CancellationToken token) {
        return optimize(request,token,false);
    }
    /** Same general search and proof boundary, excluding edits justified only by static evaluation. */
    public OptimizationResult optimizeRuntime(OptimizationRequest request, CancellationToken token) {
        return optimize(request,token,true);
    }
    private OptimizationResult optimize(OptimizationRequest request, CancellationToken token, boolean runtimeOnly) {
        Objects.requireNonNull(request); var work=new VerificationWork(request,token);
        try {
            var checker=new SemanticChecker(request,work); checker.validate();
            long setup=work.used();
            var generator=new JavaCandidateGenerator(request,work,runtimeOnly);
            var domain=new JointPlanSearch.Domain() {
                @Override public String revision() { return SEMANTICS_REVISION; }
                @Override public JointPlanSearch.Generation generate(JointComputationPlan source,int maximum) { return generator.generate(source,maximum); }
                @Override public JointPlanSearch.Verification verify(JointComputationPlan source,JointComputationPlan target) {
                    var proof=checker.check(source,target);
                    return new JointPlanSearch.Verification(proof.accepted(),proof.work());
                }
            };
            var budget=request.budget();
            var search=new JointPlanSearch(new JavaNumericBackend(request.plan().inputs()),domain,weights(request.goal()),budget.maximumCandidates());
            var found=search.optimize(request.plan(),new MoveSearch.Budget(8,8,budget.maximumWork(),budget.maximumStates(),Math.max(1,budget.maximumWork()-setup),64));
            work.charge(1);
            long searchWork=Math.addExact(setup,found.totalWork());
            var outcome=found.search().search().outcome();
            if(!found.withinBudget() || !found.search().withinBudget() || searchWork>budget.maximumWork()
                    || outcome==MoveSearch.Outcome.WORK_EXHAUSTED || outcome==MoveSearch.Outcome.STATE_LIMIT)
                return new OptimizationResult.BudgetExceeded("SEARCH_BUDGET_EXCEEDED",Math.max(searchWork,work.used()));
            // A rejected alternative makes the explored relation incomplete; it
            // does not invalidate an independently verified incumbent. Preserve
            // the incomplete status when no improvement was actually proved.
            long beforeFinal=work.used();
            var proof=checker.check(request.plan(),found.plan());
            if(!proof.accepted()) return new OptimizationResult.Inconclusive("FINAL_INDEPENDENT_CHECK_NOT_PROVED");
            long total=Math.max(work.used(),Math.addExact(searchWork,work.used()-beforeFinal));
            if(total>budget.maximumWork()) return new OptimizationResult.BudgetExceeded("FINAL_VERIFICATION_BUDGET_EXCEEDED",total);
            var obligations=obligations(request,found.plan());
            var cost=cost(request,found.plan(),obligations);
            if(runtimeOnly) {
                long beforePolicy=work.used();
                boolean useful=new RuntimeInputScope(work).improves(request,found.prepared());
                total=Math.max(work.used(),Math.addExact(total,work.used()-beforePolicy));
                if(total>budget.maximumWork()) return new OptimizationResult.BudgetExceeded("RUNTIME_POLICY_BUDGET_EXCEEDED",total);
                if(!useful) return new OptimizationResult.NoImprovement("NO_RUNTIME_DEPENDENT_IMPROVEMENT",
                        OptimizationResult.SearchCompletion.EXHAUSTED_BOUNDED_SPACE,total);
            }
            long preparationWork=cost.sourceCost().inspectionWork()+2*cost.candidateCost().inspectionWork();
            work.charge(preparationWork); total=Math.addExact(total,preparationWork);
            if(total>budget.maximumWork()) return new OptimizationResult.BudgetExceeded("FINAL_PREPARATION_BUDGET_EXCEEDED",total);
            boolean improved=cost.candidateScore()<cost.sourceScore();
            if(!improved && outcome==MoveSearch.Outcome.INCONCLUSIVE)
                return new OptimizationResult.Inconclusive("INCOMPLETE_SEARCH_RELATION");
            if(!improved) return new OptimizationResult.NoImprovement(generator.skippedConstantFold()?"CONSTANT_FOLD_BUDGET_LIMIT":"NO_IMPROVEMENT_WITH_FULL_POLICY_COST",OptimizationResult.SearchCompletion.EXHAUSTED_BOUNDED_SPACE,total);
            return new OptimizationResult.Candidate(found.plan(),found.prepared(),evidence(request,found.plan(),proof,obligations),obligations,cost,
                OptimizationResult.SearchCompletion.IMPROVEMENT_FOUND,total);
        } catch(VerificationWork.Stopped stopped) {
            return stopped.cancelled ? new OptimizationResult.Cancelled("CANCELLED") : new OptimizationResult.BudgetExceeded("OPTIMIZATION_BUDGET_EXCEEDED",work.used());
        } catch(IllegalArgumentException invalid) { return new OptimizationResult.Unsupported(diagnostic(invalid)); }
    }
    public VerificationResult verify(OptimizationRequest request, JointComputationPlan candidate, CancellationToken token) {
        Objects.requireNonNull(request); Objects.requireNonNull(candidate); var work=new VerificationWork(request,token);
        try {
            var checker=new SemanticChecker(request,work); checker.validate();
            var proof=checker.check(request.plan(),candidate);
            if(proof.accepted()) {
                var obligations=obligations(request,candidate);
                return new VerificationResult.Verified(evidence(request,candidate,proof,obligations),obligations);
            }
            var counterexample=counterexample(request,candidate,work);
            if(counterexample!=null) return new VerificationResult.Refuted("JAVA_NUMERIC_COUNTEREXAMPLE",counterexample);
            return new VerificationResult.Inconclusive("OUTSIDE_PROVED_NUMERIC_FRAGMENT");
        } catch(VerificationWork.Stopped stopped) {
            return stopped.cancelled ? new VerificationResult.Cancelled("CANCELLED") : new VerificationResult.BudgetExceeded("VERIFICATION_BUDGET_EXCEEDED",work.used());
        } catch(IllegalArgumentException invalid) { return new VerificationResult.Unsupported(diagnostic(invalid)); }
    }
    public VerificationResult reverify(OptimizationRequest request, OptimizationResult.Candidate candidate, CancellationToken token) {
        Objects.requireNonNull(candidate);
        var verified=verify(request,candidate.plan(),token);
        if(!(verified instanceof VerificationResult.Verified proof)) return verified;
        if(!proof.evidence().equals(candidate.evidence()) || !proof.obligations().equals(candidate.obligations()))
            return new VerificationResult.Unsupported("EVIDENCE_BINDING_OR_REVISION_DIFFERS");
        return proof;
    }
    /** Reference policy evaluator for consumer qualification. Generated Java needs no SDK runtime. */
    public Map<String,Object> evaluateChecked(OptimizationRequest request, OptimizationResult.Candidate candidate, Map<String,?> inputs) {
        if(request.safetyProfile()!=SafetyProfile.CHECKED_THROW) throw new IllegalArgumentException("CHECKED_PROFILE_REQUIRED");
        if(!(reverify(request,candidate,CancellationToken.NONE) instanceof VerificationResult.Verified)) throw new IllegalArgumentException("CANDIDATE_NOT_REVERIFIED");
        return RuntimeChecks.checked(request,candidate.plan(),candidate.obligations(),inputs);
    }
    /** Reference fallback. The boolean guard has no user effects; a failed gate selects the original values. */
    public Map<String,Object> evaluateGuarded(OptimizationRequest request, OptimizationResult.Candidate candidate, Map<String,?> inputs) {
        if(request.safetyProfile()!=SafetyProfile.GUARDED_FALLBACK) throw new IllegalArgumentException("GUARDED_PROFILE_REQUIRED");
        if(!(reverify(request,candidate,CancellationToken.NONE) instanceof VerificationResult.Verified)) throw new IllegalArgumentException("CANDIDATE_NOT_REVERIFIED");
        try { return RuntimeChecks.checked(request,candidate.plan(),candidate.obligations(),inputs); }
        catch(RuntimeChecks.AssumptionGuardFailure | ArithmeticException numericalGate) { return prepare(request.plan()).execute(inputs); }
    }
    private static RuntimeObligations obligations(OptimizationRequest request,JointComputationPlan target) {
        var kinds=EnumSet.noneOf(NumericKind.class);
        request.plan().inputs().values().forEach(type -> kinds.add(NumericKind.fromType(type)));
        request.sourceTrace().occurrences().forEach(o -> kinds.add(o.evaluatedKind()));
        target.outputs().forEach(o -> kinds.add(NumericKind.fromType(o.type())));
        boolean active=request.safetyProfile()!=SafetyProfile.PRESERVE_JAVA;
        boolean integral=active&&kinds.stream().anyMatch(NumericKind::integral);
        boolean fp=active&&kinds.stream().anyMatch(NumericKind::floatingPoint);
        var replacement=SourceEvaluationTrace.fromPlan(target);
        long checkWork=0;
        // A BigInteger-only fallback has no primitive numerical gate. Receiver
        // guards belong to the source adapter; it must account for their real cost.
        boolean evaluateOriginal = active && (request.safetyProfile()==SafetyProfile.CHECKED_THROW || integral || fp);
        if(evaluateOriginal) {
            var backend=new JavaNumericBackend(request.plan().inputs());
            for(var occurrence:request.sourceTrace().occurrences()) checkWork+=backend.operation(occurrence.expression()).work();
            checkWork+=(request.sourceTrace().occurrences().size()+replacement.occurrences().size())*(fp?3L:2L)
                +request.plan().inputs().size()+2L*request.plan().outputs().size();
        }
        int fallback=request.safetyProfile()==SafetyProfile.GUARDED_FALLBACK && (integral || fp)?request.sourceTrace().occurrences().size():0;
        var guard=request.safetyProfile()!=SafetyProfile.GUARDED_FALLBACK?RuntimeObligations.GuardKind.NONE:fp?
            RuntimeObligations.GuardKind.FINITE_AND_BITWISE_EQUAL:integral?RuntimeObligations.GuardKind.ORIGINAL_AND_REPLACEMENT_RANGE:RuntimeObligations.GuardKind.NONE;
        return new RuntimeObligations(guard,request.sourceTrace(),replacement,integral,fp,fp,checkWork,fallback);
    }
    private static VerificationEvidence evidence(OptimizationRequest request,JointComputationPlan target,SemanticChecker.Proof proof,RuntimeObligations obligations) {
        return new VerificationEvidence("regelsuche.optimization-evidence/v1",SEMANTICS_REVISION,SemanticChecker.REVISION,JavaCandidateGenerator.REVISION,
            EvidenceHashes.plan(request.plan()),EvidenceHashes.plan(target),EvidenceHashes.trace(request.sourceTrace()),EvidenceHashes.assumptions(request.assumptions()),
            request.safetyProfile(),request.checkedPolicy(),obligations,proof.methods(),proof.work());
    }
    private static JointPlanSearch.Weights weights(OptimizationGoal goal) {
        return switch(goal) {
            case LOWER_ESTIMATED_RUNTIME -> new JointPlanSearch.Weights(10,1,1,1);
            case LOWER_ALLOCATION -> new JointPlanSearch.Weights(1,10,10,1);
            case READABILITY -> new JointPlanSearch.Weights(1,0,0,1);
        };
    }
    private static OptimizationResult.CostAssessment cost(OptimizationRequest request,JointComputationPlan target,RuntimeObligations obligations) {
        var source=prepare(request.plan()).cost(); var candidate=prepare(target).cost();
        var backend=new JavaNumericBackend(request.plan().inputs());
        long originalWork=0;
        for(var occurrence:request.sourceTrace().occurrences()) originalWork+=backend.operation(occurrence.expression()).work();
        long sourceWork=Math.max(originalWork,source.operationWork());
        var weights=weights(request.goal());
        long sourceScore=source.weighted(weights.operation(),weights.liveStorage(),weights.retainedStorage(),weights.outputBindings())+(sourceWork-source.operationWork())*weights.operation();
        long candidateScore=candidate.weighted(weights.operation(),weights.liveStorage(),weights.retainedStorage(),weights.outputBindings());
        if(request.goal()!=OptimizationGoal.READABILITY) candidateScore+=obligations.estimatedCheckWork()*weights.operation()+obligations.fallbackOperationCount();
        boolean runtime=candidate.operationWork()+obligations.estimatedCheckWork()+obligations.fallbackOperationCount()<sourceWork;
        return new OptimizationResult.CostAssessment(sourceScore,candidateScore,obligations.estimatedCheckWork(),obligations.fallbackOperationCount(),source,candidate,runtime);
    }
    private static Map<String,Object> counterexample(OptimizationRequest request,JointComputationPlan candidate,VerificationWork work) {
        if(!request.plan().inputs().equals(candidate.inputs())) return Map.of();
        if(!safeToSample(request,candidate,work)) return null;
        var source=prepare(request.plan()); var target=prepare(candidate);
        var names=request.plan().inputs().keySet().stream().sorted().toList();
        var baseline=new LinkedHashMap<String,Object>();
        for(var name:names) baseline.put(name,samples(NumericKind.fromType(request.plan().inputs().get(name))).getFirst());
        var probes=new ArrayList<Map<String,Object>>(); probes.add(baseline);
        for(var name:names) for(var sample:samples(NumericKind.fromType(request.plan().inputs().get(name)))) {
            work.charge(1); var probe=new LinkedHashMap<>(baseline); probe.put(name,sample); probes.add(probe);
            if(probes.size()>=256) break;
        }
        for(var probe:probes) {
            work.charge(1);
            try { RuntimeChecks.validateAssumptions(request,probe); }
            catch(IllegalArgumentException assumptionViolated) { continue; }
            try {
                var left=source.execute(probe); var right=target.execute(probe);
                if(!left.keySet().equals(right.keySet()) || left.keySet().stream().anyMatch(name -> !RuntimeChecks.same(left.get(name),right.get(name)))) return probe;
            } catch(ArithmeticException existingException) { /* Throwing fragments are validated separately; samples never prove them. */ }
        }
        return null;
    }
    /** Diagnostic samples can refute only. Never execute resource-heavy operations to obtain one. */
    private static boolean safeToSample(OptimizationRequest request,JointComputationPlan candidate,VerificationWork work) {
        var bounds=new BigIntegerBounds(request,work,4096);
        var pending=new ArrayDeque<Expr>();
        pending.addAll(request.plan().outputExpressions()); pending.addAll(candidate.outputExpressions());
        request.sourceTrace().occurrences().forEach(o -> pending.add(o.expression()));
        var seen=new HashSet<Expr>();
        try {
            while(!pending.isEmpty()) {
                work.charge(1); var expression=pending.removeFirst();
                if(!seen.add(expression)) continue;
                if(JavaExpressions.kindOf(expression,request.plan().inputs())==NumericKind.BIG_INTEGER) {
                    var operation=JavaExpressions.operationOf(expression).orElse(null);
                    if(operation!=null && EnumSet.of(NumericOperation.POW,NumericOperation.MOD_POW,NumericOperation.MOD_MULTIPLY,
                            NumericOperation.SHIFT_LEFT,NumericOperation.SHIFT_RIGHT).contains(operation)) return false;
                    bounds.require(expression);
                }
                if(!JavaExpressions.isLiteral(expression)) pending.addAll(JavaExpressions.operands(expression));
            }
            return true;
        } catch(IllegalArgumentException outsideBound) { return false; }
    }
    private static List<Object> samples(NumericKind kind) {
        return switch(kind) {
            case INT -> List.of(1,Integer.MAX_VALUE,Integer.MIN_VALUE,0,-1,2,-2,1<<30);
            case LONG -> List.of(1L,Long.MAX_VALUE,Long.MIN_VALUE,0L,-1L,2L,-2L,1L<<62);
            case BYTE -> List.of((byte)1,Byte.MIN_VALUE,Byte.MAX_VALUE,(byte)0,(byte)-1);
            case SHORT -> List.of((short)1,Short.MIN_VALUE,Short.MAX_VALUE,(short)0,(short)-1);
            case CHAR -> List.of((char)1,Character.MIN_VALUE,Character.MAX_VALUE);
            case FLOAT -> List.of(1f,16_777_216f,-0f,0f,Float.MAX_VALUE,Float.MIN_VALUE,Float.MIN_NORMAL,Float.POSITIVE_INFINITY,Float.NaN,-1f);
            case DOUBLE -> List.of(1d,1e16,-0d,0d,Double.MAX_VALUE,Double.MIN_VALUE,Double.MIN_NORMAL,Double.POSITIVE_INFINITY,Double.NaN,-1d);
            case BIG_INTEGER -> List.of(BigInteger.ONE,BigInteger.TEN,BigInteger.ZERO,BigInteger.ONE.negate(),BigInteger.valueOf(17));
        };
    }
    private static String diagnostic(IllegalArgumentException invalid) { return invalid.getMessage()==null?"INVALID_TYPED_PLAN":invalid.getMessage(); }
}
