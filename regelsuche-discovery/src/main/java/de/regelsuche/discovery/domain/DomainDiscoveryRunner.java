package de.regelsuche.discovery.domain;

import de.regelsuche.discovery.domain.DiscoveryDomain.CandidateContext;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.DomainPayload;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.EvaluationStatus;
import de.regelsuche.discovery.domain.DiscoveryDomain.Invariant;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import de.regelsuche.discovery.domain.DiscoveryDomain.PathStep;
import de.regelsuche.discovery.domain.DiscoveryDomain.RenderedCertificate;
import de.regelsuche.discovery.domain.DiscoveryDomain.Successor;
import de.regelsuche.discovery.domain.DiscoveryDomain.TransitionOperator;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.AttemptDisposition;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.CandidateAttempt;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.EvaluationDisposition;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Resource;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.ResourceLine;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.StateTrace;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.TransitionTrace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/** Deterministic bounded orchestrator for {@link DiscoveryDomain} contracts. */
public final class DomainDiscoveryRunner {
    public <S, C, K> RunResult<C, K> run(
        String campaignId,
        DiscoveryDomain<S, C, K> domain,
        DiscoverySeed seed,
        DiscoveryBudget budget
    ) {
        DomainCanonical.requireIdentifier(campaignId, "campaignId");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(budget, "budget");
        if (!domain.domainId().equals(seed.domainId())) {
            throw new IllegalArgumentException(
                "seed domain " + seed.domainId()
                    + " does not match " + domain.domainId());
        }
        return new Execution<>(campaignId, domain, seed, budget).execute();
    }

