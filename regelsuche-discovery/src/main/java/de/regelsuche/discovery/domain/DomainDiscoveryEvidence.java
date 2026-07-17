package de.regelsuche.discovery.domain;

import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleStatus;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DiscoveryDomain.DomainPayload;
import de.regelsuche.discovery.domain.DiscoveryDomain.RenderedCertificate;
import de.regelsuche.json.JsonWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Canonical, domain-neutral execution evidence for one bounded discovery run. */
public final class DomainDiscoveryEvidence {
    public static final String SCHEMA = "regelsuche.domain-discovery-evidence/v1";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    private final String campaignId;
    private final DiscoveryDomainDescriptor descriptor;
    private final DiscoverySeed seed;
    private final DiscoveryBudget budget;
    private final Outcome outcome;
    private final List<StateTrace> states;
    private final List<TransitionTrace> transitions;
    private final List<CandidateAttempt> candidateAttempts;
    private final List<ResourceLine> resources;
    private final String selectedCandidateHash;
    private final RenderedCertificate certificate;
    private final DomainPayload domainEvidence;
    private final String contentHash;

    public DomainDiscoveryEvidence(
        String campaignId,
        DiscoveryDomainDescriptor descriptor,
        DiscoverySeed seed,
        DiscoveryBudget budget,
        Outcome outcome,
        List<StateTrace> states,
        List<TransitionTrace> transitions,
        List<CandidateAttempt> candidateAttempts,
        List<ResourceLine> resources,
        String selectedCandidateHash,
        RenderedCertificate certificate,
        DomainPayload domainEvidence
    ) {
        this.campaignId = DomainCanonical.requireIdentifier(campaignId, "campaignId");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.seed = Objects.requireNonNull(seed, "seed");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if (!descriptor.domainId().equals(seed.domainId())) {
            throw new IllegalArgumentException(
                "descriptor and seed domains do not match");
        }
        this.states = DomainCanonical.sortedCopy(
            states,
            Comparator.comparingInt(StateTrace::sequence));
        this.transitions = DomainCanonical.sortedCopy(
            transitions,
            Comparator.comparingInt(TransitionTrace::sequence));
        this.candidateAttempts = DomainCanonical.sortedCopy(
            candidateAttempts,
            Comparator.comparingInt(CandidateAttempt::sequence));
        this.resources = DomainCanonical.sortedCopy(
            resources,
            Comparator.comparing(line -> line.resource().name()));
        this.selectedCandidateHash = selectedCandidateHash == null
            ? ""
            : selectedCandidateHash;
        if (!this.selectedCandidateHash.isEmpty()) {
            DomainCanonical.requireSha256(
                this.selectedCandidateHash, "selectedCandidateHash");
        }
        this.certificate = certificate;
        this.domainEvidence = Objects.requireNonNull(domainEvidence, "domainEvidence");
        validateOutcomeConsistency();
        this.contentHash = DomainCanonical.sha256(render(false));
    }

    private void validateOutcomeConsistency() {
        if (outcome == Outcome.CONFIRMED) {
            if (selectedCandidateHash.isEmpty() || certificate == null) {
                throw new IllegalArgumentException(
                    "confirmed evidence requires selected candidate and certificate");
            }
            boolean retained = candidateAttempts.stream().anyMatch(attempt ->
                attempt.candidateHash().equals(selectedCandidateHash)
                    && attempt.disposition() == AttemptDisposition.CONFIRMED
                    && attempt.certificateHash().equals(certificate.contentHash()));
            if (!retained) {
                throw new IllegalArgumentException(
                    "confirmed evidence must retain its selected candidate attempt");
            }
        } else if (!selectedCandidateHash.isEmpty() || certificate != null) {
            throw new IllegalArgumentException(
                "non-confirmed evidence must not select a candidate or certificate");
        }
        long uniqueStates = states.stream().map(StateTrace::stateHash).distinct().count();
        if (uniqueStates != states.size()) {
            throw new IllegalArgumentException("state traces must be unique by stateHash");
        }
        for (ResourceLine line : resources) {
            line.validateBalance();
        }
    }

