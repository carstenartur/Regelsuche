package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.math.algorithms.equivalence.ExactLinearPolynomialHoleSolver.Result;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.ReplayedChart;
import de.regelsuche.math.algorithms.equivalence.ExactRecurrenceInductionVerifier.VerifiedInduction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Finite normalized eigenpolynomial charts over a supplied recurrence; this is not a new search executor. */
public final class ExactRecurrenceInvariantDiscovery {
    public static final String REVISION = "regelsuche.exact-recurrence-invariant-discovery/v1";
    private final ExactLinearPolynomialHoleSolver solver = new ExactLinearPolynomialHoleSolver();

    public Run discover(RecurrenceInvariantFormation formation) {
        Objects.requireNonNull(formation, "formation");
        Work work = new Work(formation.bounds().maxTotalWorkUnits());
        List<Attempt> attempts = new ArrayList<>();
        if (!formation.assumptions().isEmpty()) { return new Run(formation, attempts, Status.UNSUPPORTED, work.snapshot()); }
        boolean incomplete = false;
        search:
        for (int lambda = 0; lambda < formation.lambdas().size(); lambda++) {
            for (int chart = 0; chart < formation.basis().size(); chart++) {
                Result result = null;
                Result replay = null;
                try {
                    String template = template(formation, lambda, chart, work);
                    // Delegate neither solve more than the current allowance; reserve room for an identical replay.
                    int allowance = Math.min(formation.bounds().maxAttemptWorkUnits(), work.remaining() / 2);
                    work.admit(allowance);
                    result = solver.solve("0", template, formation.holeIds(), List.of(),
                        new ExactLinearPolynomialHoleSolver.Limits(formation.basis().size(), 128,
                            formation.bounds().maxScalarBits(), allowance));
                    work.spend(Stage.SOLVER, result.work().consumed());
                    var verifier = new ExactRecurrenceInductionVerifier();
                    var replayedChart = verifier.replayChart(formation, lambda, chart, result, work);
                    replay = replayedChart.result();
                    Attempt attempt = checkAttempt(lambda, chart, result, replayedChart, verifier);
                    if (!attempt.status().equals("UNIQUE") && !attempt.status().equals("INCONSISTENT")
                            && !attempt.status().equals("COEFFICIENT_BOUND")) {
                        incomplete = true;
                    }
                    attempts.add(attempt);
                    if (work.remaining() == 0 && attempts.size() < formation.lambdas().size() * formation.basis().size()) {
                        incomplete = true;
                        break search;
                    }
                } catch (WorkLimit limit) {
                    incomplete = true;
                    attempts.add(new Attempt(lambda, chart, "BUDGET_INCONCLUSIVE", limit.getMessage(),
                        Optional.ofNullable(result), Optional.ofNullable(replay), Optional.empty()));
                    break search;
                } catch (ExactResidualPolynomialArithmetic.ProjectionLimitExceeded limit) {
                    incomplete = true;
                    attempts.add(new Attempt(lambda, chart, "BUDGET_INCONCLUSIVE", "INDEPENDENT_PROJECTION_LIMIT:" + limit.getMessage(),
                        Optional.ofNullable(result), Optional.ofNullable(replay), Optional.empty()));
                }
            }
        }
        boolean found = attempts.stream().anyMatch(attempt -> attempt.certificate().isPresent());
        Status status = incomplete ? (found ? Status.INCOMPLETE_WITH_INVARIANTS : Status.INCOMPLETE_WITHOUT_INVARIANTS)
            : (found ? Status.COMPLETE_WITH_INVARIANTS : Status.COMPLETE_WITHOUT_INVARIANTS);
        return new Run(formation, attempts, status, work.snapshot());
    }

    private static Attempt checkAttempt(int lambda, int chart, Result result, ReplayedChart replayedChart,
        ExactRecurrenceInductionVerifier verifier) {
        Optional<VerifiedInduction> certificate = Optional.empty();
        String status = result.status().name();
        String detail = result.detailCode();
        if (result.status() == ExactLinearPolynomialHoleSolver.Status.UNIQUE) {
            try {
                certificate = Optional.of(verifier.checkReplayed(replayedChart));
            } catch (ExactRecurrenceInductionVerifier.CoefficientBound rejected) {
                status = "COEFFICIENT_BOUND";
                detail = "UNIQUE_NORMALIZED_VECTOR_OUTSIDE_FROZEN_COEFFICIENT_BOUND";
            } catch (IllegalArgumentException rejected) {
                // Resource failures keep their distinct incomplete outcome at the outer boundary.
                if (rejected instanceof ExactResidualPolynomialArithmetic.ProjectionLimitExceeded limit) { throw limit; }
                status = "CHECK_FAILED";
                detail = "INDEPENDENT_TRANSITION_OR_INITIAL_CHECK_FAILED:" + rejected.getMessage();
            }
        }
        return new Attempt(lambda, chart, status, detail, Optional.of(result), Optional.of(replayedChart.result()), certificate);
    }

    public enum Status { COMPLETE_WITH_INVARIANTS, COMPLETE_WITHOUT_INVARIANTS,
        INCOMPLETE_WITH_INVARIANTS, INCOMPLETE_WITHOUT_INVARIANTS, UNSUPPORTED }