    public record RunResult<C, K>(
        Optional<C> selectedCandidate,
        Optional<K> selectedCertificate,
        DomainDiscoveryEvidence evidence
    ) {
        public RunResult {
            selectedCandidate = selectedCandidate == null
                ? Optional.empty()
                : selectedCandidate;
            selectedCertificate = selectedCertificate == null
                ? Optional.empty()
                : selectedCertificate;
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    private static final class Execution<S, C, K> {
        private final String campaignId;
        private final DiscoveryDomain<S, C, K> domain;
        private final DiscoverySeed seed;
        private final DiscoveryBudget budget;
        private final DiscoveryDomainDescriptor descriptor;
        private final PriorityQueue<Node<S>> frontier;
        private final Set<String> visitedStates = new HashSet<>();
        private final Set<String> attemptedCandidates = new HashSet<>();
        private final List<StateTrace> stateTraces = new ArrayList<>();
        private final List<TransitionTrace> transitionTraces = new ArrayList<>();
        private final List<CandidateAttempt> candidateAttempts = new ArrayList<>();

        private S firstGeneratedState;
        private S firstValidInitialState;
        private S evidenceInitialState;
        private C selectedCandidate;
        private K selectedCertificateObject;
        private RenderedCertificate selectedCertificate;
        private String selectedCandidateHash = "";

        private int exploredStates;
        private int generatedSuccessors;
        private int counterexampleAttemptsUsed;
        private int candidateEvaluationsExecuted;
        private int candidateEvaluationsSkipped;
        private int certificateAttemptsExecuted;
        private int certificateAttemptsSkipped;
        private int stateSequence;
        private int transitionSequence;
        private boolean generationBudgetReached;
        private boolean candidateBudgetReached;

        private Execution(
            String campaignId,
            DiscoveryDomain<S, C, K> domain,
            DiscoverySeed seed,
            DiscoveryBudget budget
        ) {
            this.campaignId = campaignId;
            this.domain = domain;
            this.seed = seed;
            this.budget = budget;
            this.descriptor = domain.descriptor();
            this.frontier = new PriorityQueue<>(Comparator
                .<Node<S>>comparingInt(node -> node.objective().score())
                .reversed()
                .thenComparingInt(Node::depth)
                .thenComparing(Node::stateHash));
        }

        private RunResult<C, K> execute() {
            initializeFrontier();
            if (frontier.isEmpty()) {
                return finish(Outcome.INVALID_SEED);
            }
            while (canExplore()) {
                Node<S> node = frontier.remove();
                retainState(node);
                if (node.objective().candidateReady()) {
                    evaluateCandidate(node);
                }
                if (selectedCandidate != null) {
                    break;
                }
                expand(node);
            }
            return finish(determineOutcome());
        }

        private void initializeFrontier() {
            List<S> generated = Objects.requireNonNull(
                domain.generator().generate(seed),
                "domain generator returned null");
            if (!generated.isEmpty()) {
                firstGeneratedState = generated.getFirst();
            }
            List<PreparedState<S>> prepared = generated.stream()
                .map(state -> prepareState(domain, state))
                .sorted(Comparator.comparing(PreparedState::canonicalState))
                .toList();
            for (PreparedState<S> state : prepared) {
                addInitialState(state);
            }
            evidenceInitialState = firstValidInitialState;
        }

        private void addInitialState(PreparedState<S> prepared) {
            List<String> blockers = invariantBlockers(domain, prepared.state());
            if (!blockers.isEmpty() || !visitedStates.add(prepared.stateHash())) {
                return;
            }
            if (firstValidInitialState == null) {
                firstValidInitialState = prepared.state();
            }
            frontier.add(new Node<>(
                prepared.state(),
                prepared.state(),
                prepared.canonicalState(),
                prepared.stateHash(),
                0,
                "",
                "",
                domain.objective().assess(prepared.state()),
                List.of()));
        }

        private boolean canExplore() {
            return !frontier.isEmpty()
                && exploredStates < budget.maxExploredStates();
        }

        private void retainState(Node<S> node) {
            exploredStates++;
            stateSequence++;
            stateTraces.add(new StateTrace(
                stateSequence,
                node.stateHash(),
                node.canonicalState(),
                node.depth(),
                node.objective().score(),
                node.objective().candidateReady(),
                node.parentStateHash(),
                node.actionId(),
                node.objective().metrics()));
        }

        private void evaluateCandidate(Node<S> node) {
            if (candidateAttempts.size() >= budget.maxCandidateAttempts()) {
                candidateBudgetReached = true;
                return;
            }
            C candidate = extractCandidate(node);
            String candidateHash = domain.candidateCodec().contentHash(candidate);
            if (!attemptedCandidates.add(candidateHash)) {
                return;
            }
            CounterexampleResult counterexample = searchCounterexamples(candidate);
            CandidateAttemptResult<K> result = switch (counterexample.status()) {
                case FOUND -> skippedEvaluation(
                    CandidateAttemptResult.counterexample(candidateHash, counterexample));
                case INCONCLUSIVE -> skippedEvaluation(
                    CandidateAttemptResult.inconclusive(candidateHash, counterexample));
                case UNSUPPORTED -> skippedEvaluation(
                    CandidateAttemptResult.unsupported(candidateHash, counterexample));
                case NONE_FOUND -> evaluateAfterCounterexamples(
                    candidate, candidateHash, counterexample);
            };
            candidateAttempts.add(result.toEvidence(candidateAttempts.size() + 1));
            if (result.disposition() == AttemptDisposition.CONFIRMED) {
                selectedCandidate = candidate;
                selectedCandidateHash = candidateHash;
                evidenceInitialState = node.initialState();
            }
        }

        private C extractCandidate(Node<S> node) {
            CandidateContext<S> context = new CandidateContext<>(
                node.initialState(), node.state(), node.path());
            return Objects.requireNonNull(
                domain.candidateExtractor().extract(context),
                "candidate extractor returned null");
        }

        private CounterexampleResult searchCounterexamples(C candidate) {
            int remaining = Math.max(
                0,
                budget.maxCounterexampleAttempts() - counterexampleAttemptsUsed);
            if (remaining == 0) {
                return new CounterexampleResult(
                    CounterexampleStatus.INCONCLUSIVE,
                    0,
                    "counterexample budget exhausted",
                    Map.of("budget", "0"));
            }
            CounterexampleResult result = Objects.requireNonNull(
                domain.counterexampleGenerator().search(candidate, remaining),
                "counterexample generator returned null");
            if (result.attempts() > remaining) {
                throw new IllegalStateException(
                    "counterexample generator exceeded its attempt budget");
            }
            counterexampleAttemptsUsed += result.attempts();
            return result;
        }

        private CandidateAttemptResult<K> skippedEvaluation(
            CandidateAttemptResult<K> result
        ) {
            candidateEvaluationsSkipped++;
            return result;
        }

        private CandidateAttemptResult<K> evaluateAfterCounterexamples(
            C candidate,
            String candidateHash,
            CounterexampleResult counterexample
        ) {
            candidateEvaluationsExecuted++;
            Evaluation<K> evaluation = Objects.requireNonNull(
                domain.evaluator().evaluate(candidate),
                "candidate evaluator returned null");
            if (evaluation.status() != EvaluationStatus.CONFIRMED) {
                certificateAttemptsSkipped++;
                return CandidateAttemptResult.evaluated(
                    candidateHash, counterexample, evaluation);
            }
            return confirm(candidateHash, counterexample, evaluation);
        }

        private CandidateAttemptResult<K> confirm(
            String candidateHash,
            CounterexampleResult counterexample,
            Evaluation<K> evaluation
        ) {
            selectedCertificateObject = Objects.requireNonNull(
                evaluation.certificate(),
                "confirmed evaluation certificate");
            String objectHash = domain.certificateCodec()
                .contentHash(selectedCertificateObject);
            selectedCertificate = RenderedCertificate.create(
                domain.certificateRenderer().render(selectedCertificateObject),
                objectHash);
            certificateAttemptsExecuted++;
            return CandidateAttemptResult.confirmed(
                candidateHash,
                counterexample,
                evaluation,
                selectedCertificate);
        }

        private void expand(Node<S> node) {
            if (node.depth() >= budget.maxDepth()) {
                return;
            }
            if (generatedSuccessors >= budget.maxGeneratedSuccessors()) {
                generationBudgetReached = true;
                return;
            }
            List<PreparedSuccessor<S>> successors = prepareSuccessors(
                domain, node.state());
            int processed = 0;
            for (PreparedSuccessor<S> successor : successors) {
                if (processed >= budget.maxCandidatesPerState()) {
                    break;
                }
                if (generatedSuccessors >= budget.maxGeneratedSuccessors()) {
                    generationBudgetReached = true;
                    break;
                }
                processed++;
                processSuccessor(node, successor);
            }
        }

        private void processSuccessor(
            Node<S> node,
            PreparedSuccessor<S> prepared
        ) {
            generatedSuccessors++;
            transitionSequence++;
            List<String> blockers = transitionBlockers(prepared);
            boolean accepted = blockers.isEmpty();
            retainTransition(node, prepared, accepted, blockers);
            if (!accepted) {
                return;
            }
            visitedStates.add(prepared.stateHash());
            frontier.add(successorNode(node, prepared));
        }

        private List<String> transitionBlockers(PreparedSuccessor<S> prepared) {
            List<String> blockers = new ArrayList<>(
                invariantBlockers(domain, prepared.successor().state()));
            if (visitedStates.contains(prepared.stateHash())) {
                blockers.add("duplicate-state");
            }
            return DomainCanonical.sortedDistinct(blockers);
        }

        private void retainTransition(
            Node<S> node,
            PreparedSuccessor<S> prepared,
            boolean accepted,
            List<String> blockers
        ) {
            Successor<S> successor = prepared.successor();
            transitionTraces.add(new TransitionTrace(
                transitionSequence,
                node.stateHash(),
                prepared.stateHash(),
                successor.actionId(),
                successor.cost(),
                successor.semanticsPreserving(),
                successor.assumptions(),
                withComponentOperator(
                    successor.metadata(), prepared.componentOperatorId()),
                accepted,
                blockers));
        }

        private Node<S> successorNode(
            Node<S> parent,
            PreparedSuccessor<S> prepared
        ) {
            Successor<S> successor = prepared.successor();
            List<PathStep<S>> path = new ArrayList<>(parent.path());
            path.add(new PathStep<>(
                parent.stateHash(),
                successor.actionId(),
                successor.state(),
                prepared.stateHash(),
                successor.cost(),
                successor.semanticsPreserving(),
                successor.assumptions(),
                withComponentOperator(
                    successor.metadata(), prepared.componentOperatorId())));
            return new Node<>(
                parent.initialState(),
                successor.state(),
                prepared.canonicalState(),
                prepared.stateHash(),
                parent.depth() + 1,
                parent.stateHash(),
                successor.actionId(),
                domain.objective().assess(successor.state()),
                List.copyOf(path));
        }

        private Outcome determineOutcome() {
            if (selectedCandidate != null) {
                return Outcome.CONFIRMED;
            }
            boolean explorationBudgetReached =
                exploredStates >= budget.maxExploredStates() && !frontier.isEmpty();
            if (generationBudgetReached
                    || candidateBudgetReached
                    || explorationBudgetReached) {
                return Outcome.BUDGET_EXHAUSTED;
            }
            if (candidateAttempts.isEmpty()) {
                return Outcome.INCONCLUSIVE;
            }
            if (candidateAttempts.stream().allMatch(Execution::isRefuted)) {
                return Outcome.REFUTED;
            }
            if (candidateAttempts.stream().allMatch(attempt ->
                    attempt.disposition() == AttemptDisposition.UNSUPPORTED)) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.INCONCLUSIVE;
        }

        private static boolean isRefuted(CandidateAttempt attempt) {
            return attempt.disposition()
                == AttemptDisposition.REFUTED_BY_COUNTEREXAMPLE
                || attempt.disposition()
                == AttemptDisposition.REFUTED_BY_EVALUATOR;
        }

        private RunResult<C, K> finish(Outcome outcome) {
            Optional<C> candidate = Optional.ofNullable(selectedCandidate);
            Optional<K> certificateObject = Optional.ofNullable(
                selectedCertificateObject);
            DomainPayload payload = domain.evidenceAdapter().adapt(
                Optional.ofNullable(evidenceInitialState != null
                    ? evidenceInitialState
                    : firstGeneratedState),
                candidate,
                certificateObject);
            DomainDiscoveryEvidence evidence = new DomainDiscoveryEvidence(
                campaignId,
                descriptor,
                seed,
                budget,
                outcome,
                stateTraces,
                transitionTraces,
                candidateAttempts,
                resourceLines(),
                selectedCandidateHash,
                selectedCertificate,
                payload);
            return new RunResult<>(candidate, certificateObject, evidence);
        }

        private List<ResourceLine> resourceLines() {
            return List.of(
                line(Resource.EXPLORED_STATES,
                    budget.maxExploredStates(), exploredStates, 0),
                line(Resource.GENERATED_SUCCESSORS,
                    budget.maxGeneratedSuccessors(), generatedSuccessors, 0),
                line(Resource.CANDIDATE_EVALUATIONS,
                    budget.maxCandidateAttempts(),
                    candidateEvaluationsExecuted,
                    candidateEvaluationsSkipped),
                line(Resource.COUNTEREXAMPLE_ATTEMPTS,
                    budget.maxCounterexampleAttempts(),
                    counterexampleAttemptsUsed,
                    0),
                line(Resource.CERTIFICATE_ATTEMPTS,
                    budget.maxCandidateAttempts(),
                    certificateAttemptsExecuted,
                    certificateAttemptsSkipped));
        }
    }

    private static <S, C, K> PreparedState<S> prepareState(
        DiscoveryDomain<S, C, K> domain,
        S state
    ) {
        Objects.requireNonNull(state, "generated state");
        String canonical = DomainCanonical.requireText(
            domain.stateCodec().canonicalForm(state),
            "canonical state");
        return new PreparedState<>(state, canonical, DomainCanonical.sha256(canonical));
    }

    private static <S, C, K> List<PreparedSuccessor<S>> prepareSuccessors(
        DiscoveryDomain<S, C, K> domain,
        S state
    ) {
        List<PreparedSuccessor<S>> result = new ArrayList<>();
        List<TransitionOperator<S>> operators = domain.operators().stream()
            .sorted(Comparator.comparing(TransitionOperator::id))
            .toList();
        for (TransitionOperator<S> operator : operators) {
            addPreparedSuccessors(domain, state, operator, result);
        }
        result.sort(Comparator
            .comparing((PreparedSuccessor<S> successor) ->
                successor.successor().actionId())
            .thenComparing(PreparedSuccessor::canonicalState)
            .thenComparing(PreparedSuccessor::componentOperatorId));
        return List.copyOf(result);
    }

    private static <S, C, K> void addPreparedSuccessors(
        DiscoveryDomain<S, C, K> domain,
        S state,
        TransitionOperator<S> operator,
        List<PreparedSuccessor<S>> result
    ) {
        List<Successor<S>> generated = Objects.requireNonNull(
            operator.apply(state),
            "transition operator returned null: " + operator.id());
        for (Successor<S> successor : generated) {
            PreparedState<S> prepared = prepareState(domain, successor.state());
            result.add(new PreparedSuccessor<>(
                operator.id(),
                successor,
                prepared.canonicalState(),
                prepared.stateHash()));
        }
    }

    private static <S, C, K> List<String> invariantBlockers(
        DiscoveryDomain<S, C, K> domain,
        S state
    ) {
        List<String> blockers = new ArrayList<>();
        List<Invariant<S>> invariants = domain.invariants().stream()
            .sorted(Comparator.comparing(Invariant::id))
            .toList();
        for (Invariant<S> invariant : invariants) {
            addInvariantBlockers(state, invariant, blockers);
        }
        return DomainCanonical.sortedDistinct(blockers);
    }

    private static <S> void addInvariantBlockers(
        S state,
        Invariant<S> invariant,
        List<String> blockers
    ) {
        DiscoveryDomain.InvariantResult result = Objects.requireNonNull(
            invariant.check(state),
            "invariant returned null: " + invariant.id());
        if (!result.accepted()) {
            result.blockers().forEach(blocker ->
                blockers.add(invariant.id() + ":" + blocker));
        }
    }

    private static Map<String, String> withComponentOperator(
        Map<String, String> metadata,
        String componentOperatorId
    ) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>(metadata);
        result.put("componentOperatorId", componentOperatorId);
        return DomainCanonical.sortedMap(result);
    }

    private static ResourceLine line(
        Resource resource,
        int configured,
        int executed,
        int skipped
    ) {
        if (executed + skipped > configured) {
            throw new IllegalStateException(
                resource + " exceeded configured work");
        }
        return new ResourceLine(
            resource,
            configured,
            executed,
            skipped,
            configured - executed - skipped);
    }

    private record PreparedState<S>(
        S state,
        String canonicalState,
        String stateHash
    ) {
    }

    private record PreparedSuccessor<S>(
        String componentOperatorId,
        Successor<S> successor,
        String canonicalState,
        String stateHash
    ) {
    }

    private record Node<S>(
        S initialState,
        S state,
        String canonicalState,
        String stateHash,
        int depth,
        String parentStateHash,
        String actionId,
        ObjectiveAssessment objective,
        List<PathStep<S>> path
    ) {
    }

    private record CandidateAttemptResult<K>(
        String candidateHash,
        AttemptDisposition disposition,
        CounterexampleResult counterexample,
        EvaluationDisposition evaluationStatus,
        String evaluationSummary,
        RenderedCertificate certificate,
        Map<String, String> metrics
    ) {
        private static <K> CandidateAttemptResult<K> counterexample(
            String candidateHash,
            CounterexampleResult counterexample
        ) {
            return new CandidateAttemptResult<>(
                candidateHash,
                AttemptDisposition.REFUTED_BY_COUNTEREXAMPLE,
                counterexample,
                EvaluationDisposition.NOT_RUN,
                "counterexample found before evaluator invocation",
                null,
                prefixedMetrics("counterexample.", counterexample.metrics()));
        }

        private static <K> CandidateAttemptResult<K> inconclusive(
            String candidateHash,
            CounterexampleResult counterexample
        ) {
            return new CandidateAttemptResult<>(
                candidateHash,
                AttemptDisposition.INCONCLUSIVE,
                counterexample,
                EvaluationDisposition.NOT_RUN,
                "counterexample search was inconclusive",
                null,
                prefixedMetrics("counterexample.", counterexample.metrics()));
        }

        private static <K> CandidateAttemptResult<K> unsupported(
            String candidateHash,
            CounterexampleResult counterexample
        ) {
            return new CandidateAttemptResult<>(
                candidateHash,
                AttemptDisposition.UNSUPPORTED,
                counterexample,
                EvaluationDisposition.NOT_RUN,
                "counterexample search is unsupported",
                null,
                prefixedMetrics("counterexample.", counterexample.metrics()));
        }

        private static <K> CandidateAttemptResult<K> confirmed(
            String candidateHash,
            CounterexampleResult counterexample,
            Evaluation<K> evaluation,
            RenderedCertificate certificate
        ) {
            return new CandidateAttemptResult<>(
                candidateHash,
                AttemptDisposition.CONFIRMED,
                counterexample,
                EvaluationDisposition.CONFIRMED,
                evaluation.summary(),
                certificate,
                mergedMetrics(counterexample.metrics(), evaluation.metrics()));
        }

        private static <K> CandidateAttemptResult<K> evaluated(
            String candidateHash,
            CounterexampleResult counterexample,
            Evaluation<K> evaluation
        ) {
            return new CandidateAttemptResult<>(
                candidateHash,
                disposition(evaluation.status()),
                counterexample,
                evaluationDisposition(evaluation.status()),
                evaluation.summary(),
                null,
                mergedMetrics(counterexample.metrics(), evaluation.metrics()));
        }

        private static AttemptDisposition disposition(EvaluationStatus status) {
            return switch (status) {
                case REFUTED -> AttemptDisposition.REFUTED_BY_EVALUATOR;
                case INCONCLUSIVE -> AttemptDisposition.INCONCLUSIVE;
                case UNSUPPORTED -> AttemptDisposition.UNSUPPORTED;
                case CONFIRMED -> throw new IllegalArgumentException(
                    "confirmed evaluation must use confirmed factory");
            };
        }

        private static EvaluationDisposition evaluationDisposition(
            EvaluationStatus status
        ) {
            return switch (status) {
                case CONFIRMED -> EvaluationDisposition.CONFIRMED;
                case REFUTED -> EvaluationDisposition.REFUTED;
                case INCONCLUSIVE -> EvaluationDisposition.INCONCLUSIVE;
                case UNSUPPORTED -> EvaluationDisposition.UNSUPPORTED;
            };
        }

        private CandidateAttempt toEvidence(int sequence) {
            return new CandidateAttempt(
                sequence,
                candidateHash,
                disposition,
                counterexample.status(),
                counterexample.attempts(),
                counterexample.witness(),
                evaluationStatus,
                evaluationSummary,
                certificate == null ? "" : certificate.contentHash(),
                metrics);
        }

        private static Map<String, String> mergedMetrics(
            Map<String, String> counterexample,
            Map<String, String> evaluation
        ) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            result.putAll(prefixedMetrics("counterexample.", counterexample));
            result.putAll(prefixedMetrics("evaluation.", evaluation));
            return DomainCanonical.sortedMap(result);
        }

        private static Map<String, String> prefixedMetrics(
            String prefix,
            Map<String, String> values
        ) {
            Map<String, String> result = new HashMap<>();
            values.forEach((key, value) -> result.put(prefix + key, value));
            return DomainCanonical.sortedMap(result);
        }
    }
}
