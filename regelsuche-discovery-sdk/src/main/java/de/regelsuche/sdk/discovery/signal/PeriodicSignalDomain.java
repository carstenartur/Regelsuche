package de.regelsuche.sdk.discovery.signal;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.signal.CyclotomicDftVerifier;
import de.regelsuche.discovery.signal.FourierQuery;
import de.regelsuche.discovery.signal.PeriodicFourier;
import de.regelsuche.discovery.signal.PeriodicFourier.Plan;
import de.regelsuche.discovery.signal.SignalWork;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.discovery.DiscoveryDomainBuilder;
import de.regelsuche.sdk.discovery.DiscoveryInputCodec;
import de.regelsuche.sdk.discovery.TypedDiscoveryDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded plan selection using imported Fourier knowledge and an independent exact audit. */
public final class PeriodicSignalDomain {
    public static final String DOMAIN_ID = "periodic-divisibility-signals";
    public static final String REVISION = "v1";
    private static final DiscoveryInputCodec<PeriodicSignalStudy> CODEC =
        DiscoveryInputCodec.of(PeriodicSignalStudy::canonical, PeriodicSignalStudy::parse);

    private PeriodicSignalDomain() { }

    public static TypedDiscoveryDomain<PeriodicSignalStudy, State, Candidate, Certificate> typedDomain() {
        return TypedDiscoveryDomain.of(domain(), CODEC);
    }

    public static DiscoveryDomain<State, Candidate, Certificate> domain() {
        return DiscoveryDomainBuilder.<State, Candidate, Certificate>domain(DOMAIN_ID, REVISION)
            .generator(seed -> {
                var study = CODEC.decode(seed.payload());
                // Evaluate each known plan on TRAINING ONLY, once. Retain this selection cost.
                var profiles = new TrainingProfiles(profile(study.training(), Plan.DIRECT),
                    profile(study.training(), Plan.MERGE_PERIODS));
                return List.of(new State(study, Optional.empty(), profiles));
            })
            .stateCodec(State::canonical)
            .invariant("bounded-divisor-input", state -> InvariantResult.pass())
            .operator("direct-combs", state -> successor(state, Plan.DIRECT))
            .operator("merge-equal-periods", state -> successor(state, Plan.MERGE_PERIODS))
            .objective(state -> new ObjectiveAssessment(state.plan().map(plan ->
                    Math.toIntExact(-2 * state.profiles().forPlan(plan).total().units()
                        - plan.ordinal())).orElse(0),
                state.plan().isPresent(), Map.of("selection", "training-work-only")))
            .candidate(context -> new Candidate(context.currentState().study(),
                context.currentState().plan().orElseThrow(), context.currentState().profiles()),
                Candidate::canonical)
            .counterexamples(PeriodicSignalDomain::counterexamples)
            .evaluator(PeriodicSignalDomain::evaluate)
            .certificate("EXACT_FINITE_DFT_WITNESS", Certificate::canonical, Certificate::canonical)
            .build();
    }

    private static List<Successor<State>> successor(State state, Plan plan) {
        if (state.plan().isPresent()) return List.of();
        return List.of(new Successor<>("select-" + plan.name().toLowerCase(java.util.Locale.ROOT),
            new State(state.study(), Optional.of(plan), state.profiles()), 1, true,
            List.of("d divides L", "unshifted rational combs", "normalized forward DFT"),
            Map.of("importedIdentity", "DFT([d|k])(j)=[L/d|j]/d",
                "workModel", "explicit-arithmetic-and-coefficient-cells/v1")));
    }

    private static PlanWork profile(List<FourierQuery> queries, Plan plan) {
        var cost = PlanWork.ZERO;
        for (var query : queries) cost = cost.plus(PeriodicFourier.evaluate(query, plan));
        return cost;
    }

    private static CounterexampleResult counterexamples(Candidate candidate, int budget) {
        if (budget < 1) return CounterexampleResult.inconclusive(0, "no coefficient audit budget", Map.of());
        int attempts = 0;
        var evaluation = SignalWork.ZERO;
        var verification = SignalWork.ZERO;
        for (var query : candidate.study().training()) {
            int size = Math.min(budget - attempts, query.frequencies().size());
            if (size == 0) break;
            var limited = new FourierQuery(query.signal(), query.frequencies().subList(0, size));
            var output = PeriodicFourier.evaluate(limited, candidate.plan());
            var check = CyclotomicDftVerifier.verify(limited, output.coefficients());
            evaluation = evaluation.plus(output.total());
            verification = verification.plus(check.work());
            attempts += check.checkedCoefficients();
            if (!check.accepted()) {
                return CounterexampleResult.found(attempts,
                    query.canonical() + ";" + check.counterexample().orElseThrow(),
                    auditMetrics(evaluation, verification));
            }
        }
        int required = candidate.study().training().stream().mapToInt(q -> q.frequencies().size()).sum();
        if (attempts < required) {
            return CounterexampleResult.inconclusive(attempts,
                "coefficient audit budget did not cover all training queries",
                auditMetrics(evaluation, verification));
        }
        return CounterexampleResult.noneFound(attempts, auditMetrics(evaluation, verification));
    }