    public String campaignId() {
        return campaignId;
    }

    public DiscoveryDomainDescriptor descriptor() {
        return descriptor;
    }

    public DiscoverySeed seed() {
        return seed;
    }

    public DiscoveryBudget budget() {
        return budget;
    }

    public Outcome outcome() {
        return outcome;
    }

    public List<StateTrace> states() {
        return states;
    }

    public List<TransitionTrace> transitions() {
        return transitions;
    }

    public List<CandidateAttempt> candidateAttempts() {
        return candidateAttempts;
    }

    public List<ResourceLine> resources() {
        return resources;
    }

    public String selectedCandidateHash() {
        return selectedCandidateHash;
    }

    public RenderedCertificate certificate() {
        return certificate;
    }

    public DomainPayload domainEvidence() {
        return domainEvidence;
    }

    public String contentHash() {
        return contentHash;
    }

    public String proofStatus() {
        return NOT_EVALUATED;
    }

    public String externalNoveltyStatus() {
        return NOT_EVALUATED;
    }

    public String promotionStatus() {
        return NOT_EVALUATED;
    }

    public String publicEvidenceStatus() {
        return NOT_EVALUATED;
    }

    public String toCanonicalJson() {
        return render(true);
    }

    private String render(boolean includeHash) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("campaignId", campaignId)
            .object("domain", descriptor::writeTo)
            .object("seed", this::writeSeed)
            .object("budget", this::writeBudget)
            .property("outcome", outcome.name())
            .object("summary", summary -> summary
                .property("exploredStates", states.size())
                .property("generatedTransitions", transitions.size())
                .property("acceptedTransitions", transitions.stream()
                    .filter(TransitionTrace::accepted).count())
                .property("rejectedTransitions", transitions.stream()
                    .filter(transition -> !transition.accepted()).count())
                .property("candidateAttempts", candidateAttempts.size())
                .property("counterexampleAttempts", candidateAttempts.stream()
                    .mapToInt(CandidateAttempt::counterexampleAttempts).sum()))
            .array("states", array -> states.forEach(state ->
                array.objectValue(object -> writeState(object, state))))
            .array("transitions", array -> transitions.forEach(transition ->
                array.objectValue(object -> writeTransition(object, transition))))
            .array("candidateAttempts", array -> candidateAttempts.forEach(attempt ->
                array.objectValue(object -> writeAttempt(object, attempt))))
            .array("resources", array -> resources.forEach(line ->
                array.objectValue(object -> writeResource(object, line))));
        if (selectedCandidateHash.isEmpty()) {
            json.nullProperty("selectedCandidateHash");
        } else {
            json.property("selectedCandidateHash", selectedCandidateHash);
        }
        if (certificate == null) {
            json.nullProperty("certificate");
        } else {
            json.object("certificate", this::writeCertificate);
        }
        json.object("domainEvidence", this::writeDomainEvidence)
            .property("proofStatus", NOT_EVALUATED)
            .property("externalNoveltyStatus", NOT_EVALUATED)
            .property("promotionStatus", NOT_EVALUATED)
            .property("publicEvidenceStatus", NOT_EVALUATED);
        if (includeHash) {
            json.property("contentHash", contentHash);
        }
        return json.endObject().toString();
    }

    private void writeSeed(JsonWriter json) {
        json.property("schema", seed.schema())
            .property("seedId", seed.seedId())
            .property("domainId", seed.domainId())
            .property("payload", seed.payload())
            .property("sourceReference", seed.sourceReference())
            .property("contentHash", seed.contentHash());
    }

    private void writeBudget(JsonWriter json) {
        json.property("maxDepth", budget.maxDepth())
            .property("maxExploredStates", budget.maxExploredStates())
            .property("maxGeneratedSuccessors", budget.maxGeneratedSuccessors())
            .property("maxCandidatesPerState", budget.maxCandidatesPerState())
            .property("maxCandidateAttempts", budget.maxCandidateAttempts())
            .property("maxCounterexampleAttempts", budget.maxCounterexampleAttempts());
    }

    private static void writeState(JsonWriter json, StateTrace state) {
        json.property("sequence", state.sequence())
            .property("stateHash", state.stateHash())
            .property("canonicalState", state.canonicalState())
            .property("depth", state.depth())
            .property("objectiveScore", state.objectiveScore())
            .property("candidateReady", state.candidateReady());
        if (state.parentStateHash().isEmpty()) {
            json.nullProperty("parentStateHash");
        } else {
            json.property("parentStateHash", state.parentStateHash());
        }
        if (state.actionId().isEmpty()) {
            json.nullProperty("actionId");
        } else {
            json.property("actionId", state.actionId());
        }
        writeMapArray(json, "objectiveMetrics", state.objectiveMetrics());
    }

    private static void writeTransition(JsonWriter json, TransitionTrace transition) {
        json.property("sequence", transition.sequence())
            .property("fromStateHash", transition.fromStateHash())
            .property("toStateHash", transition.toStateHash())
            .property("actionId", transition.actionId())
            .property("cost", transition.cost())
            .property("semanticsPreserving", transition.semanticsPreserving())
            .property("accepted", transition.accepted())
            .stringArray("assumptions", transition.assumptions())
            .stringArray("blockers", transition.blockers());
        writeMapArray(json, "metadata", transition.metadata());
    }

    private static void writeAttempt(JsonWriter json, CandidateAttempt attempt) {
        json.property("sequence", attempt.sequence())
            .property("candidateHash", attempt.candidateHash())
            .property("disposition", attempt.disposition().name())
            .property("counterexampleStatus", attempt.counterexampleStatus().name())
            .property("counterexampleAttempts", attempt.counterexampleAttempts())
            .property("counterexampleWitness", attempt.counterexampleWitness())
            .property("evaluationStatus", attempt.evaluationStatus().name())
            .property("evaluationSummary", attempt.evaluationSummary());
        if (attempt.certificateHash().isEmpty()) {
            json.nullProperty("certificateHash");
        } else {
            json.property("certificateHash", attempt.certificateHash());
        }
        writeMapArray(json, "metrics", attempt.metrics());
    }

    private static void writeResource(JsonWriter json, ResourceLine line) {
        json.property("resource", line.resource().name())
            .property("configured", line.configured())
            .property("executed", line.executed())
            .property("skipped", line.skipped())
            .property("remaining", line.remaining());
    }

    private void writeCertificate(JsonWriter json) {
        json.property("kind", certificate.kind())
            .property("format", certificate.format())
            .property("certificateObjectHash", certificate.certificateObjectHash())
            .property("rendered", certificate.rendered())
            .property("contentHash", certificate.contentHash());
    }

    private void writeDomainEvidence(JsonWriter json) {
        json.property("type", domainEvidence.type());
        writeMapArray(json, "properties", domainEvidence.properties());
    }

    private static void writeMapArray(
        JsonWriter json,
        String property,
        Map<String, String> values
    ) {
        json.array(property, array -> values.forEach((key, value) ->
            array.objectValue(object -> object
                .property("key", key)
                .property("value", value))));
    }

    public enum Outcome {
        CONFIRMED,
        REFUTED,
        INCONCLUSIVE,
        UNSUPPORTED,
        BUDGET_EXHAUSTED,
        INVALID_SEED
    }

    public enum AttemptDisposition {
        CONFIRMED,
        REFUTED_BY_COUNTEREXAMPLE,
        REFUTED_BY_EVALUATOR,
        INCONCLUSIVE,
        UNSUPPORTED
    }

    public enum EvaluationDisposition {
        NOT_RUN,
        CONFIRMED,
        REFUTED,
        INCONCLUSIVE,
        UNSUPPORTED
    }

    public enum Resource {
        EXPLORED_STATES,
        GENERATED_SUCCESSORS,
        CANDIDATE_EVALUATIONS,
        COUNTEREXAMPLE_ATTEMPTS,
        CERTIFICATE_ATTEMPTS
    }

    public record StateTrace(
        int sequence,
        String stateHash,
        String canonicalState,
        int depth,
        int objectiveScore,
        boolean candidateReady,
        String parentStateHash,
        String actionId,
        Map<String, String> objectiveMetrics
    ) {
        public StateTrace {
            if (sequence < 1 || depth < 0) {
                throw new IllegalArgumentException(
                    "state sequence must be positive and depth non-negative");
            }
            DomainCanonical.requireSha256(stateHash, "stateHash");
            DomainCanonical.requireText(canonicalState, "canonicalState");
            parentStateHash = parentStateHash == null ? "" : parentStateHash;
            if (!parentStateHash.isEmpty()) {
                DomainCanonical.requireSha256(parentStateHash, "parentStateHash");
            }
            actionId = actionId == null ? "" : actionId;
            if (!actionId.isEmpty()) {
                DomainCanonical.requireIdentifier(actionId, "actionId");
            }
            objectiveMetrics = DomainCanonical.sortedMap(objectiveMetrics);
        }
    }

    public record TransitionTrace(
        int sequence,
        String fromStateHash,
        String toStateHash,
        String actionId,
        int cost,
        boolean semanticsPreserving,
        List<String> assumptions,
        Map<String, String> metadata,
        boolean accepted,
        List<String> blockers
    ) {
        public TransitionTrace {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                    "transition sequence must be positive");
            }
            DomainCanonical.requireSha256(fromStateHash, "fromStateHash");
            DomainCanonical.requireSha256(toStateHash, "toStateHash");
            DomainCanonical.requireIdentifier(actionId, "actionId");
            assumptions = DomainCanonical.sortedDistinct(assumptions);
            metadata = DomainCanonical.sortedMap(metadata);
            blockers = DomainCanonical.sortedDistinct(blockers);
            if (accepted && !blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "accepted transition must not contain blockers");
            }
            if (!accepted && blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "rejected transition requires a blocker");
            }
        }
    }

    public record CandidateAttempt(
        int sequence,
        String candidateHash,
        AttemptDisposition disposition,
        CounterexampleStatus counterexampleStatus,
        int counterexampleAttempts,
        String counterexampleWitness,
        EvaluationDisposition evaluationStatus,
        String evaluationSummary,
        String certificateHash,
        Map<String, String> metrics
    ) {
        public CandidateAttempt {
            if (sequence < 1 || counterexampleAttempts < 0) {
                throw new IllegalArgumentException(
                    "candidate sequence must be positive and attempts non-negative");
            }
            DomainCanonical.requireSha256(candidateHash, "candidateHash");
            Objects.requireNonNull(disposition, "disposition");
            Objects.requireNonNull(counterexampleStatus, "counterexampleStatus");
            counterexampleWitness = counterexampleWitness == null
                ? ""
                : counterexampleWitness;
            Objects.requireNonNull(evaluationStatus, "evaluationStatus");
            evaluationSummary = evaluationSummary == null ? "" : evaluationSummary;
            certificateHash = certificateHash == null ? "" : certificateHash;
            if (!certificateHash.isEmpty()) {
                DomainCanonical.requireSha256(certificateHash, "certificateHash");
            }
            metrics = DomainCanonical.sortedMap(metrics);
            if (disposition == AttemptDisposition.CONFIRMED
                    && (evaluationStatus != EvaluationDisposition.CONFIRMED
                        || certificateHash.isEmpty())) {
                throw new IllegalArgumentException(
                    "confirmed candidate attempt requires confirmed evaluation and certificate");
            }
        }
    }

    public record ResourceLine(
        Resource resource,
        int configured,
        int executed,
        int skipped,
        int remaining
    ) {
        public ResourceLine {
            Objects.requireNonNull(resource, "resource");
            if (configured < 0 || executed < 0 || skipped < 0 || remaining < 0) {
                throw new IllegalArgumentException(
                    "resource counts must be non-negative");
            }
            validateBalance();
        }

        void validateBalance() {
            if (configured != executed + skipped + remaining) {
                throw new IllegalArgumentException(
                    resource + " resource line is unbalanced");
            }
        }
    }
}