    public record Attempt(int lambdaIndex, int chartIndex, String status, String detailCode,
                          Optional<Result> solverResult, Optional<Result> replayResult, Optional<VerifiedInduction> certificate) {
        public Attempt {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detailCode, "detailCode");
            Objects.requireNonNull(solverResult, "solverResult");
            Objects.requireNonNull(replayResult, "replayResult");
            Objects.requireNonNull(certificate, "certificate");
        }
    }

    /** Issuer-owned complete freeze, including all negative and incomplete attempts. */
    public static final class Run {
        public static final String SCHEMA = "regelsuche.recurrence-invariant-run/v1";
        private final RecurrenceInvariantFormation formation;
        private final List<Attempt> attempts;
        private final Status status;
        private final WorkProfile work;
        private Run(RecurrenceInvariantFormation formation, List<Attempt> attempts, Status status, WorkProfile work) {
            this.formation = formation;
            this.attempts = List.copyOf(attempts);
            this.status = status;
            this.work = work;
        }
        public RecurrenceInvariantFormation formation() { return formation; }
        public List<Attempt> attempts() { return attempts; }
        public Status status() { return status; }
        public WorkProfile work() { return work; }
        public List<VerifiedInduction> certificates() { return attempts.stream().flatMap(a -> a.certificate().stream()).toList(); }
        public String contentHash() { return RecurrenceInvariantFormation.hash(RecurrenceInvariantJson.run(this, false)); }
        public String toCanonicalJson() { return RecurrenceInvariantJson.run(this, true); }
    }

    public record WorkProfile(int configured, int formation, int solver, int solverReplay, int transitionCheck, int initialCheck) {
        public WorkProfile {
            if (configured < 0 || formation < 0 || solver < 0 || solverReplay < 0 || transitionCheck < 0 || initialCheck < 0
                    || (long) formation + solver + solverReplay + transitionCheck + initialCheck > configured) {
                throw new IllegalArgumentException("invariant work must be nonnegative and cumulatively bounded");
            }
        }
        public int consumed() { return formation + solver + solverReplay + transitionCheck + initialCheck; }
        public int remaining() { return configured - consumed(); }
    }

    enum Stage { FORMATION, SOLVER, SOLVER_REPLAY, TRANSITION_CHECK, INITIAL_CHECK }
    static final class Work {
        private final int configured;
        private final int[] stages = new int[Stage.values().length];
        private int consumed;
        Work(int configured) {
            if (configured < 0 || configured > 10_000_000) { throw new IllegalArgumentException("verification work exceeds hard bounds"); }
            this.configured = configured;
        }
        int remaining() { return configured - consumed; }
        void admit(int delegatedMaximum) {
            if (delegatedMaximum < 0 || delegatedMaximum > remaining()) { throw new WorkLimit("DELEGATED_BUDGET_NOT_ADMITTED"); }
        }
        void spend(Stage stage, long units) {
            if (units < 0) { throw new IllegalArgumentException("negative work"); }
            int accepted = (int) Math.min(units, remaining());
            stages[stage.ordinal()] += accepted;
            consumed += accepted;
            if (accepted != units) { throw new WorkLimit("CUMULATIVE_WORK_BUDGET_EXHAUSTED"); }
        }
        WorkProfile snapshot() { return new WorkProfile(configured, stages[0], stages[1], stages[2], stages[3], stages[4]); }
    }
    static final class WorkLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        WorkLimit(String message) { super(message); }
    }

    /** The namespaces are generated from integer positions, never caller-supplied identifiers. */
    static String template(RecurrenceInvariantFormation formation, int lambda, int chart, Work work) {
        List<String> source = new ArrayList<>();
        for (int i = 0; i < formation.recurrence().order(); i++) { source.add("state" + i); }
        List<String> shifted = new ArrayList<>(source.subList(1, source.size()));
        Expression last = new Expression(work);
        for (int i = 0; i < source.size(); i++) {
            if (i > 0) { last.append("+"); }
            last.append("(").append(formation.recurrence().coefficients().get(i).canonicalText()).append(")*").append(source.get(i));
        }
        shifted.add(last.value());
        String before = invariant(formation, source, work);
        String after = invariant(formation, shifted, work);
        Expression expression = new Expression(work);
        expression.append("(").append(after).append(")-(").append(formation.lambdas().get(lambda).canonicalText())
            .append(")*(").append(before).append(")");
        for (int i = 0; i <= chart; i++) {
            expression.append("+normalizer").append(Integer.toString(i)).append("*(${coefficient")
                .append(Integer.toString(i)).append("}");
            if (i == chart) { expression.append("-1"); }
            expression.append(")");
        }
        return expression.value();
    }

    private static String invariant(RecurrenceInvariantFormation formation, List<String> states, Work work) {
        Expression expression = new Expression(work);
        for (int index = 0; index < formation.basis().size(); index++) {
            if (index > 0) { expression.append("+"); }
            expression.append("${coefficient").append(Integer.toString(index)).append("}");
            var exponents = formation.basis().get(index).exponents();
            for (int state = 0; state < exponents.size(); state++) {
                if (exponents.get(state) != 0) {
                    expression.append("*(").append(states.get(state)).append(")^").append(Integer.toString(exponents.get(state)));
                }
            }
        }
        return expression.value();
    }

    static final class Expression {
        private final StringBuilder text = new StringBuilder();
        private final Work work;
        private final Stage stage;
        Expression(Work work) { this(work, Stage.FORMATION); }
        Expression(Work work, Stage stage) { this.work = work; this.stage = stage; }
        Expression append(String part) {
            if (part.length() > 16_384 - text.length()) { throw new WorkLimit("GENERATED_EXPRESSION_LENGTH_LIMIT"); }
            work.spend(stage, part.length());
            text.append(part);
            return this;
        }
        String value() { return text.toString(); }
    }
}
