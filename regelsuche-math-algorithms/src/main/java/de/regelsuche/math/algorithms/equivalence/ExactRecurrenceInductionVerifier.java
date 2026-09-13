package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Result;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.Expression;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.Stage;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.Work;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.WorkLimit;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInvariantDiscovery.WorkProfile;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Specialized exact induction only: a bound recurrence, its initial state and I(s_n)=lambda^n I(s_0), n>=0. */
public final class ExactRecurrenceInductionVerifier {
    public static final String REVISION = "regelsuche.exact-recurrence-induction-verifier/v1";

    public Verification verify(RecurrenceInvariantFormation expected, Certificate supplied, int maxWorkUnits) {
        Objects.requireNonNull(expected, "expected formation");
        Objects.requireNonNull(supplied, "supplied certificate");
        Work work = new Work(maxWorkUnits);
        try {
            if (!expected.contentHash().equals(supplied.formationHash()) || maxWorkUnits > expected.bounds().maxTotalWorkUnits()) {
                throw new IllegalArgumentException("FOREIGN_FORMATION_OR_VERIFICATION_BUDGET");
            }
            if (!expected.assumptions().isEmpty()) {
                return new Verification(VerificationStatus.UNSUPPORTED, Optional.empty(), work.snapshot(), "NONEMPTY_ASSUMPTIONS");
            }
            var replay = replayChart(expected, supplied.lambdaIndex(), supplied.chartIndex(), supplied.solverResult(), work);
            var checked = checkReplayed(replay);
            if (!checked.certificate().equals(supplied)) {
                throw new IllegalArgumentException("COMPLETE_INDUCTION_CERTIFICATE_DIFFERS");
            }
            return new Verification(VerificationStatus.CONFIRMED, Optional.of(checked), work.snapshot(), "SPECIALIZED_INDUCTION_CONFIRMED");
        } catch (WorkLimit | ExactResidualPolynomialArithmetic.ProjectionLimitExceeded exhausted) {
            return new Verification(VerificationStatus.BUDGET_INCONCLUSIVE, Optional.empty(), work.snapshot(), exhausted.getMessage());
        } catch (IllegalArgumentException rejected) {
            return new Verification(VerificationStatus.REFUTED, Optional.empty(), work.snapshot(), rejected.getMessage());
        }
    }

    ReplayedChart replayChart(RecurrenceInvariantFormation expected, int lambdaIndex, int chartIndex, Result retained, Work work) {
        requireChart(expected, lambdaIndex, chartIndex);
        if (!expected.assumptions().isEmpty() || work.snapshot().configured() > expected.bounds().maxTotalWorkUnits()) {
            throw new IllegalArgumentException("REPLAY_OUTSIDE_FORMATION_AUTHORITY");
        }
        String expectedTemplate = ExactRecurrenceInvariantDiscovery.template(expected, lambdaIndex, chartIndex, work);
        if (!retained.sourceExpression().equals("0") || !expectedTemplate.equals(retained.ansatzTemplate())
                || !retained.holeIds().equals(expected.holeIds().stream().sorted().toList()) || !retained.assumptions().isEmpty()
                || retained.limits().maxHoles() != expected.basis().size() || retained.limits().maxMonomials() != 128
                || retained.limits().maxScalarBits() != expected.bounds().maxScalarBits()
                || retained.limits().maxWorkUnits() > expected.bounds().maxAttemptWorkUnits()) {
            throw new IllegalArgumentException("FOREIGN_NATIVE_SOLVER_INPUT");
        }
        work.admit(retained.limits().maxWorkUnits());
        Result replay = new ExactLinearPolynomialHoleSolver().solve("0", expectedTemplate, expected.holeIds(), List.of(), retained.limits());
        work.spend(Stage.SOLVER_REPLAY, replay.work().consumed());
        if (!retained.equals(replay)) { throw new IllegalArgumentException("NATIVE_SOLVER_REPLAY_DIFFERS"); }
        return new Replayed(expected, lambdaIndex, chartIndex, replay, work);
    }

