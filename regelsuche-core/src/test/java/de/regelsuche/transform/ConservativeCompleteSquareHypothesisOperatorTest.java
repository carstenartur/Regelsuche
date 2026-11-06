package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConservativeCompleteSquareHypothesisOperatorTest {
    private final ConservativeCompleteSquareHypothesisOperator operator = new ConservativeCompleteSquareHypothesisOperator();

    @Test
    void positiveCandidatesAreBoundedAndDeterministic() {
        List<Transformation> first = operator.generateCandidates("x^2 + 6*x + 5");
        List<Transformation> second = operator.generateCandidates("x^2 + 6*x + 5");

        assertFalse(first.isEmpty());
        assertTrue(first.size() <= 6);
        assertEquals(first, second);
        assertTrue(first.stream().anyMatch(candidate ->
            candidate.rule().equals(ConservativeCompleteSquareHypothesisOperator.RULE_ID)
                && SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())));
    }

    @Test
    void supportsBivariatePerfectSquares() {
        List<Transformation> candidates = operator.generateCandidates("x^2 + 2*x*y + y^2");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().anyMatch(candidate ->
            PerfectSquareAstPredicate.containsPerfectSquare(candidate.transformedExpression())));
    }

    @Test
    void supportsCompoundBaseWhenStructurallyVisible() {
        List<Transformation> candidates = operator.generateCandidates("(x + 1)^2 + 6*(x + 1) + 5");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().anyMatch(candidate ->
            SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())));
    }

    @Test
    void optInFactorMatchingRecognizesSquareBasesContainingProducts() throws Exception {
        var constructor = ConservativeCompleteSquareHypothesisOperator.class.getConstructor(int.class, boolean.class);
        var extended = constructor.newInstance(6, true);
        String input = "(x^2)^2+(2*y^2)^2+2*(x^2)*(2*y^2)";
        assertTrue(operator.generateCandidates(input).isEmpty(), "legacy matching stays unchanged");
        assertFalse(extended.generateCandidates(input).isEmpty(), "factorized square bases form a perfect square");
        assertFalse(extended.generateCandidates("(a+1)^2+(3*b)^2+6*b*(a+1)").isEmpty());
        assertTrue(extended.generateCandidates("(a+1)^2+(3*b)^2+6*b*(a-1)").isEmpty());
        assertTrue(extended.generateCandidates("(a+1)^2+(3*b)^2+5*b*(a+1)").isEmpty());
    }

    @Test
    void negativeCasesProduceNoFalsePositive() {
        assertTrue(operator.generateCandidates("x^2 + 6*x + 6").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 6*x + y").isEmpty());
        assertTrue(operator.generateCandidates("x^2 + 2*x*y + z^2").isEmpty());
    }

    @Test
    void rationalProductRecognitionIsOptInAndPaid() {
        var work = new java.util.concurrent.atomic.AtomicLong();
        var rational = new ConservativeCompleteSquareHypothesisOperator(6, true, true, work::addAndGet);
        assertFalse(rational.generateCandidates("x^2+(4/3)*x*y+(2/3*y)^2").isEmpty());
        assertFalse(rational.generateCandidates("x^2+3*x*a+(1.5*a)^2").isEmpty());
        assertTrue(rational.generateCandidates("x^2+(5/3)*x*y+(2/3*y)^2").isEmpty());
        assertTrue(work.get() > 0);
        assertTrue(new ConservativeCompleteSquareHypothesisOperator(6, true)
            .generateCandidates("x^2+(4/3)*x*y+(2/3*y)^2").isEmpty(), "legacy factor matching stays unchanged");
    }

    @Test
    void rationalWorkObserverFailureIsNotSilenced() {
        var rational = new ConservativeCompleteSquareHypothesisOperator(6, true, true, units -> {
            throw new IllegalArgumentException("work meter failed");
        });
        assertThrows(IllegalArgumentException.class,
            () -> rational.generateCandidates("x^2+(4/3)*x*y+(2/3*y)^2"));
    }

    @Test
    void rationalModeChargesAndBoundsUnivariateArithmeticBeforeRootChecking() {
        var work = new java.util.concurrent.atomic.AtomicLong();
        var rational = new ConservativeCompleteSquareHypothesisOperator(6, true, true, work::addAndGet);
        String factor = "1" + "0".repeat(60);
        String product = String.join("*", java.util.Collections.nCopies(20, factor));
        assertTrue(rational.generateCandidates("x^2+2*" + product + "*x+1").isEmpty());
        assertTrue(work.get() > 0, "failed coefficient products must be charged before exceeding the bit limit");
        var ordinary = new java.util.concurrent.atomic.AtomicLong();
        var exact = new ConservativeCompleteSquareHypothesisOperator(6, true, true, ordinary::addAndGet);
        assertFalse(exact.generateCandidates("x^2+6*x+5").isEmpty());
        assertTrue(ordinary.get() > 0, "accepted univariate offsets and square roots must also be charged");
    }

    @Test
    void rationalModeCompletesAUnivariateSquareWithoutIntegerParityAssumptions() {
        var rational = new ConservativeCompleteSquareHypothesisOperator(6, true, true, units -> { });
        assertFalse(rational.generateCandidates("x^2+(4/3)*x+(4/9)").isEmpty());
        assertFalse(rational.generateCandidates("x^2+(4/3)*x+(1/3)").isEmpty(),
            "a negative rational square remainder can be represented exactly");
        assertTrue(rational.generateCandidates("x^2+(4/3)*x+(5/9)").isEmpty(),
            "a positive remainder is outside the conservative operator");
        assertTrue(new ConservativeCompleteSquareHypothesisOperator(6, true)
            .generateCandidates("x^2+(4/3)*x+(4/9)").isEmpty(), "legacy matching stays unchanged");
    }

    @Test
    void legacyMatchingCanBeMeteredWithoutChangingItsCandidates() {
        var work = new java.util.concurrent.atomic.AtomicLong();
        var paid = new ConservativeCompleteSquareHypothesisOperator(6, true, false, work::addAndGet);
        var legacy = new ConservativeCompleteSquareHypothesisOperator(6, true);
        for (String source : List.of("x^2+6*x+5", "x^2+2*x*y+y^2")) {
            assertEquals(legacy.generateCandidates(source), paid.generateCandidates(source));
        }
        assertTrue(work.get() > 0, "the preserved legacy arithmetic must be charged when a meter is supplied");
        var brokenMeter = new ConservativeCompleteSquareHypothesisOperator(6, true, false, units -> {
            throw new IllegalArgumentException("work meter failed");
        });
        assertThrows(IllegalArgumentException.class, () -> brokenMeter.generateCandidates("x^2+6*x+5"));
    }
}
