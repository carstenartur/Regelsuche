package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.discovery.domain.FiniteDifferenceSequenceDomain.*;
import de.regelsuche.discovery.domain.LinearRecurrenceSequenceDomain.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainFiniteDataCheckTest {
    private static final String FD = "FINITE_DIFFERENCE_VALIDATION_NOT_FORMAL_PROOF";
    private static final String LR = "LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF";
    private static final DomainFiniteDataCheck.Budget BUDGET = DomainFiniteDataCheck.Budget.defaults();

    @Test void newtonSumIndependentlyChecksEveryObservedAndHeldOutTerm() {
        var candidate = squares(List.of(25L, 36L));
        var result = DomainFiniteDataCheck.check(candidate, squareWitness(List.of(1L,4L,9L,16L,25L,36L)), BUDGET);
        assertEquals(DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA, result.status());
        assertTrue(result.complete());
        var json = DomainExportWorkspace.read(result.toCanonicalJson());
        assertEquals(6, json.path("rows").size());
        assertEquals("OBSERVED", json.path("rows").get(3).path("partition").asText());
        assertEquals("HOLDOUT", json.path("rows").get(4).path("partition").asText());
        assertEquals("25", json.path("rows").get(4).path("computed").asText());
        assertEquals("UNAVAILABLE", json.path("work").path("totalArithmeticWork").asText());
    }

    @Test void aFalseObservedTermAndAFailedHoldoutAreActualFiniteCounterexamples() {
        var wrongPrefix = new FiniteDifferenceCandidate(2, List.of(1L,3L,2L), List.of(1L,4L,10L,16L), List.of(25L,36L));
        // The historical evaluator assumes prefix search already happened; repeating it alone misses this defect.
        assertEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED, new FiniteDifferenceSequenceDomain().evaluator().evaluate(wrongPrefix).status());
        var first = DomainFiniteDataCheck.check(wrongPrefix, squareWitness(List.of(1L,4L,9L,16L,25L,36L)), BUDGET);
        assertEquals(DomainFiniteDataCheck.Status.REFUTED_FINITE_DATA, first.status());
        assertEquals(2, DomainExportWorkspace.read(first.toCanonicalJson()).path("firstCounterexampleIndex").asInt());
        var holdout = DomainFiniteDataCheck.check(squares(List.of(26L,36L)), squareWitness(List.of(1L,4L,9L,16L,25L,36L)), BUDGET);
        assertEquals(DomainFiniteDataCheck.Status.REFUTED_FINITE_DATA, holdout.status());
        assertEquals(4, DomainExportWorkspace.read(holdout.toCanonicalJson()).path("firstCounterexampleIndex").asInt());
    }

    @Test void witnessDamageIsNotInventedAsACounterexampleToTheCandidate() {
        var result = DomainFiniteDataCheck.check(squares(List.of(25L,36L)), squareWitness(List.of(1L,4L,9L,16L,26L,36L)), BUDGET);
        assertEquals(DomainFiniteDataCheck.Status.INVALID_WITNESS, result.status());
        assertEquals("NONE_FOUND", DomainExportWorkspace.read(result.toCanonicalJson()).path("counterexampleStatus").asText());
        var changedCounts = new FiniteDifferenceCertificate(2,List.of(1L,3L,2L),List.of(1L,4L,9L,16L,25L,36L),3,3,FD);
        assertEquals(DomainFiniteDataCheck.Status.INVALID_WITNESS,
            DomainFiniteDataCheck.check(squares(List.of(25L,36L)), changedCounts, BUDGET).status());
    }

    @Test void recurrenceWindowsCrossTheObservedHoldoutBoundaryWithoutResetting() {
        var candidate = fibonacci(List.of(34L,55L,89L));
        var result = DomainFiniteDataCheck.check(candidate, fibonacciWitness(List.of("2","3","5","8","13","21","34","55","89")), BUDGET);
        assertEquals(DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA, result.status());
        var rows = DomainExportWorkspace.read(result.toCanonicalJson()).path("rows");
        assertEquals("SEED_IDENTITY", rows.get(1).path("kind").asText());
        assertEquals(4, rows.get(6).path("windowStartInclusive").asInt());
        assertEquals(6, rows.get(6).path("windowEndExclusive").asInt());
        assertEquals(5, rows.get(7).path("windowStartInclusive").asInt());
        assertEquals("0", rows.get(7).path("residual").asText());
        for (var altered : List.of(List.of(35L,55L,89L),List.of(34L,56L,89L))) {
            var rejected = DomainFiniteDataCheck.check(fibonacci(altered), fibonacciWitness(List.of("2","3","5","8","13","21","34","55","89")), BUDGET);
            assertEquals(DomainFiniteDataCheck.Status.REFUTED_FINITE_DATA, rejected.status());
            assertEquals(altered.getFirst() == 35L ? 6 : 7,
                DomainExportWorkspace.read(rejected.toCanonicalJson()).path("firstCounterexampleIndex").asInt());
        }
    }

    @Test void exactRationalCoefficientsAndSeedWitnessesAreCheckedWithoutTheDomainGenerator() {
        var model = new RecurrenceModel(1,List.of(new Rational(BigInteger.ONE,BigInteger.TWO)));
        var candidate = new LinearRecurrenceCandidate(model,List.of(16L,8L,4L),List.of(2L,1L));
        var witness = new LinearRecurrenceCertificate(model,List.of("16","8","4","2","1"),3,2,LR);
        assertEquals(DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA, DomainFiniteDataCheck.check(candidate,witness,BUDGET).status());
        var seedDamage = new LinearRecurrenceCertificate(model,List.of("17","8","4","2","1"),3,2,LR);
        assertEquals(DomainFiniteDataCheck.Status.INVALID_WITNESS, DomainFiniteDataCheck.check(candidate,seedDamage,BUDGET).status());
        var otherModel = new RecurrenceModel(1,List.of(Rational.of(2)));
        var wrongBinding = new LinearRecurrenceCertificate(otherModel,witness.generatedTerms(),3,2,LR);
        assertEquals(DomainFiniteDataCheck.Status.INVALID_WITNESS, DomainFiniteDataCheck.check(candidate,wrongBinding,BUDGET).status());
    }

    @Test void termAndArithmeticRefusalsRetainPrefixesAndNeverConfirm() {
        var candidate = squares(List.of(25L,36L));
        var witness = squareWitness(List.of(1L,4L,9L,16L,25L,36L));
        var partial = DomainFiniteDataCheck.check(candidate,witness,new DomainFiniteDataCheck.Budget(2,1000,1024));
        var json = DomainExportWorkspace.read(partial.toCanonicalJson());
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE, partial.status());
        assertFalse(partial.complete());
        assertEquals(2,json.path("rows").size());
        assertEquals("TERM_CHECKS",json.path("work").path("refusedDimension").asText());
        var noArithmetic = DomainFiniteDataCheck.check(candidate,witness,new DomainFiniteDataCheck.Budget(256,0,1024));
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE,noArithmetic.status());
        assertTrue(DomainExportWorkspace.read(noArithmetic.toCanonicalJson()).path("rows").isEmpty());
        var huge = new RecurrenceModel(1,List.of(new Rational(BigInteger.ONE.shiftLeft(200),BigInteger.ONE)));
        var oversized = DomainFiniteDataCheck.check(new LinearRecurrenceCandidate(huge,List.of(1L),List.of(1L)),
            new LinearRecurrenceCertificate(huge,List.of("1","1"),1,1,LR),new DomainFiniteDataCheck.Budget(256,1000,128));
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE,oversized.status());
        assertEquals(0,DomainExportWorkspace.read(oversized.toCanonicalJson()).path("work").path("scalarOperations").path("executed").asInt());
    }

    @Test void newtonCheckAgreesWithAdvancingDifferencesOnTheCompleteSmallCoefficientSpace() {
        int cases = 0;
        for (int order = 1; order <= 4; order++) {
            int combinations = (int) Math.pow(3,order+1);
            for (int encoding = 0; encoding < combinations; encoding++) {
                var initial = new ArrayList<Long>();
                int digits = encoding;
                for (int j = 0; j <= order; j++) { initial.add((long)(digits%3)-1); digits /= 3; }
                var differences = new ArrayList<>(initial);
                var reference = new ArrayList<Long>();
                for (int n = 0; n < 10; n++) {
                    reference.add(differences.getFirst());
                    for (int j = 0; j < order; j++) differences.set(j,Math.addExact(differences.get(j),differences.get(j+1)));
                }
                var candidate = new FiniteDifferenceCandidate(order,initial,reference.subList(0,6),reference.subList(6,10));
                var original = new FiniteDifferenceSequenceDomain().evaluator().evaluate(candidate).certificate();
                assertNotNull(original);
                assertEquals(reference,original.generatedTerms());
                assertEquals(DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA,DomainFiniteDataCheck.check(candidate,original,BUDGET).status());
                var changed = new ArrayList<>(candidate.holdout()); changed.set(0,changed.getFirst()+1);
                var negative = new FiniteDifferenceCandidate(order,initial,candidate.observed(),changed);
                var result = DomainFiniteDataCheck.check(negative,original,BUDGET);
                assertEquals(DomainFiniteDataCheck.Status.REFUTED_FINITE_DATA,result.status());
                assertEquals(6,DomainExportWorkspace.read(result.toCanonicalJson()).path("firstCounterexampleIndex").asInt());
                cases++;
            }
        }
        assertEquals(360,cases);
    }

    @Test void recurrenceObservedResidualAndCanonicalWitnessAreIndependentlyRequired() {
        var good = fibonacci(List.of(34L,55L,89L));
        var damagedPrefix = new LinearRecurrenceCandidate(good.model(),List.of(2L,3L,6L,8L,13L,21L),good.holdout());
        assertEquals(DiscoveryDomain.EvaluationStatus.CONFIRMED,new LinearRecurrenceSequenceDomain().evaluator().evaluate(damagedPrefix).status());
        var witness = fibonacciWitness(List.of("2","3","5","8","13","21","34","55","89"));
        assertEquals(DomainFiniteDataCheck.Status.REFUTED_FINITE_DATA,DomainFiniteDataCheck.check(damagedPrefix,witness,BUDGET).status());
        var unnormalized = fibonacciWitness(List.of("2","3","5","8","13","21","034","55","89"));
        assertEquals(DomainFiniteDataCheck.Status.INVALID_WITNESS,DomainFiniteDataCheck.check(good,unnormalized,BUDGET).status());
    }

    @Test void realBigIntegerCallsAreNotEnteredBeforeWidthAndOperationAdmission() {
        var large = new IntegerProbe(BigInteger.ONE.shiftLeft(200).toString());
        var largeModel = new RecurrenceModel(1,List.of(new Rational(large,BigInteger.ONE)));
        assertSame(large,largeModel.coefficients().getFirst().numerator());
        large.armed = true;
        var refusedWidth = DomainFiniteDataCheck.check(new LinearRecurrenceCandidate(largeModel,List.of(3L),List.of(6L)),
            new LinearRecurrenceCertificate(largeModel,List.of("3","6"),1,1,LR),new DomainFiniteDataCheck.Budget(256,1000,128));
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE,refusedWidth.status());
        assertEquals(0,large.gcdCalls,"refused input conversion must not enter core's real BigInteger gcd");

        var small = new IntegerProbe("2");
        var model = new RecurrenceModel(1,List.of(new Rational(small,BigInteger.ONE)));
        var candidate = new LinearRecurrenceCandidate(model,List.of(3L),List.of(6L));
        var witness = new LinearRecurrenceCertificate(model,List.of("3","6"),1,1,LR);
        small.armed = true;
        var refusedOperation = DomainFiniteDataCheck.check(candidate,witness,new DomainFiniteDataCheck.Budget(256,1,1024));
        assertEquals(DomainFiniteDataCheck.Status.INCONCLUSIVE,refusedOperation.status());
        assertTrue(small.gcdCalls > 0,"admitted input conversion really executes core arithmetic");
        assertEquals(0,small.multiplyCalls,"the first non-seed multiply is refused before real multiplication");
        assertEquals(DomainFiniteDataCheck.Status.CONFIRMED_FINITE_DATA,DomainFiniteDataCheck.check(candidate,witness,BUDGET).status());
        assertTrue(small.multiplyCalls > 0,"the ordinary completed check really enters multiplication");
    }

    private static final class IntegerProbe extends BigInteger {
        boolean armed;
        int gcdCalls, multiplyCalls;
        IntegerProbe(String decimal) { super(decimal); }
        @Override public BigInteger gcd(BigInteger other) { if (armed) gcdCalls++; return super.gcd(other); }
        @Override public BigInteger multiply(BigInteger other) { if (armed) multiplyCalls++; return super.multiply(other); }
        @Override public BigInteger divide(BigInteger other) { return other.equals(BigInteger.ONE) ? this : super.divide(other); }
    }

    static FiniteDifferenceCandidate squares(List<Long> holdout) {
        return new FiniteDifferenceCandidate(2,List.of(1L,3L,2L),List.of(1L,4L,9L,16L),holdout);
    }
    static FiniteDifferenceCertificate squareWitness(List<Long> terms) {
        return new FiniteDifferenceCertificate(2,List.of(1L,3L,2L),terms,4,2,FD);
    }
    static LinearRecurrenceCandidate fibonacci(List<Long> holdout) {
        return new LinearRecurrenceCandidate(new RecurrenceModel(2,List.of(Rational.of(1),Rational.of(1))),List.of(2L,3L,5L,8L,13L,21L),holdout);
    }
    static LinearRecurrenceCertificate fibonacciWitness(List<String> terms) {
        return new LinearRecurrenceCertificate(new RecurrenceModel(2,List.of(Rational.of(1),Rational.of(1))),terms,6,3,LR);
    }
}