    VerifiedInduction checkReplayed(ReplayedChart checked) {
        RecurrenceInvariantFormation formation = checked.formation();
        int lambdaIndex = checked.lambdaIndex();
        int chartIndex = checked.chartIndex();
        Result replayed = checked.result();
        Work work = checked.authority();
        requireChart(formation, lambdaIndex, chartIndex);
        if (!formation.assumptions().isEmpty() || replayed.status() != ExactLinearPolynomialHoleSolver.Status.UNIQUE) {
            throw new IllegalArgumentException("NO_UNIQUE_ASSUMPTION_FREE_NATIVE_CANDIDATE");
        }
        List<ExactRational> coefficients = new ArrayList<>();
        for (int i = 0; i < formation.basis().size(); i++) {
            work.spend(Stage.TRANSITION_CHECK, 1);
            var coefficient = Objects.requireNonNull(replayed.candidate().orElseThrow().bindings().get("coefficient" + i));
            try { RecurrenceInvariantFormation.requireBits(coefficient, formation.bounds().maxCoefficientBits()); }
            catch (IllegalArgumentException rejected) { throw new CoefficientBound(); }
            if (i < chartIndex && !coefficient.isZero() || i == chartIndex && !coefficient.equals(ExactRational.ONE)) {
                throw new IllegalArgumentException("NORMALIZATION_CHART_NOT_SATISFIED");
            }
            coefficients.add(coefficient);
        }
        ExactRational lambda = formation.lambdas().get(lambdaIndex);
        // Deliberately reconstruct from the typed source and concrete vector, without normalizer symbols,
        // the solver's substituted expression or its claimed normal forms. Repeated multiplication and
        // reversed companion terms are independent of the discovery template's power/term construction.
        String invariant = concretePolynomial(formation, coefficients, false, work);
        String shifted = concretePolynomial(formation, coefficients, true, work);
        String scaled = new Expression(work, Stage.TRANSITION_CHECK).append("(").append(lambda.canonicalText())
            .append(")*(").append(invariant).append(")").value();
        new ExactPolynomialAnalysis(null, units -> work.spend(Stage.TRANSITION_CHECK, units)).requireEquivalent(shifted, scaled);
        ExactRational initialValue = evaluateInitial(formation, coefficients, work);
        // Natural exponentiation is defined at zero even for lambda=0. No division by lambda is used.
        work.spend(Stage.INITIAL_CHECK, 1);
        ExactRational baseMultiplier = lambda.pow(0);
        if (!multiply(baseMultiplier, initialValue, formation.bounds().maxScalarBits(), work).equals(initialValue)) {
            throw new IllegalArgumentException("INITIAL_POWER_OBLIGATION_FAILED");
        }
        var certificate = new Certificate(formation.contentHash(), lambdaIndex, chartIndex, lambda, coefficients, replayed,
            invariant, shifted, scaled, initialValue);
        return new Checked(certificate, work.snapshot());
    }

    private static String concretePolynomial(RecurrenceInvariantFormation formation, List<ExactRational> coefficients,
                                             boolean shifted, Work work) {
        Expression expression = new Expression(work, Stage.TRANSITION_CHECK);
        for (int term = 0; term < formation.basis().size(); term++) {
            if (term > 0) { expression.append("+"); }
            expression.append("(").append(coefficients.get(term).canonicalText()).append(")");
            for (int axis = 0; axis < formation.recurrence().order(); axis++) {
                for (int power = 0; power < formation.basis().get(term).exponents().get(axis); power++) {
                    expression.append("*(");
                    if (!shifted || axis + 1 < formation.recurrence().order()) {
                        expression.append("state").append(Integer.toString(axis + (shifted ? 1 : 0)));
                    } else {
                        for (int i = formation.recurrence().order() - 1; i >= 0; i--) {
                            if (i != formation.recurrence().order() - 1) { expression.append("+"); }
                            expression.append("state").append(Integer.toString(i)).append("*(")
                                .append(formation.recurrence().coefficients().get(i).canonicalText()).append(")");
                        }
                    }
                    expression.append(")");
                }
            }
        }
        return expression.value();
    }

    private static ExactRational evaluateInitial(RecurrenceInvariantFormation formation, List<ExactRational> coefficients, Work work) {
        ExactRational sum = ExactRational.ZERO;
        for (int term = 0; term < formation.basis().size(); term++) {
            ExactRational value = coefficients.get(term);
            for (int axis = 0; axis < formation.recurrence().order(); axis++) {
                for (int power = 0; power < formation.basis().get(term).exponents().get(axis); power++) {
                    value = multiply(value, formation.recurrence().initialValues().get(axis), formation.bounds().maxScalarBits(), work);
                }
            }
            sum = add(sum, value, formation.bounds().maxScalarBits(), work);
        }
        return sum;
    }

    private static ExactRational multiply(ExactRational left, ExactRational right, int bitLimit, Work work) {
        work.spend(Stage.INITIAL_CHECK, 1);
        if (!left.isZero() && !right.isZero()) {
            room(Math.max((long) left.numerator().abs().bitLength() + right.numerator().abs().bitLength(),
                (long) left.denominator().bitLength() + right.denominator().bitLength()), bitLimit);
        }
        return left.multiply(right);
    }

