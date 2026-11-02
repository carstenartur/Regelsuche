package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Proposal;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Request;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Status;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantVerifier.Outcome;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RecurrenceInvariantSynthesisTest {
    private static final Budget BUDGET = new Budget(200_000);
    private static int checks;
    private final RecurrenceInvariantSynthesis synthesis = new RecurrenceInvariantSynthesis();
    private final RecurrenceInvariantVerifier verifier = new RecurrenceInvariantVerifier();

    @Test void derivesCassiniFromTheTransitionWithoutTheAnswer() {
        var result = synthesis.solve(new Request(fibonacci(), values(0, 1), 2, BUDGET));
        equal(Status.COMPLETE, result.status());
        equal(1, result.laws().size());
        var law = result.laws().getFirst();
        equivalent("x0^2+x0*x1-x1^2", law.proposal().polynomial());
        equal(Evolution.ALTERNATING, law.proposal().evolution());
        equal(Rational.of(-1), law.initialValue());
        equal(Rational.of(-1), law.valueAt(BigInteger.TEN.pow(100)));
        equal(Rational.ONE, law.valueAt(BigInteger.TEN.pow(100).add(BigInteger.ONE)));
        illegal(() -> law.valueAt(BigInteger.valueOf(-1)));
        check(result.work().consumedWorkUnits() > 0, "all formation and proof work is paid");
    }

    @Test void transfersToPellAndExactRationalTransitions() {
        var pell = transition(new long[][] {{0, 1}, {1, 2}}, 0, 0);
        var result = synthesis.solve(new Request(pell, values(0, 1), 2, BUDGET));
        equal(1, result.laws().size());
        equivalent("x0^2+2*x0*x1-x1^2", result.laws().getFirst().proposal().polynomial());
        var half = new Rational(BigInteger.ONE, BigInteger.TWO);
        var rational = new Transition(new ExactMatrix(List.of(List.of(Rational.ZERO, half),
            List.of(Rational.of(2), Rational.ZERO))), new ExactVector(values(0, 0)));
        var rationalResult = synthesis.solve(new Request(rational, values(3, 4), 2, BUDGET));
        check(!rationalResult.laws().isEmpty(), "rational transition yields genuine laws");
        for (var law : rationalResult.laws())
            equal(Outcome.VERIFIED, verifier.verify(rational, law.proposal(), values(3, 4), BUDGET).outcome());
    }

    @Test void handlesAffineOffsetsAndDegenerateNullspaces() {
        var rotation = transition(new long[][] {{0, -1}, {1, 0}}, 1, 0);
        var report = synthesis.solve(new Request(rotation, values(0, 0), 2, BUDGET));
        check(!report.laws().isEmpty(), "affine centered quadratic law");
        equal(Outcome.VERIFIED, verifier.verify(rotation,
            new Proposal("x0^2+x1^2-x0-x1", Evolution.FIXED), values(0, 0), BUDGET).outcome());
        var identity = transition(new long[][] {{1, 0}, {0, 1}}, 0, 0);
        var all = synthesis.solve(new Request(identity, values(2, 3), 2, BUDGET));
        equal(Status.COMPLETE, all.status());
        equal(5, all.laws().size());
        check(all.laws().stream().allMatch(l -> l.proposal().evolution() == Evolution.FIXED),
            "zero columns remain free; constants are not reported as useful laws");
    }

    @Test void distinguishesAnEmptyAnsatzFromAnExhaustedBudget() {
        var request = new Request(transition(new long[][] {{2}}, 0), values(1), 2, BUDGET);
        var empty = synthesis.solve(request);
        equal(Status.COMPLETE, empty.status());
        check(empty.laws().isEmpty(), "no nonconstant fixed/alternating degree <= 2 law");
        var exhausted = synthesis.solve(new Request(fibonacci(), values(0, 1), 2, new Budget(0)));
        equal(Status.BUDGET_INCONCLUSIVE, exhausted.status());
        check(exhausted.laws().isEmpty(), "no unverified claims on exhaustion");
    }

    @Test void preservesOneBudgetAcrossEliminationAndProof() {
        var full = synthesis.solve(new Request(fibonacci(), values(0, 1), 2, BUDGET));
        int used = full.work().consumedWorkUnits();
        for (int cap : new int[] {0, 1, 10, used / 2, used - 1, used, used + 1}) {
            var result = synthesis.solve(new Request(fibonacci(), values(0, 1), 2, new Budget(cap)));
            equal(cap < used ? Status.BUDGET_INCONCLUSIVE : Status.COMPLETE, result.status());
            check(result.work().consumedWorkUnits() <= cap, "never resets request budget");
            if (cap < used) check(result.laws().isEmpty(), "no partially paid certificate");
        }
        var proposal = full.laws().getFirst().proposal();
        var verified = verifier.verify(fibonacci(), proposal, values(0, 1), BUDGET);
        int proof = verified.work().consumedWorkUnits();
        equal(Outcome.BUDGET_INCONCLUSIVE,
            verifier.verify(fibonacci(), proposal, values(0, 1), new Budget(proof - 1)).outcome());
        equal(Outcome.VERIFIED,
            verifier.verify(fibonacci(), proposal, values(0, 1), new Budget(proof)).outcome());
    }

    @Test void rejectsForgedNoOpForeignAndSourceReboundClaims() {
        var p = new Proposal("x0^2+x0*x1-x1^2", Evolution.ALTERNATING);
        equal(Outcome.REJECTED, verifier.verify(transition(new long[][] {{0,1},{1,2}}, 0, 0),
            p, values(0,1), BUDGET).outcome());
        equal(Outcome.REJECTED, verifier.verify(fibonacci(),
            new Proposal(p.polynomial(), Evolution.FIXED), values(0,1), BUDGET).outcome());
        for (String bad : List.of("0", "1", "x0-x0", "x0^2+x1^2", "z^2"))
            equal(Outcome.REJECTED, verifier.verify(fibonacci(),
                new Proposal(bad, Evolution.FIXED), values(0,1), BUDGET).outcome());
        equal(Outcome.UNSUPPORTED, verifier.verify(fibonacci(),
            new Proposal("1/x0", Evolution.FIXED), values(0,1), BUDGET).outcome());
    }

    @Test void distinguishesFittingNumbersFromAUniversalStepIdentity() {
        // x0^2 agrees with several zero observations, but is not a Fibonacci invariant.
        equal(Outcome.REJECTED, verifier.verify(fibonacci(),
            new Proposal("x0^2", Evolution.FIXED), values(0,0), BUDGET).outcome());
        var shifted = transition(new long[][] {{1}}, 1);
        equal(Outcome.REJECTED, verifier.verify(shifted,
            new Proposal("x0^2", Evolution.FIXED), values(0), BUDGET).outcome());
    }

    @Test void validatesDimensionsDegreeAndExactLargeCoefficients() {
        illegal(() -> new Request(fibonacci(), values(0), 2, BUDGET));
        illegal(() -> new Request(fibonacci(), values(0,1), 0, BUDGET));
        illegal(() -> new Request(fibonacci(), values(0,1), 3, BUDGET));
        illegal(() -> new Transition(new ExactMatrix(List.of(values(1,2))), new ExactVector(values(0))));
        var big = new Rational(new BigInteger("9007199254740993"), BigInteger.ONE);
        var transition = new Transition(new ExactMatrix(List.of(values(0,1), List.of(Rational.ONE,big))),
            new ExactVector(values(0,0)));
        var report = synthesis.solve(new Request(transition, values(0,1), 2, BUDGET));
        equal(Status.COMPLETE, report.status());
        equal(1, report.laws().size());
        equivalent("x0^2+9007199254740993*x0*x1-x1^2", report.laws().getFirst().proposal().polynomial());
        check(!report.laws().getFirst().proposal().polynomial().contains("9007199254740992"), "no double rounding");
    }

    @Test void reusesAStoredProposalWithAnIndependentlyCheckedNewInitialState() {
        var report = synthesis.solve(new Request(fibonacci(), values(0,1), 2, BUDGET));
        var proposal = report.laws().getFirst().proposal();
        var restored = verifier.verify(fibonacci(), new Proposal(proposal.polynomial(), proposal.evolution()),
            values(2,1), BUDGET);
        equal(Outcome.VERIFIED, restored.outcome());
        equal(Rational.of(5), restored.law().orElseThrow().initialValue());
        equal(Rational.of(-5), restored.law().orElseThrow().valueAt(BigInteger.ONE));
        check(restored.work().consumedWorkUnits() < report.work().consumedWorkUnits(),
            "checking retained knowledge avoids coefficient search, but is not free");
    }

    @Test void checksAffineTransferOnIndependentConcreteTrajectories() {
        var random = new java.util.Random(73142);
        for (int example = 0; example < 36; example++) {
            int dimension = example < 24 ? 2 : 3;
            var transition = randomTransition(random, dimension);
            var initial = dimension == 2 ? values(2,-3) : values(2,-3,4);
            var result = synthesis.solve(new Request(transition, initial, 2, BUDGET));
            equal(Status.COMPLETE, result.status());
            verifyTrajectory(transition, initial, result.laws());
        }
    }

    private static Transition randomTransition(java.util.Random random, int dimension) {
        long[][] matrix = new long[dimension][dimension];
        long[] offset = new long[dimension];
        for (int i = 0; i < dimension; i++) {
            offset[i] = random.nextInt(3) - 1;
            for (int j = 0; j < dimension; j++) matrix[i][j] = random.nextInt(3) - 1;
        }
        return transition(matrix, offset);
    }

    private static void verifyTrajectory(Transition transition, List<Rational> initial,
            List<RecurrenceInvariantVerifier.VerifiedLaw> laws) {
        List<Rational> state = initial;
        for (int n = 0; n < 8; n++) {
            for (var law : laws) equal(evaluate(law.proposal().polynomial(), state), law.valueAt(BigInteger.valueOf(n)));
            var next = new java.util.ArrayList<Rational>();
            for (int i = 0; i < state.size(); i++) {
                Rational value = transition.offset().get(i);
                for (int j = 0; j < state.size(); j++) value = value.add(transition.linear().get(i,j).multiply(state.get(j)));
                next.add(value);
            }
            state = List.copyOf(next);
        }
    }

    private static Rational evaluate(String expression, List<Rational> state) {
        var polynomial = new ExactPolynomialAnalysis().normalForm(expression);
        Rational concrete = Rational.ZERO;
        for (var term : polynomial.terms().entrySet()) {
            Rational value = term.getValue();
            for (int coordinate = 0; coordinate < state.size(); coordinate++)
                for (int power = 0; power < term.getKey().exponentOf("x"+coordinate); power++)
                    value = value.multiply(state.get(coordinate));
            concrete = concrete.add(value);
        }
        return concrete;
    }

    @Test void respectsLinearAnsatzAndRejectsOversizedInputs() {
        var linear = synthesis.solve(new Request(fibonacci(), values(0,1), 1, BUDGET));
        equal(Status.COMPLETE, linear.status()); check(linear.laws().isEmpty(), "Cassini needs degree two");
        var identity = transition(new long[][] {{1,0},{0,1}}, 0,0);
        equal(2, synthesis.solve(new Request(identity, values(2,3), 1, BUDGET)).laws().size());
        var huge = new Rational(BigInteger.ONE.shiftLeft(129), BigInteger.ONE);
        illegal(() -> new Request(fibonacci(), List.of(huge, Rational.ONE), 2, BUDGET));
        illegal(() -> new Transition(new ExactMatrix(List.of(List.of(huge))), new ExactVector(values(0))));
        equal(Outcome.UNSUPPORTED, verifier.verify(fibonacci(),
            new Proposal("x0^3+x1^3", Evolution.FIXED), values(0,1), BUDGET).outcome());
    }

    @Test void reportsOversizedValidLawsAsUnsupportedInsteadOfThrowing() {
        var transition = new Transition(new ExactMatrix(List.of(
                values(0, -1, 0),
                List.of(Rational.of(-1), ratio("-24402839367495703042404871834352035752", "26333050882828419407775062651959254809"), Rational.ONE),
                values(0, 0, 0))),
            new ExactVector(List.of(
                ratio("21483844301554108744711421673995363106", "22975977712300925394603424805469836863"),
                ratio("12264347322807890882286157959284470611", "7376799742270097422755889420238910814"),
                ratio("61564151475901760944591302486959482587", "62204651478189102752098365946853233994"))));
        var report = synthesis.solve(new Request(transition, values(1, 1, 1), 2, new Budget(Integer.MAX_VALUE)));
        equal(Status.UNSUPPORTED, report.status());
        check(report.laws().isEmpty(), "no partial law set is published as complete");
        check(report.work().consumedWorkUnits() > 0, "work up to the guard is still accounted");
        illegal(() -> new Proposal("x0+".repeat(1366) + "x1", Evolution.FIXED));
    }

    private static void freshProcess(String mode, java.nio.file.Path path) throws Exception {
        var properties = new java.util.Properties();
        var verifier = new RecurrenceInvariantVerifier();
        if (mode.equals("save")) {
            var result = new RecurrenceInvariantSynthesis().solve(new Request(fibonacci(), values(0,1),2,BUDGET));
            var law = result.laws().getFirst();
            properties.setProperty("polynomial", law.proposal().polynomial());
            properties.setProperty("evolution", law.proposal().evolution().name());
            try (var writer = java.nio.file.Files.newBufferedWriter(path)) { properties.store(writer, "untrusted proposal, not a certificate"); }
            System.out.println("synthesisWork="+result.work().consumedWorkUnits()+" polynomial="+law.proposal().polynomial());
        } else {
            try (var reader = java.nio.file.Files.newBufferedReader(path)) { properties.load(reader); }
            var proposal = new Proposal(properties.getProperty("polynomial"), Evolution.valueOf(properties.getProperty("evolution")));
            // Caller supplies its transition and new initial state, not the stored artifact.
            var result = verifier.verify(fibonacci(),proposal,values(2,1),BUDGET);
            equal(Outcome.VERIFIED,result.outcome());
            equal(Rational.of(5),result.law().orElseThrow().valueAt(BigInteger.TEN.pow(100)));
            System.out.println("restoreWithoutSynthesisWork="+result.work().consumedWorkUnits()+" invariantAt10pow100=5");
        }
    }

    private static Transition fibonacci() { return transition(new long[][] {{0,1},{1,1}},0,0); }
    private static Transition transition(long[][] matrix, long... offset) {
        return new Transition(new ExactMatrix(java.util.Arrays.stream(matrix).map(RecurrenceInvariantSynthesisTest::values).toList()),
            new ExactVector(values(offset)));
    }
    private static Rational ratio(String numerator, String denominator) {
        return new Rational(new BigInteger(numerator), new BigInteger(denominator));
    }
    private static List<Rational> values(long... values) { return java.util.Arrays.stream(values).mapToObj(Rational::of).toList(); }
    private static void equivalent(String left, String right) { new ExactPolynomialAnalysis().requireEquivalent(left,right); checks++; }
    private static void equal(Object expected, Object actual) { check(expected.equals(actual), "expected="+expected+", actual="+actual); }
    private static void check(boolean success, String message) { checks++; if (!success) throw new AssertionError(message); }
    private static void illegal(Runnable call) { try { call.run(); } catch (IllegalArgumentException expected) { checks++; return; } throw new AssertionError("expected rejection"); }

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { freshProcess(args[0], java.nio.file.Path.of(args[1])); return; }
        var tests = new RecurrenceInvariantSynthesisTest();
        tests.derivesCassiniFromTheTransitionWithoutTheAnswer();
        tests.transfersToPellAndExactRationalTransitions();
        tests.handlesAffineOffsetsAndDegenerateNullspaces();
        tests.distinguishesAnEmptyAnsatzFromAnExhaustedBudget();
        tests.preservesOneBudgetAcrossEliminationAndProof();
        tests.rejectsForgedNoOpForeignAndSourceReboundClaims();
        tests.distinguishesFittingNumbersFromAUniversalStepIdentity();
        tests.validatesDimensionsDegreeAndExactLargeCoefficients();
        tests.reusesAStoredProposalWithAnIndependentlyCheckedNewInitialState();
        tests.checksAffineTransferOnIndependentConcreteTrajectories();
        tests.respectsLinearAnsatzAndRejectsOversizedInputs();
        tests.reportsOversizedValidLawsAsUnsupportedInsteadOfThrowing();
        System.out.println("PASS recurrence invariant contracts; assertions="+checks);
    }
}
