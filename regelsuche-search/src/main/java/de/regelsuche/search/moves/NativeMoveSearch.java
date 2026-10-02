package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.*;

/** Explicit Expr execution through the same frontier and batch pickers as the historical facade. */
public final class NativeMoveSearch {
    public static final String REVISION = "regelsuche.native-expr-move-search/v5-partial-structural-index";
    /** Fixed release coverage, independent of mathematical proof validity or observed resource limits. */
    public enum Coverage { PARTIAL_ATOMIC_INVENTORY }
    public static Coverage coverage(){return Coverage.PARTIAL_ATOMIC_INVENTORY;}
    public record Primitive(MoveProvider.Descriptor descriptor, AstRewriteTransport transport) implements NativeMoveProvider,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(descriptor);v.reference(transport);}

        public Primitive {
            Objects.requireNonNull(descriptor);Objects.requireNonNull(transport);
            if(descriptor.sourceKind()!=SearchMove.SourceKind.PRIMITIVE)throw new IllegalArgumentException("primitive native provider required");
        }
        @Override public IncrementalProviderContract.Mathematics mathematicalKind(){return IncrementalProviderContract.Mathematics.PRIMITIVE;}
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
            return new NativeVerification(accepted,TransformationWorkMetrics.flatEngine(generated.size())
                    .withCandidateWork(new ExecutionWork(generated.size(),0,0)).totalWorkUnitsV2(),
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
        private long validationWork,executionWork,storageWork,retentionWork,peakNodes,peakCharacters,peakReferences,resultNodes,resultCharacters,resultReferences;
        private boolean complete,externalKnown;private long externalNodes,externalCharacters,externalReferences;private String detail="";
        void update(long validation,long execution,long storage,long retention,long pn,long pc,long pr,long rn,long rc,long rr,boolean complete,String detail){
            validationWork=validation;executionWork=execution;storageWork=storage;retentionWork=retention;peakNodes=pn;peakCharacters=pc;peakReferences=pr;
            resultNodes=rn;resultCharacters=rc;resultReferences=rr;this.complete=complete;this.detail=detail;
        }
        public long validationWork(){return validationWork;}
        public long executionWork(){return executionWork;}
        public long storageWork(){return storageWork;}
        public long retentionWork(){return retentionWork;}
        /** Post-close caller input graph, separate from and overlapping result ownership; empty means unknown. */
        public Optional<RetainedGraph.Usage> externalRetained(){return externalKnown?Optional.of(new RetainedGraph.Usage(externalNodes,externalCharacters,externalReferences)):Optional.empty();}
        void external(RetainedGraph.Usage usage,boolean known){externalNodes=usage.nodes();externalCharacters=usage.characters();externalReferences=usage.references();externalKnown=known;}
        /** Session-owned lookup/kernel graph after close, excluding result and caller input graphs. */
        public RetainedGraph.Usage live(){return new RetainedGraph.Usage(0,0,0);}
        /** Peak of observed graphs only; uninstrumented atomic temporaries make total coverage incomplete. */
        public RetainedGraph.Usage peak(){return new RetainedGraph.Usage(peakNodes,peakCharacters,peakReferences);}
        public RetainedGraph.Usage resultRetained(){return new RetainedGraph.Usage(resultNodes,resultCharacters,resultReferences);}
        /** No total work/retention qualification is available while the atomic inventory remains partial. */
        public boolean complete(){return false;}
        public Coverage coverage(){return NativeMoveSearch.coverage();}
        /** Diagnostic completion of installed observations, never evidence of complete accounting. */
        public boolean observationsComplete(){return complete;}
        public String detail(){return detail;}
        long work(){return Math.addExact(Math.addExact(Math.addExact(validationWork,executionWork),storageWork),retentionWork);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(detail);}
    }
    /** Separate output-phase receipt; never changes the already published search charge. */
    public static final class ExportAccounting implements RetainedGraph.View {
        private final long budget;
        private long work,peakNodes,peakCharacters,peakReferences,resultNodes,resultCharacters,resultReferences;
        private boolean complete;private String detail="";
        ExportAccounting(long budget){this.budget=budget;}
        void update(long work,long pn,long pc,long pr,RetainedGraph.Usage retained,boolean complete,String detail){
            this.work=work;peakNodes=pn;peakCharacters=pc;peakReferences=pr;
            resultNodes=retained.nodes();resultCharacters=retained.characters();resultReferences=retained.references();this.complete=complete;this.detail=detail;
        }
        /** Observed output work only; missing atomic helpers prevent a total-cost claim. */
        public long work(){return work;}public long budget(){return budget;}
        /** Peak of observed graphs only; uninstrumented atomic temporaries make total coverage incomplete. */
        public RetainedGraph.Usage peak(){return new RetainedGraph.Usage(peakNodes,peakCharacters,peakReferences);}
        public RetainedGraph.Usage resultRetained(){return new RetainedGraph.Usage(resultNodes,resultCharacters,resultReferences);}
        /** No total work/retention qualification is available while the atomic inventory remains partial. */
        public boolean complete(){return false;}
        public Coverage coverage(){return NativeMoveSearch.coverage();}
        /** Diagnostic completion of installed observations, never evidence of complete accounting. */
        public boolean observationsComplete(){return complete;}public String detail(){return detail;}
        public String workRevision(){return Result.EXPORT_REVISION;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(detail);}
    }
    public static final class ExportFailure extends IllegalStateException {
        private final ExportResult attempted;
        ExportFailure(ExportResult attempted){super(attempted.accounting().detail().isEmpty()?attempted.coverage().name():attempted.accounting().detail());this.attempted=attempted;}
        public ExportResult attempted(){return attempted;}
    }
    public static final class ExportResult implements RetainedGraph.View {
        private MoveSearch.Result projection;private final ExportAccounting accounting;
        ExportResult(MoveSearch.Result projection,ExportAccounting accounting){this.projection=projection;this.accounting=accounting;}
        void discard(){projection=null;}
        public MoveSearch.Result projection(){return projection;}public ExportAccounting accounting(){return accounting;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(projection);v.reference(accounting);}
        public boolean complete(){return accounting.complete();}
        /** A diagnostic artefact was materialized; this does not qualify its costs or bounded relation. */
        public boolean artifactAvailable(){return projection!=null;}
        public Coverage coverage(){return accounting.coverage();}
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
        /** Managed cursor receipts from eager/staged batch draining; separate from lane scheduling receipts. */
        public List<IncrementalProviderContract.Snapshot> batchCursorReceipts(){return result.batchCursorReceipts();}
        public Coverage coverage(){return NativeMoveSearch.coverage();}
        public boolean accountingComplete(){return false;}
        /** Installed observations completed; partial coverage still precludes total qualification. */
        public boolean observationsComplete(){return (accounting==null || accounting.observationsComplete())
            && batchCursorReceipts().stream().allMatch(receipt->receipt.accountingComplete() && receipt.status()!=IncrementalProviderContract.Status.FAILED)
            && cursorReceipts().stream().flatMap(r->r.lanes().stream()).allMatch(l->l.cursor()==null || l.cursor().accountingComplete());}
        public Accounting accounting(){if(accounting==null)throw new IllegalStateException("ownership accounting unavailable for this revision");return accounting;}
        public String workRevision(){return REVISION;}
        public long replayWork(){return replayWork;}
        /** Sum of observed search/replay/accounting work, not a complete execution-cost upper bound. */
        public long totalWork(){return Math.addExact(Math.addExact(metrics().totalWork(),replayWork),accounting==null?0:accounting.work());}
        public boolean withinBudget(){return accountingComplete() && totalWork()<=workBudget;}
        public MoveSearch.Outcome outcome(){return MoveSearch.Outcome.INCONCLUSIVE;}
        /** Diagnostic execution outcome including observed failures; never a total-budget qualification. */
        public MoveSearch.Outcome observedOutcome(){if(accounting!=null && !accounting.observationsComplete())return MoveSearch.Outcome.INCONCLUSIVE;return totalWork()>workBudget?MoveSearch.Outcome.WORK_EXHAUSTED:result.outcome();}
        public long workBudget(){return workBudget;}
        public Expr output(){return result.witness().isEmpty()?source:result.witness().getLast().target().expression();}
        public List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness(){return result.witness();}
        /** Frontier receipt. Independently paid final replay is separate; totalWork adds the other observed result costs. */
        public MoveSearch.Metrics metrics(){return result.metrics();}
        /** Independent finite output-phase budget; add its observed work once to totalWork; coverage remains incomplete. */
        public ExportResult exportLegacy(long workBudget,SearchExpressionStore.Limits limits){return NativeExportSession.run(this,workBudget,limits);}
        public static final long DEFAULT_EXPORT_WORK=10_000_000;
        public static final String EXPORT_REVISION="regelsuche.native-legacy-export/v2-partial-atomic-inventory";
        /** Requires fully qualified output accounting; partial coverage throws with its paid diagnostic artefact. */
        public MoveSearch.Result exportLegacy(){
            var exported=exportLegacy(DEFAULT_EXPORT_WORK,SearchExpressionStore.Limits.DEFAULT);
            if(!exported.complete())throw new ExportFailure(exported);return exported.projection();
        }
        MoveSearch.Result projectLegacy(){
            var assessments=new HashMap<MoveState,StateValue.Assessment>();
            var witness=new ArrayList<MoveSearch.WitnessStep>();var events=new ArrayList<MoveSearch.Event>();
            var reached=new HashSet<MoveState>();var dead=new ArrayList<MoveState>();
            var expansions=new ArrayList<StagedIncrementalMoveExecution.Expansion>();
            try(var retained=de.regelsuche.retention.RetainedOperation.retain(assessments,witness,events,reached,dead,expansions)) {
                for(var state:result.assessmentOrder()){
                    var assessment=result.stateAssessments().get(state);
                    de.regelsuche.retention.RetainedOperation.work(1);
                    assessments.put(export(state),export(assessment));de.regelsuche.retention.RetainedOperation.checkpoint();
                }
                for(var step:result.witness()){witness.add(new MoveSearch.WitnessStep(export(step.source()),export(step.target()),step.move().exportLegacy(),step.verification().exportLegacy()));de.regelsuche.retention.RetainedOperation.checkpoint();}
                for(var event:result.events()){events.add(new MoveSearch.Event(export(event.source()),export(event.target()),event.move().exportLegacy(),event.decision(),event.verification()==null?null:event.verification().exportLegacy()));de.regelsuche.retention.RetainedOperation.checkpoint();}
                for(var state:result.reachedOrder()){reached.add(export(state));de.regelsuche.retention.RetainedOperation.checkpoint();}
                for(var state:result.deadEndStates()){dead.add(export(state));de.regelsuche.retention.RetainedOperation.checkpoint();}
                if(incrementalProviders!=null)for(var receipt:cursorReceipts()){expansions.add(new StagedIncrementalMoveExecution.Expansion(export(receipt.source()),receipt.closed(),receipt.lanes()));de.regelsuche.retention.RetainedOperation.checkpoint();}
                de.regelsuche.retention.RetainedOperation.work(assessments.size()+witness.size()+events.size()+reached.size()+dead.size()+expansions.size()+7L);
                return de.regelsuche.retention.RetainedOperation.produced(new MoveSearch.Result(outcome(),witness,events,reached,dead,result.metrics(),false,assessments,null,
                    incrementalProviders==null?null:new StagedIncrementalMoveExecution(REVISION,StagedIncrementalMoveExecution.ORDER_REVISION,incrementalProviders,expansions)));
            }
        }
    }
    public record QualityResult(Result search,TypedMoveSearch.State incumbent,long inputScore,long outputScore,
            List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness,long replayWork,long workBudget) implements RetainedGraph.View {
        public QualityResult { witness=List.copyOf(witness); }
        /** Observed work only; see the native search coverage and accounting receipt. */
        public long totalWork(){return search.totalWork();}
        public boolean hasIncumbent(){return incumbent!=null;}
        @Override public long inputScore(){if(!hasIncumbent())throw new IllegalStateException("input was not scored");return inputScore;}
        @Override public long outputScore(){if(!hasIncumbent())throw new IllegalStateException("no scored incumbent");return outputScore;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(search);v.reference(incumbent);v.reference(witness);}
        public boolean withinBudget(){return search.accountingComplete() && totalWork()<=workBudget;}
    }
    public QualityResult searchUntil(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,SearchContinuationContract continuation){
        return select(problem,objective,maximumOutputScore,true,continuation,SearchExpressionStore.Limits.DEFAULT);
    }
    public QualityResult searchUntil(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,SearchContinuationContract continuation,SearchExpressionStore.Limits limits){
        return select(problem,objective,maximumOutputScore,true,continuation,Objects.requireNonNull(limits));
    }
    public QualityResult searchBest(Problem problem,TypedSourceOnlySearch.Objective objective,SearchContinuationContract continuation,SearchExpressionStore.Limits limits){
        return select(problem,objective,0,false,continuation,Objects.requireNonNull(limits));
    }
    /** Best admitted incumbent under the fixed budget; does not stop at an adequate score. */
    public QualityResult searchBest(Problem problem,TypedSourceOnlySearch.Objective objective,SearchContinuationContract continuation){
        return select(problem,objective,0,false,continuation,SearchExpressionStore.Limits.DEFAULT);
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
    private record ObjectiveAdapter(TypedSourceOnlySearch.Objective objective)
            implements java.util.function.Function<TypedMoveSearch.State,MoveSearch.ObjectiveScore>,RetainedGraph.View {
        @Override public MoveSearch.ObjectiveScore apply(TypedMoveSearch.State state){
            var score=objective.evaluate(state);return new MoveSearch.ObjectiveScore(score.value(),score.work());
        }
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(objective);}
    }
    private QualityResult select(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,
            boolean stopAtQuality,SearchContinuationContract continuation,SearchExpressionStore.Limits limits){
        Objects.requireNonNull(objective);
        if(!problem.context().sourceOnly())throw new IllegalArgumentException("source-only context required");
        var selection=new MoveSearchObjective<TypedMoveSearch.State,NativeSearchMove,NativeVerification>(
            new ObjectiveAdapter(objective),maximumOutputScore,stopAtQuality);
        try(var store=new SearchExpressionStore(limits)) {
            var accounting=new NativeRetentionSession(problem,store,limits);
            try(var operation=de.regelsuche.retention.RetainedOperation.open(accounting)) {
                accounting.operation(operation);accounting.externalObjective(objective);accounting.validate(problem.source());
                var execution=new Execution(problem,store,accounting);
                var searched=new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>()
                    .search(execution,continuation,selection);
                accounting.completed(searched,selection,execution);
                var replay=selection.incumbent()==null?new Replay(0,null):
                    replay(execution,problem.source(),selection.incumbent().expression(),selection.witness());
                var result=new Result(problem,searched,replay.work());
                var quality=new QualityResult(result,selection.incumbent(),selection.inputScore(),selection.outputScore(),selection.witness(),replay.work(),problem.budget().totalWork());
                accounting.finish(result,quality);
                if(replay.rejected()!=null)throw new FinalCheckFailure(quality,replay.rejected());
                return quality;
            }
        }
    }
    public Result search(Problem problem,SearchContinuationContract continuation,SearchExpressionStore.Limits limits){
        Objects.requireNonNull(limits);
        try(var store=new SearchExpressionStore(limits)) {
            var accounting=new NativeRetentionSession(problem,store,limits);
            try(var operation=de.regelsuche.retention.RetainedOperation.open(accounting)) {
            accounting.operation(operation);
            accounting.validate(problem.source());if(problem.context().goal()!=null)accounting.validate(problem.context().goal());
            var execution=new Execution(problem,store,accounting);
            var searched=new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>()
                .search(execution,continuation,null);
            accounting.completed(searched,null,execution);
            var replay=searched.outcome()==MoveSearch.Outcome.TARGET_REACHED
                ?replay(execution,problem.source(),problem.context().goal(),searched.witness()):new Replay(0,null);
            var result=new Result(problem,searched,replay.work());accounting.finish(result);
            if(replay.rejected()!=null)throw new TargetCheckFailure(result,replay.rejected());
            return result;
            }
        }
    }
    /** Default native execution includes validation, ownership observations, cleanup and fresh final replay. */
    public Result search(Problem problem,SearchContinuationContract continuation){
        return search(problem,continuation,SearchExpressionStore.Limits.DEFAULT);
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
            NativeVerification checked;
            try {checked=execution.verify(cursor,step.move());}
            catch(SearchExecution.ResourceLimit exhausted) {
                if(execution.accounting==null)throw exhausted;
                execution.accounting.incomplete("NATIVE_RESOURCE_LIMIT");
                return new Replay(Math.addExact(work,exhausted.takeWork().total()),null);
            }
            work=Math.addExact(work,checked.work());
            if(!checked.accepted() || !checked.equals(step.verification()))return new Replay(work,checked);
            cursor=step.target();
        }
        if(!(cursor==null?source:cursor.expression()).equals(target))throw new IllegalStateException("native replay endpoint differs");
        return new Replay(work,null);
    }
    private static final class Execution implements SearchExecution.Environment<Expr,TypedMoveSearch.State,NativeSearchMove,NativeStateValue.Assessment,NativeVerification>,RetainedGraph.View {
        private final Problem problem;private final SearchExpressionStore store;
        private final NativeRetentionSession accounting;
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
            try {return new TypedMoveSearch.State(store.dereference(store.intern(expression)),depth,primitive,previous,assumptions,capabilities,debt);}
            catch(SearchExpressionStore.LimitExceeded exhausted){if(accounting!=null)accounting.fail("NATIVE_RETENTION_EXHAUSTED");throw exhausted;}
        }
        @Override public NativeStateValue.Assessment inspect(TypedMoveSearch.State state){return problem.stateValue().evaluate(state,problem.context());}
        @Override public double score(TypedMoveSearch.State state){return problem.stateScore().applyAsDouble(state);}
        @Override public boolean carries(List<String> assumptions,TypedMoveSearch.State state){
            var available=new HashSet<>(initialAssumptions());available.addAll(state.assumptions());return available.containsAll(assumptions);
        }
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move){
            de.regelsuche.retention.RetainedOperation.work(1);
            return problem.verifier().verify(state,move,problem.context());
        }
        @Override public SearchExecution.Picker<NativeSearchMove> picker(TypedMoveSearch.State state){
            var ranking=new SearchBatches.Ranking<NativeSearchMove>() {
                @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(Execution.this);v.reference(state);}
                @Override public double score(NativeSearchMove move){return problem.policy().score(move,state,problem.context());}
                @Override public int stage(MoveProvider.Descriptor descriptor){return problem.policy().stage(descriptor,state,problem.context()).ordinal();}
                @Override public double providerScore(MoveProvider.Descriptor descriptor){return problem.policy().providerScore(descriptor,state,problem.context());}
                @Override public long contextWork(){return problem.policy().contextWork(state,problem.context());}
                @Override public void requireSource(NativeSearchMove move){move.requireSource(state.expression());}
            };
            if(scheduling()==MoveSearch.Scheduling.STAGED_INCREMENTAL)return new StagedIncrementalLanes<>(problem.providers().stream()
                .map(p->NativeIncrementalSources.lane(p,state,problem.context())).toList(),ranking,state);
            var providers=problem.providers().stream().<SearchBatches.Provider<NativeSearchMove>>map(p->new SearchBatches.Provider<>() {
                @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(Execution.this);v.reference(p);v.reference(state);}
                @Override public MoveProvider.Descriptor descriptor(){return p.descriptor();}
                @Override public SearchBatches.Batch<NativeSearchMove> candidates(){
                    var batch=p.candidates(state,problem.context());
                    return new SearchBatches.Batch<>(batch.moves(),batch.work(),batch.complete(),batch.cursorReceipts());
                }
            }).toList();
            return scheduling()==MoveSearch.Scheduling.STAGED?new StagedBatchPicker<>(providers,ranking):new EagerBatchPicker<>(providers,ranking,false);
        }
    }
    private static StateValue.Assessment export(NativeStateValue.Assessment value){
        var capabilities=new TreeMap<String,StateValue.Capability>();var codec=new CompiledAstReplayCodec();
        var text=new String[3];
        try(var retained=de.regelsuche.retention.RetainedOperation.retain(capabilities,text)) {
            for(var entry:value.capabilities().entrySet()) {
                var c=entry.getValue();text[0]=codec.encodeExpression(c.sourceExpression());text[1]=codec.encodeExpression(c.matchedExpression());text[2]=codec.encodeExpression(c.rewrittenExpression());
                capabilities.put(entry.getKey(),new StateValue.Capability(c.providerId(),text[0],c.subtreePath(),text[1],text[2]));
                de.regelsuche.retention.RetainedOperation.work(4);de.regelsuche.retention.RetainedOperation.checkpoint();
            }
            return de.regelsuche.retention.RetainedOperation.produced(new StateValue.Assessment(value.complexity(),value.value(),value.searchWork(),value.primitiveWork(),capabilities));
        }
    }
    static MoveState export(TypedMoveSearch.State state){return new MoveState(new CompiledAstReplayCodec().encodeExpression(state.expression()),state.searchDepth(),state.primitiveDepth(),state.previousRule(),state.assumptions(),state.capabilities(),state.complexityDebt());}
}
