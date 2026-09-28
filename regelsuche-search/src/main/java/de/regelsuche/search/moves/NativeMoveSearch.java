package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import de.regelsuche.transform.*;
import java.util.*;

/** Explicit Expr execution through the same frontier and batch pickers as the historical facade. */
public final class NativeMoveSearch {
    public static final String REVISION = "regelsuche.native-expr-move-search/v1";
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
            if(!NativeMoveProvider.carries(move.assumptions(),source,context))return new NativeVerification(false,1,null,null,"TYPED_PRIMITIVE_ASSUMPTIONS_MISSING");
            var generated=transport.generate(source.expression());
            boolean accepted=descriptor.equals(move.descriptor()) && source.expression().equals(move.sourceExpression())
                && move.proof() instanceof NativeMoveProof.Primitive proof && generated.contains(proof.step());
            return new NativeVerification(accepted,TransformationWorkMetrics.flatEngine(generated.size()).totalWorkUnits(),
                accepted?move.proof():null,accepted?move.ruleId():null,accepted?"TYPED_PRIMITIVE_REPLAYED":"TYPED_PRIMITIVE_REPLAY_REJECTED");
        }
    }
    public record Problem(Expr source, TypedMoveSearch.Context context, List<NativeMoveProvider> providers,
            MoveSearch.Mode mode, MoveSearch.Scheduling scheduling, MoveSearch.Budget budget) {
        public Problem {
            Objects.requireNonNull(source);Objects.requireNonNull(context);providers=List.copyOf(providers);
            Objects.requireNonNull(mode);Objects.requireNonNull(scheduling);Objects.requireNonNull(budget);
            if(context.phase()==MoveContext.Phase.PRODUCTION)throw new IllegalArgumentException("experimental scheduling is not production-qualified (#745)");
            if(scheduling!=MoveSearch.Scheduling.STAGED && scheduling!=MoveSearch.Scheduling.EAGER_CONTROL)
                throw new IllegalArgumentException("native provider does not implement this scheduling contract");
        }
    }
    record Assessment(int complexity,double value,long searchWork,long primitiveWork,Map<String,SearchExecution.Capability<Expr>> capabilities)
            implements SearchExecution.Assessment<Expr> {
        static final Assessment EMPTY=new Assessment(0,0,0,0,Map.of());
    }
    public static final class Result {
        private final SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,Assessment> result;
        private final Expr source;
        private Result(Expr source,SearchExecution.Result<TypedMoveSearch.State,NativeSearchMove,NativeVerification,Assessment> result){this.source=source;this.result=result;}
        public MoveSearch.Outcome outcome(){return result.outcome();}
        public Expr output(){return result.witness().isEmpty()?source:result.witness().getLast().target().expression();}
        public List<SearchExecution.Step<TypedMoveSearch.State,NativeSearchMove,NativeVerification>> witness(){return result.witness();}
        public MoveSearch.Metrics metrics(){return result.metrics();}
        public MoveSearch.Result exportLegacy(){
            var assessments=new HashMap<MoveState,StateValue.Assessment>();
            result.stateAssessments().forEach((state,value)->assessments.put(export(state),StateValue.Assessment.EMPTY));
            return new MoveSearch.Result(result.outcome(),result.witness().stream().map(s->new MoveSearch.WitnessStep(export(s.source()),export(s.target()),s.move().exportLegacy(),s.verification().exportLegacy())).toList(),
                result.events().stream().map(e->new MoveSearch.Event(export(e.source()),export(e.target()),e.move().exportLegacy(),e.decision(),e.verification()==null?null:e.verification().exportLegacy())).toList(),
                result.reachedStates().stream().map(NativeMoveSearch::export).collect(java.util.stream.Collectors.toSet()),
                result.deadEndStates().stream().map(NativeMoveSearch::export).toList(),result.metrics(),result.completeBoundedRelation(),assessments);
        }
    }
    public record QualityResult(Result search,TypedMoveSearch.State incumbent,long inputScore,long outputScore,
            long replayWork,long workBudget) {
        public long totalWork(){return Math.addExact(search.metrics().totalWork(),replayWork);}
        public boolean withinBudget(){return totalWork()<=workBudget;}
    }
    public QualityResult searchUntil(Problem problem,TypedSourceOnlySearch.Objective objective,long maximumOutputScore,SearchContinuationContract continuation){
        throw new UnsupportedOperationException("native source-only final replay is not implemented");
    }
    public Result search(Problem problem,SearchContinuationContract continuation){
        de.regelsuche.search.program.AstExpressionValidation.inspect(problem.source());
        if(problem.context().goal()!=null)de.regelsuche.search.program.AstExpressionValidation.inspect(problem.context().goal());
        try(var store=new SearchExpressionStore(SearchExpressionStore.Limits.DEFAULT)) {
            return new Result(problem.source(),new MoveSearchKernel<Expr,TypedMoveSearch.State,NativeSearchMove,Assessment,NativeVerification>()
                .search(new Execution(problem,store),continuation,null));
        }
    }
    private static final class Execution implements SearchExecution.Environment<Expr,TypedMoveSearch.State,NativeSearchMove,Assessment,NativeVerification> {
        private final Problem problem;private final SearchExpressionStore store;
        Execution(Problem problem,SearchExpressionStore store){this.problem=Objects.requireNonNull(problem);this.store=store;}
        @Override public Expr source(){return problem.source();}
        @Override public Expr goal(){return problem.context().goal();}
        @Override public List<String> initialAssumptions(){return problem.context().initialAssumptions();}
        @Override public MoveSearch.Budget budget(){return problem.budget();}
        @Override public MoveSearch.Mode mode(){return problem.mode();}
        @Override public MoveSearch.Scheduling scheduling(){return problem.scheduling();}
        @Override public TypedMoveSearch.State state(Expr expression,int depth,int primitive,String previous,List<String> assumptions,Set<String> capabilities,int debt){
            de.regelsuche.search.program.AstExpressionValidation.inspect(expression);
            return new TypedMoveSearch.State(store.dereference(store.intern(expression)),depth,primitive,previous,assumptions,capabilities,debt);
        }
        @Override public Assessment inspect(TypedMoveSearch.State state){return Assessment.EMPTY;}
        @Override public double score(TypedMoveSearch.State state){return 0;}
        @Override public boolean carries(List<String> assumptions,TypedMoveSearch.State state){
            var available=new HashSet<>(initialAssumptions());available.addAll(state.assumptions());return available.containsAll(assumptions);
        }
        @Override public NativeVerification verify(TypedMoveSearch.State state,NativeSearchMove move){
            var provider=problem.providers().stream().filter(p->p.descriptor().equals(move.descriptor())).findFirst();
            return provider.isEmpty()?new NativeVerification(false,1,null,null,"UNREGISTERED_NATIVE_PROVIDER")
                :provider.orElseThrow().verify(state,move,problem.context());
        }
        @Override public SearchExecution.Picker<NativeSearchMove> picker(TypedMoveSearch.State state){
            var providers=problem.providers().stream().<SearchBatches.Provider<NativeSearchMove>>map(p->new SearchBatches.Provider<>() {
                @Override public MoveProvider.Descriptor descriptor(){return p.descriptor();}
                @Override public SearchBatches.Batch<NativeSearchMove> candidates(){
                    var batch=p.candidates(state,problem.context());
                    return new SearchBatches.Batch<>(batch.moves(),batch.work(),batch.complete());
                }
            }).toList();
            var ranking=new SearchBatches.Ranking<NativeSearchMove>() {
                @Override public double score(NativeSearchMove move){return 0;}
                @Override public int stage(MoveProvider.Descriptor descriptor){return MovePriorityPolicy.INVENTORY_ORDER.stage(descriptor,null,null).ordinal();}
                @Override public double providerScore(MoveProvider.Descriptor descriptor){return 0;}
                @Override public long contextWork(){return 0;}
                @Override public void requireSource(NativeSearchMove move){move.requireSource(state.expression());}
            };
            return scheduling()==MoveSearch.Scheduling.STAGED?new StagedBatchPicker<>(providers,ranking):new EagerBatchPicker<>(providers,ranking);
        }
    }
    static MoveState export(TypedMoveSearch.State state){return new MoveState(new CompiledAstReplayCodec().encodeExpression(state.expression()),state.searchDepth(),state.primitiveDepth(),state.previousRule(),state.assumptions(),state.capabilities(),state.complexityDebt());}
}
