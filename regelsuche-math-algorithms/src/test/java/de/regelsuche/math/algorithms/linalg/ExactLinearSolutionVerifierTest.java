package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExactLinearSolutionVerifierTest {
    private final ExactLinearSolutionVerifier verifier = new ExactLinearSolutionVerifier();
    private final LinearRepresentationPlanner planner = new LinearRepresentationPlanner();
    private static final Budget LARGE = new Budget(1_000_000);
    private static ExactVector v(long... values) { return new ExactVector(java.util.Arrays.stream(values).mapToObj(Rational::of).toList()); }
    private static ExactLinearSolutionConsequence family(List<String> vars, ExactVector p, ExactVector... basis) {
        return new ExactLinearSolutionConsequence(vars, basis.length == 0 ? SolutionClassification.UNIQUE : SolutionClassification.UNDERDETERMINED,
            Optional.of(p), List.of(basis), Optional.empty());
    }
    private static ExactLinearSolutionCertificate identity(int rows) {
        return new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA, List.of(new ExactLinearSolutionCertificate.Part(
            java.util.stream.IntStream.range(0, rows).boxed().toList(), List.of())));
    }
    @Test void provesCompletenessAndRejectsResidualOnlyFamilies() {
        var source = List.of("x+y=1");
        var proof = identity(1);
        assertTrue(verifier.verify(source, family(List.of("x","y"),v(1,0),v(-1,1)),proof,LARGE).verified());
        assertFalse(verifier.verify(source, family(List.of("x","y"),v(1,0)),proof,LARGE).verified());
        assertFalse(verifier.verify(source, family(List.of("x","y"),v(0,1),v(-1,1)),proof,LARGE).verified());
        assertFalse(verifier.verify(source, family(List.of("x","y"),v(1,0),v(-2,2)),proof,LARGE).verified());
        assertFalse(verifier.verify(List.of("x+y=2"),family(List.of("x","y"),v(1,0),v(-1,1)),proof,LARGE).verified());
        var wide=List.of("x+y+z=1");
        assertFalse(verifier.verify(wide,family(List.of("x","y","z"),v(1,0,0),v(-1,1,0)),proof,LARGE).verified());
        assertFalse(verifier.verify(wide,family(List.of("x","y","z"),v(1,0,0),v(-1,1,0),v(-1,1,0)),proof,LARGE).verified());
        assertFalse(verifier.verify(List.of("x-x+y=1"),family(List.of("y"),v(1)),proof,LARGE).verified());
        var inconsistent = new ExactLinearSolutionConsequence(List.of("x","y"),SolutionClassification.INCONSISTENT,Optional.empty(),List.of(),Optional.of(Rational.ONE));
        assertFalse(verifier.verify(source,inconsistent,proof,LARGE).verified());
    }
    @Test void certifiedRoutesPreserveLegacyAndVerify() {
        var sources=List.of(List.of("2*x+y=3","x-y=0"),List.of("x+y=1","2*x+2*y=2"),List.of("x-x=0"),List.of("x=1","x=2","0=3"),List.of("x+y=1","a+b=2","2*x+2*y=2","2*a+2*b=4"));
        for(var source:sources) for(var route:LinearRepresentationPlanner.Route.values()) {
            var certified=planner.solveCertified(source,route,1_000_000,LARGE);
            assertEquals(planner.solve(source,route,1_000_000),certified.result());
            assertTrue(certified.certificate().isPresent(),source+" "+route);
            var checked=verifier.verify(source,certified.result().solution().orElseThrow(),certified.certificate().orElseThrow(),LARGE);
            assertTrue(checked.verified(),source+" "+route+" "+checked);
            assertTrue(planner.audit(certified.result(),1_000_000).verified());
        }
    }

    @Test void rejectsBrokenCoverageAndOperationSequences() {
        var source=List.of("2*x+y=3","x-y=0");
        var real=planner.solveCertified(source,LinearRepresentationPlanner.Route.DIRECT,100_000,LARGE);
        var claim=real.result().solution().orElseThrow();
        var ops=real.certificate().orElseThrow().parts().getFirst().operations();
        var broken=new java.util.ArrayList<ExactLinearSolutionCertificate>();
        broken.add(identity(1));
        broken.add(new ExactLinearSolutionCertificate("wrong",List.of()));
        for(var rows:List.of(List.of(0,0),List.of(0,2),List.of(1,0)))
            broken.add(new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,List.of(new ExactLinearSolutionCertificate.Part(rows,ops))));
        broken.add(new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,List.of(
            new ExactLinearSolutionCertificate.Part(List.of(0),List.of()),new ExactLinearSolutionCertificate.Part(List.of(0),List.of()))));
        broken.add(identity(2));
        var reversed=new java.util.ArrayList<>(ops);java.util.Collections.reverse(reversed);
        for(var changed:List.of(reversed,ops.subList(1,ops.size()),List.of(ExactRrefReduction.RowOperation.scale(2,Rational.ONE)),
            List.of(ExactRrefReduction.RowOperation.scale(0,Rational.of(3)))))
            broken.add(new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,List.of(new ExactLinearSolutionCertificate.Part(List.of(0,1),changed))));
        for(var proof:broken) {
            var checked=verifier.verify(source,claim,proof,LARGE);
            assertEquals(ExactLinearSolutionVerifier.Status.INVALID_CERTIFICATE,checked.status(),proof.toString());
            assertTrue(checked.ranks().isEmpty());
            assertTrue(checked.work().totalWork()>0);
        }
        assertFalse(verifier.verify(List.of("x=1","x=1"),family(List.of("x"),v(1)),identity(2),LARGE).verified());
        assertFalse(verifier.verify(List.of("x+y=1","y=0"),family(List.of("x","y"),v(1,0)),identity(2),LARGE).verified());
    }
    @Test void derivesRanksAndAcceptsCoefficientRrefWithSeveralContradictions() {
        for(var source:List.of(List.of("x=1","x=2","0=3"),List.of("x-x=1","0=2"),List.of("x=1","y-y=0","0=1"))) {
            var real=planner.solveCertified(source,LinearRepresentationPlanner.Route.DIRECT,100_000,LARGE);
            var checked=verifier.verify(source,real.result().solution().orElseThrow(),real.certificate().orElseThrow(),LARGE);
            assertTrue(checked.verified(),checked.toString());
            assertEquals(checked.ranks().orElseThrow().coefficient()+1,checked.ranks().orElseThrow().augmented());
        }
        var zero=verifier.verify(List.of("x-x+y-y=0"),family(List.of("x","y"),v(0,0),v(1,0),v(0,1)),identity(1),LARGE);
        assertEquals(Optional.of(new ExactLinearSolutionVerifier.Ranks(0,0)),zero.ranks());
        var wrongOrder=family(List.of("x","y"),v(0,0),v(0,1),v(1,0));
        assertFalse(verifier.verify(List.of("x-x+y-y=0"),wrongOrder,identity(1),LARGE).verified());
    }
    @Test void boundsEveryStageAndExportWithoutChangingConstruction() {
        var source=List.of("2*x+y=3","x-y=0");
        for(var route:LinearRepresentationPlanner.Route.values()) {
            var real=planner.solveCertified(source,route,100_000,LARGE);
            int export=real.certificateWork().consumedWork();
            for(int limit:new int[]{0,export-1,export}) {
                var bounded=planner.executeCertified(planner.prepare(source,100_000),route,100_000,new Budget(limit));
                assertEquals(real.result(),bounded.result());
                assertEquals(limit==export,bounded.certificate().isPresent());
                assertEquals(limit,bounded.certificateWork().consumedWork());
                assertEquals(limit==export?"EXPORTED":"BUDGET_INCONCLUSIVE",bounded.certificateWork().status());
            }
            var proof=real.certificate().orElseThrow();var claim=real.result().solution().orElseThrow();
            var full=verifier.verify(source,claim,proof,LARGE);var w=full.work();
            int required=w.totalWork();
            int[] boundaries={0,w.sourceBinding(),w.sourceBinding()+w.proofValidation(),w.sourceBinding()+w.proofValidation()+w.replay(),required-w.consequence(),required-1,required};
            for(int limit:boundaries) {
                var checked=verifier.verify(source,claim,proof,new Budget(limit));
                assertEquals(limit==required,checked.verified(),route+" "+limit);
                assertEquals(limit,checked.work().totalWork());
                if(limit<required) {assertEquals(ExactLinearSolutionVerifier.Status.BUDGET_INCONCLUSIVE,checked.status());assertTrue(checked.ranks().isEmpty());}
            }
        }
        var failed=planner.solveCertified(source,LinearRepresentationPlanner.Route.DIRECT,0,LARGE);
        assertTrue(failed.certificate().isEmpty());assertEquals("NOT_SOLVED",failed.certificateWork().status());
    }
    @Test void rejectsUnsupportedSourceAndExcessiveEvidence() {
        for(var text:List.of("x^0=1","0^0+x=1","0^(-1)+x=1","x*y=1","x/y=1","sin(x)=0","2^65+x=0","x^2=1","x=1e1000000","((2^64)^64)*x=1","x==1")) {
            var checked=verifier.verify(List.of(text),family(List.of("x"),v(1)),identity(1),LARGE);
            assertEquals(ExactLinearSolutionVerifier.Status.DOMAIN_UNSUPPORTED,checked.status(),text+" "+checked);
            assertTrue(checked.ranks().isEmpty());
        }
        for(var text:List.of("2^(-1)*x=1","x^(2-1)=1","2^21*x=1","0.1*x=0.3","(x-x)*x+x=1")) {
            var real=planner.solveCertified(List.of(text),LinearRepresentationPlanner.Route.DIRECT,100_000,LARGE);
            assertTrue(verifier.verify(List.of(text),real.result().solution().orElseThrow(),real.certificate().orElseThrow(),LARGE).verified(),text);
        }
        var huge=new Rational(java.math.BigInteger.ONE.shiftLeft(ExactLinearSolutionVerifier.MAX_RATIONAL_BITS+1),java.math.BigInteger.ONE);
        var proof=new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,List.of(new ExactLinearSolutionCertificate.Part(List.of(0),List.of(ExactRrefReduction.RowOperation.scale(0,huge)))));
        assertFalse(verifier.verify(List.of("x=1"),family(List.of("x"),v(1)),proof,LARGE).verified());
        proof=new ExactLinearSolutionCertificate(ExactLinearSolutionCertificate.SCHEMA,List.of(new ExactLinearSolutionCertificate.Part(List.of(0),java.util.Collections.nCopies(ExactLinearSolutionVerifier.MAX_OPERATIONS+1,ExactRrefReduction.RowOperation.scale(0,Rational.ONE)))));
        assertFalse(verifier.verify(List.of("x=1"),family(List.of("x"),v(1)),proof,LARGE).verified());
    }
    @Test void qualifiesDeterministicRationalSystemsAgainstUnchangedFullAudit() {
        long construction=0,export=0,checker=0,oracle=0,preparation=0,execution=0,composition=0;int cases=0;
        long[] stages=new long[5];
        java.util.Random random=new java.util.Random(7919);
        for(int sample=0;sample<30;sample++) {
            int m=1+sample%4,n=1+sample%3;
            var rows=new java.util.ArrayList<String>();
            for(int i=0;i<m;i++) {
                var terms=new java.util.ArrayList<String>();
                for(int j=0;j<n;j++) terms.add("("+(random.nextInt(7)-3)+"/"+(1+random.nextInt(3))+")*"+(char)('a'+j));
                rows.add(String.join("+",terms)+"="+(random.nextInt(7)-3));
            }
            for(var route:LinearRepresentationPlanner.Route.values()) {
                var real=planner.solveCertified(rows,route,1_000_000,LARGE);
                assertEquals("SOLVED",real.result().status(),rows+" "+route);
                var checked=verifier.verify(rows,real.result().solution().orElseThrow(),real.certificate().orElseThrow(),LARGE);
                var full=planner.audit(real.result(),1_000_000);
                assertTrue(checked.verified(),rows+" "+route+" "+checked);
                assertTrue(full.verified(),rows+" "+route+" "+full);
                construction+=real.result().totalWork();export+=real.certificateWork().consumedWork();checker+=checked.work().totalWork();oracle+=full.work();cases++;
                preparation+=real.result().preparationWork();execution+=real.result().executionWork();composition+=real.result().compositionWork();
                var w=checked.work();stages[0]+=w.sourceBinding();stages[1]+=w.proofValidation();stages[2]+=w.replay();stages[3]+=w.rank();stages[4]+=w.consequence();
            }
        }
        System.out.println("linear-semantic-qualification/v1 cases="+cases+" construction-v1="+construction+" export-v1="+export+" checker-v1="+checker+" full-audit-v1="+oracle
            +" preparation="+preparation+" execution="+execution+" composition="+composition+" checker-stages="+java.util.Arrays.toString(stages));
    }

    @Test void reportsSourceRationalOverflowAsDomainBoundary() {
        var source=List.of("("+"9".repeat(128)+")^64*x=1");
        var checked=verifier.verify(source,family(List.of("x"),v(1)),identity(1),LARGE);
        assertEquals(ExactLinearSolutionVerifier.Status.DOMAIN_UNSUPPORTED,checked.status());
        assertEquals("RATIONAL_SIZE_LIMIT",checked.detailCode());
    }
    @Test void preservesSixteenSyntacticCoordinatesAndAutomaticBlocks() {
        var rows=new java.util.ArrayList<String>();
        for(char c='a';c<='p';c++) rows.add(c+"-"+c+"=0");
        var real=planner.solveCertified(rows,LinearRepresentationPlanner.Route.AUTO,1_000_000,LARGE);
        assertEquals(LinearRepresentationPlanner.Route.BLOCKS,real.result().selected());
        var checked=verifier.verify(rows,real.result().solution().orElseThrow(),real.certificate().orElseThrow(),LARGE);
        assertTrue(checked.verified(),checked.toString());
        assertEquals(16,real.result().solution().orElseThrow().nullspaceBasis().size());
        assertEquals(Optional.of(new ExactLinearSolutionVerifier.Ranks(0,0)),checked.ranks());
        assertTrue(planner.audit(real.result(),1_000_000).verified());
    }

    @Test void metersMalformedSourceBeforeRejecting() {
        var checked=verifier.verify(List.of(),family(List.of("x"),v(1)),identity(1),LARGE);
        assertEquals(ExactLinearSolutionVerifier.Status.DOMAIN_UNSUPPORTED,checked.status());
        assertTrue(checked.work().sourceBinding()>0);
        assertEquals(ExactLinearSolutionVerifier.Status.BUDGET_INCONCLUSIVE,
            verifier.verify(List.of(),family(List.of("x"),v(1)),identity(1),new Budget(0)).status());
    }
}