    private static ExactRational add(ExactRational left, ExactRational right, int bitLimit, Work work) {
        work.spend(Stage.INITIAL_CHECK, 1);
        if (!left.isZero() && !right.isZero()) {
            long numerator = 1L + Math.max((long) left.numerator().abs().bitLength() + right.denominator().bitLength(),
                (long) right.numerator().abs().bitLength() + left.denominator().bitLength());
            room(Math.max(numerator, (long) left.denominator().bitLength() + right.denominator().bitLength()), bitLimit);
        }
        return left.add(right);
    }

    private static void room(long estimatedBits, int maximum) {
        if (estimatedBits > maximum) { throw new WorkLimit("INITIAL_SCALAR_BIT_ROOM_EXHAUSTED"); }
    }
    private static void requireChart(RecurrenceInvariantFormation formation, int lambdaIndex, int chartIndex) {
        if (lambdaIndex < 0 || lambdaIndex >= formation.lambdas().size() || chartIndex < 0 || chartIndex >= formation.basis().size()) {
            throw new IllegalArgumentException("CHART_OUTSIDE_FROZEN_DOMAIN");
        }
    }

    public enum VerificationStatus { CONFIRMED, REFUTED, UNSUPPORTED, BUDGET_INCONCLUSIVE }
    public record Verification(VerificationStatus status, Optional<VerifiedInduction> verified, WorkProfile work, String detailCode) {
        public Verification {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(verified, "verified");
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(detailCode, "detailCode");
            if ((status == VerificationStatus.CONFIRMED) != verified.isPresent()) {
                throw new IllegalArgumentException("verification state and issued induction authority disagree");
            }
        }
    }

    /** Retainable data. Even a self-consistent hash and solver result cannot issue VerifiedInduction. */
    public record Certificate(String formationHash, int lambdaIndex, int chartIndex, ExactRational lambda,
                              List<ExactRational> coefficients, Result solverResult, String invariantExpression,
                              String shiftedInvariantExpression, String scaledInvariantExpression, ExactRational initialValue) {
        public static final String SCHEMA = "regelsuche.specialized-recurrence-induction-certificate/v1";
        public Certificate {
            if (formationHash == null || !formationHash.matches("sha256:[0-9a-f]{64}") || lambdaIndex < 0 || lambdaIndex >= 16
                    || chartIndex < 0 || chartIndex >= 12) { throw new IllegalArgumentException("invalid recurrence certificate identity"); }
            Objects.requireNonNull(lambda, "lambda");
            Objects.requireNonNull(coefficients, "coefficients");
            if (coefficients.isEmpty() || coefficients.size() > 12) { throw new IllegalArgumentException("invalid coefficient count"); }
            coefficients = List.copyOf(coefficients);
            if (coefficients.isEmpty() || coefficients.size() > 12) { throw new IllegalArgumentException("invalid coefficient count"); }
            Objects.requireNonNull(solverResult, "solverResult");
            Objects.requireNonNull(initialValue, "initialValue");
            for (String expression : List.of(invariantExpression, shiftedInvariantExpression, scaledInvariantExpression)) {
                if (expression.isBlank() || expression.length() > 16_384 || expression.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("invalid certificate expression");
                }
            }
        }
        public String indexDomain() { return "NATURAL_INDEX_GE_ZERO"; }
        public String contentHash() { return RecurrenceInvariantFormation.hash(RecurrenceInvariantJson.certificate(this, false)); }
        public String toCanonicalJson() { return RecurrenceInvariantJson.certificate(this, true); }
    }

    /** This capability proves only the specialized induction stated in its certificate schema. */
    public sealed interface VerifiedInduction permits Checked {
        Certificate certificate();
        WorkProfile work();
    }
    private record Checked(Certificate certificate, WorkProfile work) implements VerifiedInduction {}

    /** The exact cumulative authority accompanies the private replay witness; checking cannot replace it with a fresh budget. */
    sealed interface ReplayedChart permits Replayed {
        RecurrenceInvariantFormation formation();
        int lambdaIndex();
        int chartIndex();
        Result result();
        Work authority();
    }
    private record Replayed(RecurrenceInvariantFormation formation, int lambdaIndex, int chartIndex, Result result,
                            Work authority) implements ReplayedChart {}
    static final class CoefficientBound extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        CoefficientBound() { super("COEFFICIENT_BOUND_EXCEEDED"); }
    }
}
