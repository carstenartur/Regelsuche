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
    private static void require(boolean ok,String message) { assertions++;if(!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var t=new RecurrenceInvariantFamilyTest();
        t.infersCoefficientDependenceAndTransfersToNewRationalValues();
        t.refusesAnInterpolationThatOnlyFitsItsTwoExamples();
        t.handlesAnOffsetParameterWithoutDependingOnSolverNormalization();
        t.transfersFixedLawsAsWellAsAlternatingLaws();
        t.rejectsWrongSourcesForgedAnchorsAndMultipleVaryingEntries();
        t.sharesWorkBoundsAndDoesNotPublishAnUnpaidFamily();
        System.out.println("PASS symbolic recurrence-family assertions="+assertions);
    }
}