    private static Map<String, String> auditMetrics(SignalWork evaluation, SignalWork verification) {
        return Map.of("trainingAuditEvaluationWork", evaluation.toString(),
            "trainingAuditVerificationWork", verification.toString());
    }

    private static Evaluation<Certificate> evaluate(Candidate candidate) {
        var directWork = PlanWork.ZERO;
        var mergedWork = PlanWork.ZERO;
        var verification = SignalWork.ZERO;
        var outputs = new ArrayList<List<ExactRational>>();
        int checks = 0;
        for (var query : candidate.study().holdout()) {
            // Fixed baselines get the same primitives. The plan is already frozen.
            var direct = PeriodicFourier.evaluate(query, Plan.DIRECT);
            var merged = PeriodicFourier.evaluate(query, Plan.MERGE_PERIODS);
            directWork = directWork.plus(direct);
            mergedWork = mergedWork.plus(merged);
            var selected = candidate.plan() == Plan.DIRECT ? direct : merged;
            var check = CyclotomicDftVerifier.verify(query, selected.coefficients());
            verification = verification.plus(check.work());
            checks += check.checkedCoefficients();
            if (!check.accepted() || !direct.coefficients().equals(merged.coefficients())) {
                return Evaluation.refuted("holdout coefficient disagreement", Map.of(
                    "query", query.canonical(), "verification", check.toString()));
            }
            outputs.add(selected.coefficients());
        }
        var certificate = new Certificate(candidate, new Comparison(candidate.plan(), directWork, mergedWork),
            outputs, checks, verification);
        return Evaluation.confirmed(certificate, "selected plan passed the exact finite holdout audit", Map.of(
            "evidenceStrength", certificate.evidenceStrength(),
            "selectionWork", certificate.selectionWork().toString(),
            "holdoutVerificationWork", verification.toString(),
            "selectedHoldoutWork", certificate.comparison().selectedWork().toString()));
    }

    public record PlanWork(SignalWork construction, SignalWork evaluation) {
        public static final PlanWork ZERO = new PlanWork(SignalWork.ZERO, SignalWork.ZERO);
        public PlanWork {
            Objects.requireNonNull(construction, "construction");
            Objects.requireNonNull(evaluation, "evaluation");
        }
        public SignalWork total() { return construction.plus(evaluation); }
        PlanWork plus(PeriodicFourier.Result result) {
            return new PlanWork(construction.plus(result.construction()), evaluation.plus(result.evaluation()));
        }
    }

    public record TrainingProfiles(PlanWork direct, PlanWork merged) {
        public TrainingProfiles {
            Objects.requireNonNull(direct, "direct");
            Objects.requireNonNull(merged, "merged");
        }
        public PlanWork forPlan(Plan plan) { return plan == Plan.DIRECT ? direct : merged; }
        public SignalWork selectionWork() { return direct.total().plus(merged.total()); }
    }

    public record State(PeriodicSignalStudy study, Optional<Plan> plan, TrainingProfiles profiles) {
        public State {
            Objects.requireNonNull(study, "study");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(profiles, "profiles");
        }
        public String canonical() { return study.canonical() + "\nplan=" + plan + "\ntraining=" + profiles; }
    }

    public record Candidate(PeriodicSignalStudy study, Plan plan, TrainingProfiles profiles) {
        public Candidate {
            Objects.requireNonNull(study, "study");
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(profiles, "profiles");
        }
        public String canonical() { return study.canonical() + "\nplan=" + plan + "\ntraining=" + profiles; }
    }

    /** Arithmetic measurements on holdout; baseline evaluation overhead is retained separately. */
    public record Comparison(Plan selectedPlan, PlanWork direct, PlanWork merged) {
        public Comparison {
            Objects.requireNonNull(selectedPlan, "selectedPlan");
            Objects.requireNonNull(direct, "direct");
            Objects.requireNonNull(merged, "merged");
        }
        public SignalWork directWork() { return direct.total(); }
        public SignalWork mergedWork() { return merged.total(); }
        public SignalWork selectedWork() { return selectedPlan == Plan.DIRECT ? directWork() : mergedWork(); }
        public SignalWork comparisonWork() { return directWork().plus(mergedWork()); }
    }

    public record Certificate(Candidate candidate, Comparison comparison,
                              List<List<ExactRational>> holdoutCoefficients, int holdoutChecks,
                              SignalWork verificationWork) {
        public Certificate {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(comparison, "comparison");
            holdoutCoefficients = holdoutCoefficients.stream().map(List::copyOf).toList();
            Objects.requireNonNull(verificationWork, "verificationWork");
        }
        public SignalWork selectionWork() { return candidate.profiles().selectionWork(); }
        public String evidenceStrength() { return "EXACT_FINITE_DFT_WITNESS_NOT_UNIVERSAL_PROOF"; }
        public String canonical() {
            return candidate.canonical() + "\ncomparison=" + comparison + "\ncoefficients=" + holdoutCoefficients
                + "\nholdoutChecks=" + holdoutChecks + "\nholdoutVerification=" + verificationWork
                + "\nstrength=" + evidenceStrength();
        }
    }
}
