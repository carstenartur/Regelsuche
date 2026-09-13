package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.*;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.*;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.*;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.*;
import de.regelsuche.search.telemetry.*;
import de.regelsuche.transform.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native target-free execution evidence. Deserialization produces observations, never executable transformations. */
public final class TargetFreeSearchExecution {
    public static final String SCHEMA = "regelsuche.target-free-search-execution/v1";
    public static final int MAX_BYTES = 1_048_576;
    public static final String CLAIM_BOUNDARY = "Observed native target-free search, generation occurrences and retained execution paths; "
        + "not complete closure, formal proof, recognition, novelty, salience qualification or search superiority.";
    public static final String RECORDING_OVERHEAD = "Occurrence observation, telemetry, serialization and persistence add work; "
        + "runtime cost is not measured and is not included in generated-candidate or primitive-path counters.";
    private static final String STRATEGY = BestFirstSearchStrategy.class.getName();
    private static final String PROFILE = "CORE_WITH_OBSERVED_OCCURRENCES/v1";
    private static final int MAX_AST_INCREASE = 12;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private TargetFreeSearchExecution() { }

    static RunResult run(String source, SearchHeuristic heuristic, String repositoryRevision) {
        Objects.requireNonNull(source, "source");
        requireAdmission(source, heuristic);
        var input = RepresentationDiscoveryRunInput.expression(source, List.of());
        source = input.displayText();
        var boundary = RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
            RepresentationDiscoveryInformationBoundary.Track.R1_TARGET_FREE_COMPRESSION, KnowledgePackSelection.CORE);
        var engine = new AstRewriteTransformationEngine(boundary.candidateFormationRules(), MAX_AST_INCREASE, heuristic.maxCandidatesPerState());
        var generations = new ArrayList<Generation>();
        var byTransformation = new IdentityHashMap<Transformation, Integer>();
        var events = new ArrayList<Event>();
        int[] calls = {0};
        TransformationEngine observed = expression -> {
            int call = calls[0]++;
            return engine.transformWithOccurrences(expression, generated -> {
                int sequence = generations.size();
                generations.add(Generation.capture(sequence, call, generated));
                byTransformation.put(generated.transformation(), sequence);
            });
        };
        var problem = new SearchProblem(source, observed, new ExpressionScorer(), new ExpressionCanonicalizer(), heuristic)
            .withObserver(event -> events.add(Event.capture(event)));
        var result = new BestFirstSearchStrategy().searchWithDiagnostics(problem);
        var states = new ArrayList<State>();
        var stateByPath = new HashMap<List<Transformation>, String>();
        for (var state : result.states()) {
            String observation = SearchStateReplay.toCanonicalJson(state);
            var sequences = state.transformations().stream().map(step -> Objects.requireNonNull(byTransformation.get(step), "unobserved transformation")).toList();
            var retained = new State(states.size(), sha256(observation), observation, sequences, state.recordedExecution().orElseThrow().contentHash());
            states.add(retained); stateByPath.put(state.transformations(), retained.stateId());
        }
        var transitions = new ArrayList<Transition>();
        for (int i = 0; i < states.size(); i++) {
            var state = result.states().get(i);
            if (state.depth() == 0) continue;
            String parent = Objects.requireNonNull(stateByPath.get(state.transformations().subList(0, state.depth() - 1)), "missing explored parent");
            transitions.add(new Transition(transitions.size() + 1, parent, states.get(i).stateId(), states.get(i).generationSequences().getLast()));
        }
        var plan = RepresentationDiscoveryRunPlan.create(boundary.track(), boundary.contentHash(), boundary.candidateFormationRuleInventoryHash(),
            boundary.candidateFormationSelectionCommitment(), boundary.postFreezeCatalogCommitment(), STRATEGY, PROFILE,
            "UNTARGETED_NATIVE_PRIORITY", sha256(canonical(heuristic)), 0, List.of(AstRewriteTransformationEngine.class.getName()));
        var work = new Work(calls[0], generations.size(), generations.stream().map(Generation::work).reduce(ExecutionWork.ZERO, ExecutionWork::plus));
        var content = new Content(SCHEMA, input, plan, heuristic,
            RepresentationDiscoveryRevisionEvidence.create(repositoryRevision, SCHEMA), result.status(), result.bestDistance(),
            result.bestState() == null ? "" : sha256(SearchStateReplay.toCanonicalJson(result.bestState())), result.metrics(),
            states, transitions, generations, events, work, "NOT_EVALUATED", RECORDING_OVERHEAD, CLAIM_BOUNDARY);
        var artifact = new Artifact(content, sha256(canonical(content)));
        if (artifact.toCanonicalJson().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("observed execution exceeds the bounded dossier storage admission");
        }
        return new RunResult(result, artifact, workspace(artifact));
    }

    public static final class RunResult {
        private final GoalSearchResult searchResult;
        private final Artifact artifact;
        private final RepresentationDiscoveryRunWorkspace workspace;
        private RunResult(GoalSearchResult searchResult, Artifact artifact, RepresentationDiscoveryRunWorkspace workspace) {
            this.searchResult = Objects.requireNonNull(searchResult);
            this.artifact = Objects.requireNonNull(artifact);
            this.workspace = Objects.requireNonNull(workspace);
            requireBound(workspace, artifact);
        }
        public GoalSearchResult searchResult() { return searchResult; }
        public Artifact artifact() { return artifact; }
        public RepresentationDiscoveryRunWorkspace workspace() { return workspace; }
    }

    public record Artifact(Content content, String contentHash) {
        public Artifact {
            Objects.requireNonNull(content, "content");
            content.validate();
            if (!sha256(canonical(content)).equals(contentHash)) throw new IllegalArgumentException("target-free execution hash mismatch");
        }
        public String toCanonicalJson() { return canonical(this); }
        /** Explicit additional execution. Stored bytes cannot supply the independent comparison result. */
        public RunResult replay() {
            var replayed = run(content.input().displayText(), content.heuristic(), content.revisions().repositoryCommit());
            requireReplay(replayed.artifact());
            return replayed;
        }
        private void requireReplay(Artifact independentlyExecuted) {
            if (!toCanonicalJson().equals(Objects.requireNonNull(independentlyExecuted).toCanonicalJson())) {
                throw new IllegalArgumentException("native execution replay differs in trace, occurrences, paths, work or configuration");
            }
        }
    }

    public static Artifact fromCanonicalBytes(RepresentationDiscoveryRunWorkspace workspace, byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("target-free execution exceeds 1 MiB");
        try {
            var artifact = JSON.readValue(bytes, Artifact.class);
            if (artifact == null || (!Arrays.equals(bytes, artifact.toCanonicalJson().getBytes(StandardCharsets.UTF_8))
                    && !Arrays.equals(bytes, (artifact.toCanonicalJson() + "\n").getBytes(StandardCharsets.UTF_8)))) {
                throw new IllegalArgumentException("target-free execution must be canonical UTF-8 JSON");
            }
            requireBound(workspace, artifact);
            return artifact;
        } catch (IOException exception) { throw new IllegalArgumentException("invalid target-free execution artifact", exception); }
    }

    private static void requireBound(RepresentationDiscoveryRunWorkspace supplied, Artifact artifact) {
        if (!workspace(artifact).equals(supplied)) throw new IllegalArgumentException("native execution belongs to another workspace");
    }

    private static RepresentationDiscoveryRunWorkspace workspace(Artifact artifact) {
        var c = artifact.content();
        var roles = Arrays.stream(RepresentationDiscoveryArtifactReference.ArtifactRole.values()).map(role ->
            Set.of(SEARCH_GRAPH, PROGRESS_LEDGER, REPRESENTATION_CANDIDATES, CANDIDATE_DOSSIERS, PATH_REPLAY).contains(role)
                ? RepresentationDiscoveryArtifactReference.available(role, SCHEMA, artifact.contentHash())
                : RepresentationDiscoveryArtifactReference.notProduced(role)).toList();
        return RepresentationDiscoveryRunWorkspace.create(c.input(), c.plan(), RepresentationDiscoveryRunOutcome.create(
            RepresentationDiscoveryRunOutcome.TerminalState.COMPLETED, "UNTARGETED_NATIVE_EXECUTION_RETURNED",
            (long) c.heuristic().maxVisitedExpressions() * c.heuristic().maxCandidatesPerState(), c.work().generatedTransformations(),
            artifact.contentHash(), sha256(RECORDING_OVERHEAD)), roles, c.revisions());
    }

    public record Content(String schema, RepresentationDiscoveryRunInput input, RepresentationDiscoveryRunPlan plan,
            SearchHeuristic heuristic, RepresentationDiscoveryRevisionEvidence revisions, GoalStatus goalStatus, int bestDistance,
            String bestStateId, GoalMetrics metrics, List<State> states, List<Transition> transitions, List<Generation> generations,
            List<Event> events, Work work, String stageAssessment, String recordingOverhead, String claimBoundary) {
        public Content {
            states = List.copyOf(states); transitions = List.copyOf(transitions); generations = List.copyOf(generations); events = List.copyOf(events);
        }
        private void validate() {
            if (!SCHEMA.equals(schema) || !CLAIM_BOUNDARY.equals(claimBoundary) || !RECORDING_OVERHEAD.equals(recordingOverhead)
                    || !"NOT_EVALUATED".equals(stageAssessment) || goalStatus != GoalStatus.UNTARGETED || bestDistance != -1
                    || !STRATEGY.equals(plan.searchStrategyId()) || !PROFILE.equals(plan.searchProfileId())
                    || plan.informationTrack() != RepresentationDiscoveryInformationBoundary.Track.R1_TARGET_FREE_COMPRESSION
                    || !"UNTARGETED_NATIVE_PRIORITY".equals(plan.objectiveId()) || plan.deterministicSeed() != 0
                    || !plan.backendIdentities().equals(List.of(AstRewriteTransformationEngine.class.getName())) || !input.assumptions().isEmpty()
                    || !sha256(canonical(heuristic)).equals(plan.budgetHash()) || !SCHEMA.equals(revisions.applicationRevision())) {
                throw new IllegalArgumentException("unsupported native execution contract");
            }
            requireAdmission(input.displayText(), heuristic);
            if (states.isEmpty() || states.size() > heuristic.maxVisitedExpressions() || transitions.size() != states.size() - 1
                    || events.size() < 2 || events.getFirst().type() != SearchEventType.SEARCH_STARTED
                    || events.getLast().type() != SearchEventType.SEARCH_FINISHED || metrics.exploredStates() != states.size()
                    || metrics.expandedStates() != work.engineCalls() || metrics.generatedTransformations() != generations.size()
                    || work.generatedTransformations() != generations.size()
                    || generations.size() > (long) heuristic.maxVisitedExpressions() * heuristic.maxCandidatesPerState()) {
                throw new IllegalArgumentException("native execution counters or trace membership differ");
            }
            for (JsonNode value : JSON.valueToTree(metrics)) if (!value.isIntegralNumber() || value.longValue() < 0) {
                throw new IllegalArgumentException("invalid native search metrics");
            }
            var ids = new HashSet<String>();
            var byPath = new HashMap<List<Integer>, String>();
            for (int i = 0; i < generations.size(); i++) {
                var generation = generations.get(i);
                if (generation.sequence() != i || generation.engineCall() < 0 || generation.engineCall() >= work.engineCalls()) {
                    throw new IllegalArgumentException("invalid generation identity");
                }
                generation.validate();
            }
            if (!generations.stream().map(Generation::work).reduce(ExecutionWork.ZERO, ExecutionWork::plus).equals(work.returnedCandidateWork())) {
                throw new IllegalArgumentException("generated candidate work differs");
            }
            for (int i = 0; i < states.size(); i++) {
                var state = states.get(i);
                if (state.sequence() != i || !ids.add(state.stateId())) throw new IllegalArgumentException("invalid state identity");
                state.validate(input.displayText(), generations);
                if (i == 0 && !state.generationSequences().isEmpty()) throw new IllegalArgumentException("first state must be root");
                if (i > 0) {
                    var edge = transitions.get(i - 1);
                    var path = state.generationSequences();
                    if (path.isEmpty() || edge.sequence() != i || !edge.toStateId().equals(state.stateId())
                            || edge.generationSequence() != path.getLast()
                            || !Objects.equals(edge.fromStateId(), byPath.get(path.subList(0, path.size() - 1)))) {
                        throw new IllegalArgumentException("explored transition differs from the retained execution path");
                    }
                }
                byPath.put(state.generationSequences(), state.stateId());
            }
            if (!ids.contains(bestStateId)) throw new IllegalArgumentException("best state is not retained");
            for (int i = 0; i < events.size(); i++) {
                var event = events.get(i);
                if (event.sequence() != i) throw new IllegalArgumentException("native event sequence differs");
                var execution = RecordedExecution.fromCanonicalJson(event.execution());
                if (!execution.transformedExpression().equals(event.expression())) throw new IllegalArgumentException("event output differs from execution");
                if (event.type() == SearchEventType.TRANSFORMATION_GENERATED) {
                    if (!execution.sourceExpression().equals(event.parentExpression()) || generations.stream().noneMatch(g -> g.execution().equals(event.execution()))) {
                        throw new IllegalArgumentException("native generation event lacks its actual engine occurrence");
                    }
                } else if (!execution.sourceExpression().equals(input.displayText())) throw new IllegalArgumentException("event execution belongs to another source");
            }
            if (events.stream().filter(e -> e.type() == SearchEventType.STATE_VISITED).count() != states.size()
                    || events.stream().filter(e -> e.type() == SearchEventType.STATE_EXPANDED).count() != work.engineCalls()
                    || events.stream().filter(e -> e.type() == SearchEventType.STATE_ENQUEUED).count() != metrics.enqueuedStates()) {
                throw new IllegalArgumentException("native event accounting differs from result metrics");
            }
        }
    }

    public record State(int sequence, String stateId, String canonicalStateJson, List<Integer> generationSequences, String executionHash) {
        public State { generationSequences = List.copyOf(generationSequences); }
        private void validate(String source, List<Generation> generations) {
            var observation = tree(canonicalStateJson);
            if (!sha256(canonicalStateJson).equals(stateId) || !SearchStateReplay.SCHEMA.equals(observation.path("schema").asText())
                    || !observation.path("executionRetained").asBoolean() || observation.path("depth").asInt(-1) != generationSequences.size()) {
                throw new IllegalArgumentException("invalid native state observation");
            }
            var execution = RecordedExecution.fromCanonicalJson(observation.required("execution").asText());
            execution.requireEndpoints(source, observation.required("expression").asText());
            if (!execution.contentHash().equals(executionHash) || execution.edgeCount() != generationSequences.size()) {
                throw new IllegalArgumentException("state execution binding differs");
            }
            var path = new ArrayList<String>(); path.add(source);
            var rules = new ArrayList<String>(); var applications = new TreeSet<String>();
            var steps = execution.edgeCount() == 0 ? JSON.createArrayNode() : tree(execution.provenance()).required("steps");
            for (int i = 0; i < generationSequences.size(); i++) {
                int index = generationSequences.get(i);
                if (index < 0 || index >= generations.size()) throw new IllegalArgumentException("unknown generation in path");
                var generation = generations.get(index);
                var incoming = RecordedExecution.fromCanonicalJson(generation.execution());
                if (!incoming.sourceExpression().equals(path.getLast()) || !steps.get(i).equals(tree(incoming.provenance()).required("steps").get(0))) {
                    throw new IllegalArgumentException("state path substituted a generation or occurrence");
                }
                path.add(generation.transformedExpression()); rules.add(generation.ruleId()); applications.add(generation.applicationKey());
            }
            if (!JSON.valueToTree(path).equals(observation.required("path")) || !JSON.valueToTree(rules).equals(observation.required("appliedRuleIds"))
                    || !JSON.valueToTree(applications).equals(observation.required("applicationKeys"))
                    || !textList(observation.required("assumptions")).containsAll(execution.assumptions())) {
                throw new IllegalArgumentException("native state path, identity set or assumptions differ");
            }
        }
    }
    /** Only transitions to actually visited states; engine generations and enqueues remain separate evidence. */
    public record Transition(int sequence, String fromStateId, String toStateId, int generationSequence) { }

    public record Generation(int sequence, int engineCall, String sourceExpression, List<Integer> occurrencePath,
            String sourceOccurrenceExpression, String transformedOccurrenceExpression, String transformedExpression,
            String ruleId, String applicationKey, String execution, String executionHash, ExecutionWork work) {
        public Generation { occurrencePath = List.copyOf(occurrencePath); }
        private static Generation capture(int sequence, int call, AstRewriteTransformationEngine.GeneratedTransformation generated) {
            var step = generated.transformation();
            var execution = RecordedExecution.capture(generated.sourceExpression(), List.of(step));
            return new Generation(sequence, call, generated.sourceExpression(), generated.position().path(), generated.sourceOccurrenceExpression(),
                generated.transformedOccurrenceExpression(), step.transformedExpression(), step.rule(), step.applicationKey(),
                execution.toCanonicalJson(), execution.contentHash(), execution.work());
        }
        private void validate() {
            var retained = RecordedExecution.fromCanonicalJson(execution);
            retained.requireEndpoints(sourceExpression, transformedExpression);
            if (retained.edgeCount() != 1 || !retained.contentHash().equals(executionHash) || !retained.work().equals(work)) {
                throw new IllegalArgumentException("generation execution root or work differs");
            }
            var step = tree(retained.provenance()).required("steps").get(0);
            if (!step.path("rule").asText().equals(ruleId) || !step.path("applicationKey").asText().equals(applicationKey)) {
                throw new IllegalArgumentException("generation application identity differs");
            }
            var parser = new ExpressionParser();
            var root = parser.parse(new InputRequest(InputType.TERM, sourceExpression)).terms().getFirst();
            var replacement = parser.parse(new InputRequest(InputType.TERM, transformedOccurrenceExpression)).terms().getFirst();
            var position = new TreePosition(occurrencePath, sourceOccurrenceExpression);
            if (!position.subtreeAt(root).map(ExpressionFormatter::format).filter(sourceOccurrenceExpression::equals).isPresent()
                    || !position.replaceAt(root, replacement).rewrittenRoot().map(ExpressionFormatter::format).filter(transformedExpression::equals).isPresent()) {
                throw new IllegalArgumentException("generation source occurrence does not produce its recorded output");
            }
        }
    }

    public record Event(long sequence, SearchEventType type, String expression, String canonicalHash, int depth, int score,
            String parentCanonicalHash, String parentExpression, String ruleId, RewriteKind rewriteKind, boolean mayIncreaseComplexity,
            int estimatedCostDelta, boolean equivalencePreservingByConstruction, List<String> assumptions, int frontierSize,
            int visitedCount, int generatedCount, String pruningReason, String execution) {
        public Event { assumptions = List.copyOf(assumptions); }
        private static Event capture(SearchEvent e) {
            return new Event(e.sequence(), e.type(), e.expression(), e.canonicalHash(), e.depth(), e.score(), e.parentCanonicalHash(),
                e.parentExpression(), e.ruleId(), e.rewriteKind(), e.mayIncreaseComplexity(), e.estimatedCostDelta(),
                e.equivalencePreservingByConstruction(), e.assumptions(), e.frontierSize(), e.visitedCount(), e.generatedCount(),
                e.pruningReason(), Objects.requireNonNull(e.execution(), "native event lacks retained execution").toCanonicalJson());
        }
    }
    /** Returned engine candidates, counted once per generation; never sums shared prefixes of retained state paths. */
    public record Work(int engineCalls, int generatedTransformations, ExecutionWork returnedCandidateWork) { }

    private static void requireAdmission(String source, SearchHeuristic h) {
        Objects.requireNonNull(h, "heuristic");
        if (source.isBlank() || source.length() > 2048 || h.maxDepth() > 8 || h.maxVisitedExpressions() > 64
                || h.maxCandidatesPerState() > 32 || h.maxExpandingSteps() > 8 || h.beamWidth() > 64) {
            throw new IllegalArgumentException("source or heuristic exceeds bounded native trace admission");
        }
    }
    private static List<String> textList(JsonNode value) {
        if (!value.isArray()) throw new IllegalArgumentException("expected string array");
        var result = new ArrayList<String>();
        for (var item : value) { if (!item.isTextual()) throw new IllegalArgumentException("expected string"); result.add(item.textValue()); }
        return List.copyOf(result);
    }
    private static JsonNode tree(String value) {
        try { var node = JSON.readTree(value); if (node == null || !node.isObject()) throw new IllegalArgumentException("expected JSON object"); return node; }
        catch (IOException exception) { throw new IllegalArgumentException("invalid native observation JSON", exception); }
    }
    private static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalArgumentException("unable to render native execution", exception); }
    }
}
