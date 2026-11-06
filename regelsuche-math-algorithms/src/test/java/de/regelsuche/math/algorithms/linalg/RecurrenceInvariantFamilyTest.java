package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantFamily.Anchor;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.Outcome;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RecurrenceInvariantFamilyTest {
    private static int assertions;
    private static final Budget BUDGET=new Budget(200_000);
    private final RecurrenceInvariantFamily inference=new RecurrenceInvariantFamily();
    private static List<Rational> values(long... n) { return java.util.Arrays.stream(n).mapToObj(Rational::of).toList(); }
    private static Transition source(Rational parameter) {
        return new Transition(new ExactMatrix(List.of(values(0,1),List.of(Rational.ONE,parameter))),new ExactVector(values(0,0)));
    }
    private static Anchor acquired(Transition source) {
        var initial=java.util.Collections.nCopies(source.linear().columns(),Rational.ONE);
        var result=new RecurrenceInvariantSynthesis().solve(new Request(source,initial,2,BUDGET));
        require(!result.laws().isEmpty(),"real synthesis supplies an anchor");
        return new Anchor(source,result.laws().getFirst().proposal());
    }
    @Test void infersCoefficientDependenceAndTransfersToNewRationalValues() {
        var formed=inference.form(acquired(source(Rational.ONE)),acquired(source(Rational.of(2))),2,BUDGET);
        require(formed.outcome()==Outcome.VERIFIED,"universal family derived from acquired anchors");
        for(var parameter: List.of(Rational.of(7),Rational.of(-5),Rational.ZERO,new Rational(BigInteger.ONE,BigInteger.TWO))) {
            var specialized=formed.family().orElseThrow().specialize(source(parameter),BUDGET);
            require(specialized.proposal().isPresent(),"new exact parameter specialized");
            var checked=new RecurrenceInvariantVerifier().verify(source(parameter),specialized.proposal().get(),values(3,4),BUDGET);
            require(checked.outcome()==Outcome.VERIFIED,"independent concrete proof required");
            require(checked.law().get().initialValue().abs().equals(Rational.of(12).multiply(parameter).subtract(Rational.of(7)).abs()),"new initial value exact up to monic scaling sign");
        }
    }
    @Test void refusesAnInterpolationThatOnlyFitsItsTwoExamples() {
        var one=new Transition(new ExactMatrix(List.of(values(1))),new ExactVector(values(0)));
        var minus=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(0)));
        var square=new Proposal("x0^2",Evolution.FIXED);
        var formed=inference.form(new Anchor(one,square),new Anchor(minus,square),2,BUDGET);
        require(formed.outcome()==Outcome.REJECTED && formed.family().isEmpty(),"p*x does not preserve x^2 for arbitrary p despite matching +/-1");
        require(formed.work().consumedWorkUnits()>0,"failed generalization stays paid");
    }
    @Test void handlesAnOffsetParameterWithoutDependingOnSolverNormalization() {
        var a=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(1)));
        var b=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(2)));
        var formed=inference.form(acquired(a),acquired(b),2,BUDGET);
        require(formed.outcome()==Outcome.VERIFIED,"offset family survives source normalizations");
        var target=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(7)));
        var specialized=formed.family().get().specialize(target,BUDGET);
        require(new RecurrenceInvariantVerifier().verify(target,specialized.proposal().orElseThrow(),values(3),BUDGET).outcome()==Outcome.VERIFIED,"affine offsets generalize");
    }
    @Test void transfersFixedLawsAsWellAsAlternatingLaws() {
        var a=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,3))),new ExactVector(values(0,0)));
        var b=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,4))),new ExactVector(values(0,0)));
        var formed=inference.form(acquired(a),acquired(b),2,BUDGET);
        require(formed.outcome()==Outcome.VERIFIED,"fixed evolution family");
        var target=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,-2))),new ExactVector(values(0,0)));
        var proposal=formed.family().orElseThrow().specialize(target,BUDGET).proposal().orElseThrow();
        require(proposal.evolution()==Evolution.FIXED,"not hardcoded to alternating families");
        require(new RecurrenceInvariantVerifier().verify(target,proposal,values(3,4),BUDGET).law().isPresent(),"fixed transferred law checked");
    }
    @Test void rejectsWrongSourcesForgedAnchorsAndMultipleVaryingEntries() {
        var a=acquired(source(Rational.ONE));var b=acquired(source(Rational.of(2)));
        var family=inference.form(a,b,2,BUDGET).family().orElseThrow();
        var wrong=new Transition(new ExactMatrix(List.of(values(1,1),values(1,7))),new ExactVector(values(0,0)));
        require(!family.matches(wrong) && family.specialize(wrong,BUDGET).proposal().isEmpty(),"fixed coefficients cannot drift");
        require(inference.form(a,new Anchor(b.transition(),new Proposal("x0^2+x1^2",Evolution.ALTERNATING)),2,BUDGET).family().isEmpty(),"invalid training theorem is rejected");
        require(inference.form(a,a,2,BUDGET).family().isEmpty(),"one source twice is not parameter evidence");
        require(inference.form(a,new Anchor(wrong,b.proposal()),2,BUDGET).family().isEmpty(),"more than one changing entry outside grammar");
        require(inference.form(a,b,1,BUDGET).family().isEmpty(),"quadratic evidence cannot claim degree one");
    }
    @Test void sharesWorkBoundsAndDoesNotPublishAnUnpaidFamily() {
        var a=acquired(source(Rational.ONE));var b=acquired(source(Rational.of(2)));
        var full=inference.form(a,b,2,BUDGET);int cost=full.work().consumedWorkUnits();
        for (int cap:new int[]{0,1,cost-1,cost}) {
            var run=inference.form(a,b,2,new Budget(cap));
            require(run.work().consumedWorkUnits()<=cap,"bounded formation work");
            require(run.family().isPresent()==(cap>=cost),"no free symbolic completion");
        }
        var family=full.family().orElseThrow();var target=source(Rational.of(7));
        int specialization=family.specialize(target,BUDGET).work().consumedWorkUnits();
        require(family.specialize(target,new Budget(specialization-1)).outcome()==Outcome.BUDGET_INCONCLUSIVE,"specialization is paid separately");
    }
    @Test void appliesAProvedFamilyWithFreshInitialValuesAndIndependentAudit() {
        var family=inference.form(acquired(source(Rational.ONE)),acquired(source(Rational.of(2))),2,BUDGET).family().orElseThrow();
        var verifier=new RecurrenceInvariantVerifier();
        var large=new Rational(BigInteger.ONE.shiftLeft(100),BigInteger.ONE);
        for(var parameter:List.of(Rational.of(7),Rational.of(-5),Rational.ZERO,new Rational(BigInteger.ONE,BigInteger.TWO))) {
            for(var initial:List.of(values(0,1),values(2,1),values(3,4),List.of(large,large.subtract(Rational.ONE)))) {
                var run=verifier.verifyFamily(family,source(parameter),initial,BUDGET);
                require(run.outcome()==Outcome.VERIFIED,"proved family produces a concrete verified law");
                var law=run.law().orElseThrow();
                var audit=verifier.verify(source(parameter),law.proposal(),initial,BUDGET);
                require(audit.outcome()==Outcome.VERIFIED,"independent full step proof still confirms every instance");
                require(law.transition().equals(source(parameter)) && law.initialState().equals(initial),"caller, not training source and state");
                require(law.initialValue().equals(audit.law().get().initialValue()),"fresh initial value, including exact large integers");
                require(law.initialValue().equals(evaluateNumerically(law.proposal().polynomial(),initial)),"numeric evaluation independent of the shared symbolic base-case checker");
                require(law.valueAt(BigInteger.valueOf(9)).equals(audit.law().get().valueAt(BigInteger.valueOf(9))),"same induction law");
                require(run.work().consumedWorkUnits()<audit.work().consumedWorkUnits(),"one universal proof replaces repeated step expansion");
            }
        }
    }
    @Test void checksEveryFixedSourceEntryAndAllApplicationBudgetBoundaries() {
        var family=inference.form(acquired(source(Rational.ONE)),acquired(source(Rational.of(2))),2,BUDGET).family().orElseThrow();
        var verifier=new RecurrenceInvariantVerifier(); var target=source(Rational.of(7));
        for(int cell: new int[]{0,1,2,3,5}) {
            var row0=new java.util.ArrayList<>(target.linear().rows().get(0));
            var row1=new java.util.ArrayList<>(target.linear().rows().get(1));
            var offset=new java.util.ArrayList<>(target.offset().values());
            if(cell%3==2) offset.set(cell/3,Rational.ONE);
            else { var row=cell<3?row0:row1; row.set(cell%3,row.get(cell%3).add(Rational.ONE)); }
            var wrong=new Transition(new ExactMatrix(List.of(row0,row1)),new ExactVector(offset));
            var run=verifier.verifyFamily(family,wrong,values(3,4),BUDGET);
            require(run.outcome()==Outcome.REJECTED && run.law().isEmpty(),"fixed matrix/offset entry remains bound: "+cell);
            require(run.work().consumedWorkUnits()>0,"source inspection stays paid");
        }
        var wrongDimension=new Transition(new ExactMatrix(List.of(values(1))),new ExactVector(values(0)));
        require(verifier.verifyFamily(family,wrongDimension,values(3),BUDGET).outcome()==Outcome.REJECTED,"dimension mismatch cannot inherit proof");
        int used=verifier.verifyFamily(family,target,values(3,4),BUDGET).work().consumedWorkUnits();
        for(int cap: new int[]{0,1,used/2,used-1,used,used+1}) {
            var run=verifier.verifyFamily(family,target,values(3,4),new Budget(cap));
            require(run.work().consumedWorkUnits()<=cap,"single non-resetting application budget");
            require(run.law().isPresent()==(cap>=used),"initial-value proof cannot escape a cutoff");
            require(run.outcome()==(cap>=used?Outcome.VERIFIED:Outcome.BUDGET_INCONCLUSIVE),"cutoff is not false mathematics");
        }
        rejectInitial(()->verifier.verifyFamily(family,target,values(1),BUDGET));
        var huge=new Rational(BigInteger.ONE.shiftLeft(129),BigInteger.ONE);
        rejectInitial(()->verifier.verifyFamily(family,target,List.of(huge,Rational.ONE),BUDGET));
    }
    @Test void reusesFixedAndAffineOffsetProofsWithoutASequenceSpecialCase() {
        var verifier=new RecurrenceInvariantVerifier();
        var a=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,3))),new ExactVector(values(0,0)));
        var b=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,4))),new ExactVector(values(0,0)));
        var target=new Transition(new ExactMatrix(List.of(values(0,1),values(-1,-2))),new ExactVector(values(0,0)));
        var family=inference.form(acquired(a),acquired(b),2,BUDGET).family().orElseThrow();
        var result=verifier.verifyFamily(family,target,values(3,4),BUDGET);
        require(result.law().orElseThrow().proposal().evolution()==Evolution.FIXED,"fixed evolution retained");
        require(verifier.verify(target,result.law().get().proposal(),values(3,4),BUDGET).law().get().initialValue()
            .equals(result.law().get().initialValue()),"independent fixed audit");
        a=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(1)));
        b=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(2)));
        target=new Transition(new ExactMatrix(List.of(values(-1))),new ExactVector(values(7)));
        family=inference.form(acquired(a),acquired(b),2,BUDGET).family().orElseThrow();
        result=verifier.verifyFamily(family,target,values(3),BUDGET);
        require(result.law().isPresent(),"offset parameter is not a special matrix-only path");
        require(verifier.verify(target,result.law().get().proposal(),values(3),BUDGET).law().get().initialValue()
            .equals(result.law().get().initialValue()),"independent affine audit");
    }
    @Test void sharedProofDoesNotShareMutableQueryState() throws Exception {
        var family=inference.form(acquired(source(Rational.ONE)),acquired(source(Rational.of(2))),2,BUDGET).family().orElseThrow();
        var jobs=new java.util.ArrayList<java.util.concurrent.Future<RecurrenceInvariantVerifier.Verification>>();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            for(int i=0;i<24;i++) {
                final int n=i;
                jobs.add(pool.submit(()->new RecurrenceInvariantVerifier().verifyFamily(family,source(Rational.of(n-12)),values(n,n+1),BUDGET)));
            }
            for(int i=0;i<jobs.size();i++) {
                var result=jobs.get(i).get(10,java.util.concurrent.TimeUnit.SECONDS);
                require(result.law().isPresent(),"concurrent proof application");
                var law=result.law().get();
                var full=new RecurrenceInvariantVerifier().verify(source(Rational.of(i-12)),law.proposal(),values(i,i+1),BUDGET);
                require(law.initialValue().equals(full.law().orElseThrow().initialValue()),"no query state crosses threads");
            }
        }
    }
    private static Rational evaluateNumerically(String text, List<Rational> initial) {
        var polynomial=new de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis().normalForm(text);
        Rational sum=Rational.ZERO;
        for(var term:polynomial.terms().entrySet()) {
            Rational value=term.getValue();
            for(int i=0;i<initial.size();i++)
                for(int power=0;power<term.getKey().exponentOf("x"+i);power++) value=value.multiply(initial.get(i));
            sum=sum.add(value);
        }
        return sum;
    }
    private static void rejectInitial(Runnable call) {
        try { call.run(); } catch(IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("invalid caller state must remain rejected");
    }
    private static void require(boolean ok,String message) { assertions++;if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var t=new RecurrenceInvariantFamilyTest();
        t.infersCoefficientDependenceAndTransfersToNewRationalValues();
        t.refusesAnInterpolationThatOnlyFitsItsTwoExamples();
        t.handlesAnOffsetParameterWithoutDependingOnSolverNormalization();
        t.transfersFixedLawsAsWellAsAlternatingLaws();
        t.rejectsWrongSourcesForgedAnchorsAndMultipleVaryingEntries();
        t.sharesWorkBoundsAndDoesNotPublishAnUnpaidFamily();
        t.appliesAProvedFamilyWithFreshInitialValuesAndIndependentAudit();
        t.checksEveryFixedSourceEntryAndAllApplicationBudgetBoundaries();
        t.reusesFixedAndAffineOffsetProofsWithoutASequenceSpecialCase();
        t.sharedProofDoesNotShareMutableQueryState();
        System.out.println("PASS symbolic recurrence-family assertions="+assertions);
    }
}
