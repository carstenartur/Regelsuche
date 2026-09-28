package de.regelsuche.search.moves;

import java.util.List;
import java.util.Set;

/** Original public records enter/leave the common frontier without changing their serialization. */
final class LegacySearchExecution implements SearchExecution.Environment<String,MoveState,SearchMove,StateValue.Assessment,MoveVerifier.Verification> {
    private final MoveSearch.Problem problem;
    LegacySearchExecution(MoveSearch.Problem problem){this.problem=problem;}
    @Override public String source(){return problem.source();}
    @Override public String goal(){return problem.context().goal();}
    @Override public List<String> initialAssumptions(){return problem.context().initialAssumptions();}
    @Override public MoveSearch.Budget budget(){return problem.budget();}
    @Override public MoveSearch.Mode mode(){return problem.mode();}
    @Override public MoveSearch.Scheduling scheduling(){return problem.scheduling();}
    @Override public MoveState state(String expression,int depth,int primitive,String previous,List<String> assumptions,Set<String> capabilities,int debt){return new MoveState(expression,depth,primitive,previous,assumptions,capabilities,debt);}
    @Override public StateValue.Assessment inspect(MoveState state){return problem.stateValue().evaluate(state,problem.context());}
    @Override public double score(MoveState state){return problem.stateScore().applyAsDouble(state);}
    @Override public MoveVerifier.Verification verify(MoveState state,SearchMove move){return problem.verifier().verify(state,move,problem.context());}
    @Override public boolean carries(List<String> assumptions,MoveState state){return problem.context().carries(assumptions,state);}
    @Override public MovePicker picker(MoveState state){
        return switch(problem.scheduling()) {
            case STAGED_INCREMENTAL -> new IncrementalMovePicker(problem.providers(),problem.policy(),state,problem.context());
            case INCREMENTAL_NATIVE_ORDER -> new IncrementalMovePicker(problem.providers(),state,problem.context());
            case STAGED -> new StagedMovePicker(problem.providers(),problem.policy(),state,problem.context());
            case EAGER_CONTROL -> new EagerMovePicker(problem.providers(),problem.policy(),state,problem.context());
        };
    }
    static MoveSearch.WitnessStep step(SearchExecution.Step<MoveState,SearchMove,MoveVerifier.Verification> step){
        return new MoveSearch.WitnessStep(step.source(),step.target(),step.move(),step.verification());
    }
    static MoveSearch.Result project(MoveSearch.Problem problem,SearchExecution.Result<MoveState,SearchMove,MoveVerifier.Verification,StateValue.Assessment> result){
        IncrementalMoveExecution nativeReceipt=null; StagedIncrementalMoveExecution stagedReceipt=null;
        if(problem.scheduling()==MoveSearch.Scheduling.INCREMENTAL_NATIVE_ORDER)
            nativeReceipt=new IncrementalMoveExecution(IncrementalMoveExecution.WORK_REVISION,IncrementalMoveExecution.ORDER_REVISION,
                problem.providers().stream().map(p->new IncrementalMoveExecution.Provider(p.descriptor(),((IncrementalMoveProvider)p).definition())).toList(),
                result.pickerReceipts().stream().map(IncrementalMoveExecution.Expansion.class::cast).toList());
        if(problem.scheduling()==MoveSearch.Scheduling.STAGED_INCREMENTAL)
            stagedReceipt=new StagedIncrementalMoveExecution(StagedIncrementalMoveExecution.WORK_REVISION,StagedIncrementalMoveExecution.ORDER_REVISION,
                problem.providers().stream().map(p->new StagedIncrementalMoveExecution.Provider(p.descriptor(),StagedIncrementalSources.definition(p))).toList(),
                result.pickerReceipts().stream().map(StagedIncrementalMoveExecution.Expansion.class::cast).toList());
        return new MoveSearch.Result(result.outcome(),result.witness().stream().map(LegacySearchExecution::step).toList(),
            result.events().stream().map(e->new MoveSearch.Event(e.source(),e.target(),e.move(),e.decision(),e.verification())).toList(),
            result.reachedStates(),result.deadEndStates(),result.metrics(),result.completeBoundedRelation(),result.stateAssessments(),nativeReceipt,stagedReceipt);
    }
}
