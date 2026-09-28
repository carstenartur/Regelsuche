package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.*;

/** Explicit Expr execution through the same frontier and batch pickers as the historical facade. */
public final class NativeMoveSearch {
    public static final String REVISION = "regelsuche.native-expr-move-search/v2-final-replay";
    public record Primitive(MoveProvider.Descriptor descriptor, AstRewriteTransport transport) implements NativeMoveProvider {
        public Primitive {
            Objects.requireNonNull(descriptor);Objects.requireNonNull(transport);
            if(descriptor.sourceKind()!=SearchMove.SourceKind.PRIMITIVE)throw new IllegalArgumentException("primitive native provider required");
        }
        @Override public Batch candidates(TypedMoveSearch.State source,TypedMoveSearch.Context context){
            if(!NativeMoveProvider.carries(descriptor.requiredAssumptions(),source,context))return NativeMoveProvider.rejectedAssumptions();
            var steps=transport.generate(source.expression());var work=TransformationWorkMetrics.flatEngine(steps.size());
            return new Batch(steps.stream().map(step->new NativeSearchMove(step,descriptor,work.totalWorkUnits(),Set.of())).toList(),
                work.withCandidateWork(new ExecutionWork(steps.size(),0,0)),false);
        }
        @Override public NativeVerification verify(TypedMoveSearch.State source,NativeSearchMove move,TypedMoveSearch.Context context){
            if(!NativeMoveProvider.carries(move.assumptions(),source,context) || !NativeMoveProvider.carries(descriptor.requiredAssumptions(),source,context))return new NativeVerification(false,1,null,null,"TYPED_PRIMITIVE_ASSUMPTIONS_MISSING");
            var generated=transport.generate(source.expression());
            boolean accepted=descriptor.equals(move.descriptor()) && source.expression().equals(move.sourceExpression())
                && move.proof() instanceof NativeMoveProof.Primitive proof && generated.contains(proof.step());
            return new NativeVerification(accepted,TransformationWorkMetrics.flatEngine(generated.size()).totalWorkUnits(),
                accepted?move.proof():null,accepted?move.ruleId():null,accepted?"TYPED_PRIMITIVE_REPLAYED":"TYPED_PRIMITIVE_REPLAY_REJECTED");
        }
    }
    public enum ZeroScore implements java.util.function.ToDoubleFunction<TypedMoveSearch.State> { INSTANCE;
        @Override public double applyAsDouble(TypedMoveSearch.State state){return 0;}
    }
    public record Problem(Expr source, TypedMoveSearch.Context context, List<NativeMoveProvider> providers,
            MoveSearch.Mode mode, MoveSearch.Scheduling scheduling, MoveSearch.Budget budget,
            NativeMovePriorityPolicy policy,java.util.function.ToDoubleFunction<TypedMoveSearch.State> stateScore,NativeStateValue stateValue,NativeVerifier verifier) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(source);v.reference(context);v.reference(providers);v.reference(mode);v.reference(scheduling);v.reference(budget);v.reference(policy);v.reference(stateScore);v.reference(stateValue);v.reference(verifier);}
        public Problem(Expr source,TypedMoveSearch.Context context,List<NativeMoveProvider> providers,MoveSearch.Mode mode,MoveSearch.Scheduling scheduling,MoveSearch.Budget budget){
            this(source,context,providers,mode,scheduling,budget,NativeMovePriorityPolicy.INVENTORY_ORDER,ZeroScore.INSTANCE,NativeStateValue.NONE,NativeVerifier.registered(providers));
        }
        public Problem(Expr source,TypedMoveSearch.Context context,List<NativeMoveProvider> providers,MoveSearch.Mode mode,MoveSearch.Scheduling scheduling,MoveSearch.Budget budget,
                NativeMovePriorityPolicy policy,java.util.function.ToDoubleFunction<TypedMoveSearch.State> stateScore,NativeStateValue stateValue){
            this(source,context,providers,mode,scheduling,budget,policy,stateScore,stateValue,NativeVerifier.registered(providers));
        }
        public Problem {
            Objects.requireNonNull(source);Objects.requireNonNull(context);providers=List.copyOf(providers);
            Objects.requireNonNull(mode);Objects.requireNonNull(scheduling);Objects.requireNonNull(budget);
            Objects.requireNonNull(policy);Objects.requireNonNull(stateScore);Objects.requireNonNull(stateValue);Objects.requireNonNull(verifier);
            if(context.phase()==MoveContext.Phase.PRODUCTION)throw new IllegalArgumentException("experimental scheduling is not production-qualified (#745)");
            if(scheduling!=MoveSearch.Scheduling.STAGED && scheduling!=MoveSearch.Scheduling.EAGER_CONTROL && scheduling!=MoveSearch.Scheduling.STAGED_INCREMENTAL)
                throw new IllegalArgumentException("native provider does not implement this scheduling contract");
        }
    }
    /** Populated privately before result publication; accessors expose only immutable scalar observations. */
    public static final class Accounting implements RetainedGraph.View {
        private long validationWork,storageWork,retentionWork,peakNodes,peakCharacters,peakReferences,resultNodes,resultCharacters,resultReferences;
        private boolean complete;private String detail="";
        void update(long validation,long storage,long retention,long pn,long pc,long pr,long rn,long rc,long rr,boolean complete,String detail){
            validationWork=validation;storageWork=storage;retentionWork=retention;peakNodes=pn;peakCharacters=pc;peakReferences=pr;
            resultNodes=rn;resultCharacters=rc;resultReferences=rr;this.complete=complete;this.detail=detail;
        }
        public long validationWork(){return validationWork;}
        public long storageWork(){return storageWork;}
        public long retentionWork(){return retentionWork;}
        public RetainedGraph.Usage live(){return new RetainedGraph.Usage(0,0,0);}
        public RetainedGraph.Usage peak(){return new RetainedGraph.Usage(peakNodes,peakCharacters,peakReferences);}
        public RetainedGraph.Usage resultRetained(){return new RetainedGraph.Usage(resultNodes,resultCharacters,resultReferences);}
        public boolean complete(){return complete;}
        public String detail(){return detail;}
        long work(){return Math.addExact(Math.addExact(validationWork,storageWork),retentionWork);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(detail);}
    }
    public static final class Result implements RetainedGraph.View {
        Accounting accounting;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(result);v.reference(source);v.reference(incrementalProviders);v.reference(accounting);}
        private final SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,NativeStateValue.Assessment> result;
        private final Expr source;
        private final long replayWork,workBudget;
        private final List<StagedIncrementalMoveExecution.Provider> incrementalProviders;
        private Result(Problem problem,SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,NativeStateValue.Assessment> result){
            this(problem,result,0);
        }
        private Result(Problem problem,SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,NativeStateValue.Assessment> result,long replayWork){
            this.source=problem.source();this.result=result;this.replayWork=replayWork;this.workBudget=problem.budget().totalWork();
            incrementalProviders=problem.scheduling()==MoveSearch.Scheduling.STAGED_INCREMENTAL?problem.providers().stream()
                .map(p->new StagedIncrementalMoveExecution.Provider(p.descriptor(),NativeIncrementalSources.definition(p))).toList():null;
        }
        @SuppressWarnings("unchecked")
        public List<SearchExecution.Expansion<TypedMoveSearch.State>> cursorReceipts(){
            return result.pickerReceipts().stream().map(r->(SearchExecution.Expansion<TypedMoveSearch.State>)r).toList();
        }
        public boolean accountingComplete(){return (accounting==null || accounting.complete()) && cursorReceipts().stream().flatMap(r->r.lanes().stream()).allMatch(l->l.cursor()==null || l.cursor().accountingComplete());}
        public Accounting accounting(){if(accounting==null)throw new IllegalStateException("ownership accounting unavailable for this revision");return accounting;}
        public long replayWork(){return replayWork;}
        public long totalWork(){return Math.addExact(Math.addExact(metrics().totalWork(),replayWork),accounting==null?0:accounting.work());}
        public boolean withinBudget(){return accountingComplete() && totalWork()<=workBudget;}
        public MoveSearch.Outcome outcome(){if(accounting!=null && !accounting.complete())return MoveSearch.Outcome.INCONCLUSIVE;return totalWork()>workBudget?MoveSearch.Outcome.WORK_EXHAUSTED:result.outcome();}
        public Expr output(){return result.witness().isEmpty()?source:result.witness().getLast().target().expression();}
        public List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness(){return result.witness();}
        /** Frontier receipt. Independently paid final replay is separate; use totalWork for the whole result. */
        public MoveSearch.Metrics metrics(){return result.metrics();}
        /** Historical frontier projection; the additive native replay receipt remains available separately. */
        public MoveSearch.Result exportLegacy(){
            var assessments=new HashMap<MoveState,StateValue.Assessment>();
            result.stateAssessments().forEach((state,value)->assessments.put(export(state),export(value)));
            return new MoveSearch.Result(outcome(),result.witness().stream().map(s->new MoveSearch.WitnessStep(export(s.source()),export(s.target()),s.move().exportLegacy(),s.verification().exportLegacy())).toList(),
                result.events().stream().map(e->new MoveSearch.Event(export(e.source()),export(e.target()),e.move().exportLegacy(),e.decision(),e.verification()==null?null:e.verification().exportLegacy())).toList(),
                result.reachedStates().stream().map(NativeMoveSearch::export).collect(java.util.stream.Collectors.toSet()),
                result.deadEndStates().stream().map(NativeMoveSearch::export).toList(),result.metrics(),result.completeBoundedRelation(),assessments,null,incrementalProviders==null?null:
                    new StagedIncrementalMoveExecution(REVISION,StagedIncrementalMoveExecution.ORDER_REVISION,incrementalProviders,
                        cursorReceipts().stream().map(r->new StagedIncrementalMoveExecution.Expansion(export(r.source()),r.closed(),r.lanes())).toList()));
        }
    }
    public record QualityResult(Result search,TypedMoveSearch.State incumbent,long inputScore,long outputScore,
            List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness,long replayWork,long workBudget) {
        public QualityResult { witness=List.copyOf(witness); }
        public long totalWork(){return Math.addExact(search.metrics().totalWork(),replayWork);}
        public boolean withinBudget(){return search.accountingComplete() && totalWork()<=workBudget;}
    }
    public QualityResult searchUntil(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,SearchContinuationContract continuation){
        return select(problem,objective,maximumOutputScore,true,continuation);
    }
    /** Best admitted incumbent under the fixed budget; does not stop at an adequate score. */
    public QualityResult searchBest(Problem problem,TypedSourceOnlySearch.Objective objective,SearchContinuationContract continuation){
        return select(problem,objective,0,false,continuation);
    }
    public static final class FinalCheckFailure extends IllegalStateException {
        private final QualityResult attempted;
        private final NativeVerification rejected;
        private FinalCheckFailure(QualityResult attempted,NativeVerification rejected){
            super("independent native selected-path replay differs");this.attempted=attempted;this.rejected=rejected;
        }
        public QualityResult attempted(){return attempted;}
        public NativeVerification rejected(){return rejected;}
    }
    private QualityResult select(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,
            boolean stopAtQuality,SearchContinuationContract continuation){
        Objects.requireNonNull(objective);
        if(!problem.context().sourceOnly())throw new IllegalArgumentException("source-only context required");
        validate(problem);
        var selection=new MoveSearchObjective<TypedMoveSearch.State,NativeSearchMove,NativeVerification>(state->{
            var score=objective.evaluate(state);return new MoveSearch.ObjectiveScore(score.value(),score.work());
        },maximumOutputScore,stopAtQuality);
        try(var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var execution=new Execution(problem,store);
            var result=new Result(problem,new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>()
                .search(execution,continuation,selection));
            var replay=replay(execution,problem.source(),selection.incumbent().expression(),selection.witness());
            var quality=new QualityResult(result,selection.incumbent(),selection.inputScore(),selection.outputScore(),selection.witness(),replay.work(),problem.budget().totalWork());
            if(replay.rejected()!=null)throw new FinalCheckFailure(quality,replay.rejected());
            return quality;
        }
    }
    public Result search(Problem problem,SearchContinuationContract continuation,SearchExpressionStore.Limits limits){
        Objects.requireNonNull(limits);
        try(var store=new SearchExpressionStore(limits)) {
            var accounting=new NativeRetentionSession(problem,store,limits);
            accounting.validate(problem.source());if(problem.context().goal()!=null)accounting.validate(problem.context().goal());
            var execution=new Execution(problem,store,accounting);
            var searched=new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>()
                .search(execution,continuation,null);
            var replay=searched.outcome()==MoveSearch.Outcome.TARGET_REACHED
                ?replay(execution,problem.source(),problem.context().goal(),searched.witness()):new Replay(0,null);
            var result=new Result(problem,searched,replay.work());accounting.finish(result);
            if(replay.rejected()!=null)throw new TargetCheckFailure(result,replay.rejected());
            return result;
        }
    }
    public Result search(Problem problem,SearchContinuationContract continuation){
        validate(problem);
        try(var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            var execution=new Execution(problem,store);
            var searched=new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>()
                .search(execution,continuation,null);
            var replay=searched.outcome()==MoveSearch.Outcome.TARGET_REACHED
                ?replay(execution,problem.source(),problem.context().goal(),searched.witness()):new Replay(0,null);
            var result=new Result(problem,searched,replay.work());
            if(replay.rejected()!=null)throw new TargetCheckFailure(result,replay.rejected());
            return result;
        }
    }
    public static final class TargetCheckFailure extends IllegalStateException {
        private final Result attempted;private final NativeVerification rejected;
        private TargetCheckFailure(Result attempted,NativeVerification rejected){
            super("independent native target replay differs");this.attempted=attempted;this.rejected=rejected;
        }
        public Result attempted(){return attempted;}
        public NativeVerification rejected(){return rejected;}
    }
    private record Replay(long work,NativeVerification rejected) {}
    private static Replay replay(Execution execution,Expr source,Expr target,
            List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness){
        long work=0;
        TypedMoveSearch.State cursor=witness.isEmpty()?null:witness.getFirst().source();
        if(cursor!=null && (!cursor.expression().equals(source) || cursor.searchDepth()!=0))
            throw new IllegalStateException("native replay root differs");
        for(var step:witness) {
            if(!cursor.equals(step.source()))throw new IllegalStateException("broken native witness lineage");
            var checked=execution.verify(cursor,step.move());work=Math.addExact(work,checked.work());
            if(!checked.accepted() || !checked.equals(step.verification()))return new Replay(work,checked);
            cursor=step.target();
        }
        if(!(cursor==null?source:cursor.expression()).equals(target))throw new IllegalStateException("native replay endpoint differs");
        return new Replay(work,null);
    }
    private static void validate(Problem problem){
        de.regelsuche.search.program.AstExpressionValidation.inspect(problem.source());
        if(problem.context().goal()!=null)de.regelsuche.search.program.AstExpressionValidation.inspect(problem.context().goal());
    }
    private static final class Execution implements SearchExecution.Environment<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>,RetainedGraph.View {
        private final Problem problem;private final SearchExpressionStore store;
        private final NativeRetentionSession accounting;
        Execution(Problem problem,SearchExpressionStore store){this(problem,store,null);}
        Execution(Problem problem,SearchExpressionStore store,NativeRetentionSession accounting){this.problem=Objects.requireNonNull(problem);this.store=store;this.accounting=accounting;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(problem);v.reference(store);v.reference(accounting);}
        @Override public void ownership(RetainedGraph.View root){if(accounting!=null)accounting.ownership(root);}
        @Override public void checkpoint(){if(accounting!=null)accounting.checkpoint();}
        @Override public long additionalWork(){return accounting==null?0:accounting.work();}
        @Override public boolean ownershipComplete(){return accounting==null || accounting.complete();}
        @Override public Expr source(){return problem.source();}
        @Override public Expr goal(){return problem.context().goal();}
        @Override public List<String> initialAssumptions(){return problem.context().initialAssumptions();}
        @Override public MoveSearch.Budget budget(){return problem.budget();}
        @Override public MoveSearch.Mode mode(){return problem.mode();}
        @Override public MoveSearch.Scheduling scheduling(){return problem.scheduling();}
        @Override public TypedMoveSearch.State state(Expr expression,int depth,int primitive,String previous,List<String> assumptions,Set<String> capabilities,int debt){
            if(accounting==null)de.regelsuche.search.program.AstExpressionValidation.inspect(expression);else accounting.validate(expression);
            return new TypedMoveSearch.State(store.dereference(store.intern(expression)),depth,primitive,previous,assumptions,capabilities,debt);
        }
        @Override public NativeStateValue.Assessment inspect(TypedMoveSearch.State state){return problem.stateValue().evaluate(state,problem.context());}
        @Override public double score(TypedMoveSearch.State state){return problem.stateScore().applyAsDouble(state);}
        @Override public boolean carries(List<String> assumptions,TypedMoveSearch.State state){
            var available=new HashSet<>(initialAssumptions());available.addAll(state.assumptions());return available.containsAll(assumptions);
        }
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move){
            return problem.verifier().verify(state,move,problem.context());
        }
        @Override public SearchExecution.Picker<NativeSearchMove> picker(TypedMoveSearch.State state){
            var ranking=new SearchBatches.Ranking<NativeSearchMove>() {
                @Override public double score(NativeSearchMove move){return problem.policy().score(move,state,problem.context());}
                @Override public int stage(MoveProvider.Descriptor descriptor){return problem.policy().stage(descriptor,state,problem.context()).ordinal();}
                @Override public double providerScore(MoveProvider.Descriptor descriptor){return problem.policy().providerScore(descriptor,state,problem.context());}
                @Override public long contextWork(){return problem.policy().contextWork(state,problem.context());}
                @Override public void requireSource(NativeSearchMove move){move.requireSource(state.expression());}
            };
            if(scheduling()==MoveSearch.Scheduling.STAGED_INCREMENTAL)return new StagedIncrementalLanes<>(problem.providers().stream()
                .map(p->NativeIncrementalSources.lane(p,state,problem.context())).toList(),ranking,state);
            var providers=problem.providers().stream().<SearchBatches.Provider<NativeSearchMove>>map(p->new SearchBatches.Provider<>() {
                @Override public MoveProvider.Descriptor descriptor(){return p.descriptor();}
                @Override public SearchBatches.Batch<NativeSearchMove> candidates(){
                    var batch=p.candidates(state,problem.context());
                    return new SearchBatches.Batch<>(batch.moves(),batch.work(),batch.complete());
                }
            }).toList();
            return scheduling()==MoveSearch.Scheduling.STAGED?new StagedBatchPicker<>(providers,ranking):new EagerBatchPicker<>(providers,ranking);
        }
    }
    private static StateValue.Assessment export(NativeStateValue.Assessment value){
        var capabilities=new TreeMap<String,StateValue.Capability>();var codec=new CompiledAstReplayCodec();
        value.capabilities().forEach((key,c)->capabilities.put(key,new StateValue.Capability(c.providerId(),codec.encodeExpression(c.sourceExpression()),c.subtreePath(),codec.encodeExpression(c.matchedExpression()),codec.encodeExpression(c.rewrittenExpression()))));
        return new StateValue.Assessment(value.complexity(),value.value(),value.searchWork(),value.primitiveWork(),capabilities);
    }
    static MoveState export(TypedMoveSearch.State state){return new MoveState(new CompiledAstReplayCodec().encodeExpression(state.expression()),state.searchDepth(),state.primitiveDepth(),state.previousRule(),state.assumptions(),state.capabilities(),state.complexityDebt());}
}
