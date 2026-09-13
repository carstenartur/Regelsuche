package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.VerifiedTransition;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Executes the frozen native profile through the shared exact pipeline. */
public final class PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
        implements PolynomialTheoryUtilityProfileAdapter {
    public static final String PROFILE_ID = "ON_DEMAND_VERIFIED_FACTORIZATION";
    public static final String ADAPTER_ID =
        "regelsuche.polynomial-theory-utility.on-demand-verified-factorization/v1";
    private static final int PRIMITIVE_EXPANSION_LENGTH = 7;
    private final Consumer<Execution> evidence;
    private final boolean observed;

    public PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(Consumer<Execution> evidence) {
        this(evidence, false);
    }

    private PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(Consumer<Execution> evidence, boolean observed) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.observed = observed;
    }

    /** Opts into result v3 without changing the frozen adapter inventory or work scale. */
    public static PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter observed(Consumer<Execution> evidence) {
        return new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(evidence, true);
    }

    @Override
    public String profileId() { return PROFILE_ID; }

    @Override
    public String adapterId() { return ADAPTER_ID; }

    @Override
    public String resultSchema() {
        return observed ? PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA
            : PolynomialTheoryUtilityCandidateResult.SCHEMA;
    }

    @Override
    public Run openRun(RunDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        var expected = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input -> descriptor.runId().equals(input.runId())).toList();
        if (!PROFILE_ID.equals(descriptor.profileId()) || !ADAPTER_ID.equals(descriptor.adapterId())
                || expected.size() != PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()
                || descriptor.expectedCaseCount() != expected.size()
                || !expected.getFirst().checkpointId().equals(descriptor.checkpointId())
                || !expected.getFirst().profileId().equals(PROFILE_ID)) {
            throw new IllegalArgumentException("native run differs from the frozen matrix");
        }
        return new NativeRun(expected);
    }

    private final class NativeRun implements MeasuredRun {
        private final List<PolynomialTheoryUtilityExecutionInput> expected;
        private int next;
        private boolean closed;

        private NativeRun(List<PolynomialTheoryUtilityExecutionInput> expected) {
            this.expected = List.copyOf(expected);
        }

        @Override
        public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
            if (closed || next >= expected.size() || !expected.get(next).equals(input)) {
                throw new IllegalArgumentException("native input is not the next frozen row");
            }
            requireFormation(input, formation);
            try {
                Execution execution = executeCase(input, formation, observed);
                evidence.accept(execution);
                next++;
                return execution.measured();
            } catch (RuntimeException | Error failure) {
                closed = true;
                throw failure;
            }
        }

        @Override
        public void close() {
            if (closed || next != expected.size()) {
                throw new IllegalStateException("native run did not consume each frozen row exactly once");
            }
            closed = true;
        }
    }

    static Execution executeCase(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        return executeCase(input, formation, false);
    }

    private static Execution executeCase(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation, boolean observed) {
        requireFormation(input, formation);
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        List<List<Integer>> paths = paths(formation);
        var observations = new ArrayList<Occurrence>();
        var transitions = new ArrayList<PolynomialTheoryUtilityTransitionOutcome>();
        var traces = new ArrayList<PolynomialTheoryUtilityTransitionTrace>();
        var attempts = new ArrayList<PolynomialTheoryUtilityFactorizationAttempt>();
        var retainedOccurrences = new ArrayList<PolynomialTheoryUtilityExecutionObservations.Occurrence>();
        ExactParsedTerm parsed = parse(formation.sourceExpression(), authority);
        var preparation = authority.ledger();
        for (List<Integer> path : paths) {
            var beforeRaw = authority.ledger();
            int firstAttempt = attempts.size();
            Occurrence occurrence = executeOccurrence(parsed, path, authority);
            observations.add(occurrence);
            var nested = occurrence.pipeline();
            PolynomialTheoryUtilityTransitionOutcome transition = null;
            if (nested != null && occurrence.status().equals("TRANSFORMED")) {
                transition = transition(input, formation, occurrence, transitions.size());
                transitions.add(transition);
                traces.add(trace(transition, nested));
            }
            var attempt = attempt(input, nested, transition, attempts.size(), retainedOccurrences.size(), observed);
            if (attempt != null) attempts.add(attempt);
            if (observed) {
                retainedOccurrences.add(retainOccurrence(retainedOccurrences.size(), occurrence, transition,
                    beforeRaw, authority.ledger(), attempts.subList(firstAttempt, attempts.size())));
            }
        }
        var retained = observed ? PolynomialTheoryUtilityExecutionObservations.create(preparation, retainedOccurrences) : null;
        TerminalStatus terminal = observed ? retained.terminalStatus() : terminal(observations);
        if (!observed && !transitions.isEmpty() && terminal != TerminalStatus.VALIDATED_TRANSITION) {
            throw new UnrepresentableExecution(observations, authority.ledger(), authority.projection());
        }
        String proof = executionHash(authority.projection(), observations);
        String verifier = verifierStatus(terminal, attempts.isEmpty());
        var result = observed ? PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
            "NATIVE_SHARED_CANONICAL_AUTHORITY:" + proof, transitions, verifier, retained)
            : PolynomialTheoryUtilityCandidateResult.create(input, formation, terminal,
                "NATIVE_SHARED_CANONICAL_AUTHORITY:" + proof, authority.work(), transitions, verifier);
        var measured = PolynomialTheoryUtilityMeasuredCandidate.create(result, traces, attempts, List.of());
        return new Execution(measured, authority.ledger(), authority.projection(), observations, proof);
    }

    private static PolynomialTheoryUtilityExecutionObservations.Occurrence retainOccurrence(int index,
            Occurrence occurrence, PolynomialTheoryUtilityTransitionOutcome transition,
            PolynomialWorkLedger before, PolynomialWorkLedger after,
            List<PolynomialTheoryUtilityFactorizationAttempt> attempts) {
        var nested = occurrence.pipeline();
        return new PolynomialTheoryUtilityExecutionObservations.Occurrence(index, occurrence.path(),
            terminal(List.of(occurrence)), occurrence.detailCode(),
            nested == null ? "NONE" : nested.certificateHash(),
            transition == null ? "NONE" : transition.transitionId(), occurrence.work().primitiveWork(),
            PolynomialTheoryUtilityExecutionObservations.difference(after, before),
            attempts.stream().map(PolynomialTheoryUtilityFactorizationAttempt::attemptId).toList(), List.of());
    }

    private static String verifierStatus(TerminalStatus terminal, boolean noAttempts) {
        return switch (terminal) {
            case MIXED_OUTCOMES -> "RETAINED_OCCURRENCE_OUTCOMES";
            case VALIDATED_TRANSITION -> "VERIFIED";
            default -> noAttempts ? "NOT_REQUESTED" : "RETAINED_ATTEMPT_OUTCOMES";
        };
    }

    /** Explicit observed-result entry point using the same frozen row authority. */
    public static Execution executeObservedCase(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        return executeCase(input, formation, true);
    }

    private static ExactParsedTerm parse(String source, PolynomialTheoryUtilityWorkAuthority authority) {
        try {
            authority.consume("projection.study-source-parse-code-units", source.length());
            return new ExpressionParser().parseExactTerm(source);
        } catch (PolynomialWorkAuthority.LimitReached exhausted) {
            return null;
        }
    }

    private static Occurrence executeOccurrence(ExactParsedTerm parsed, List<Integer> path,
            PolynomialTheoryUtilityWorkAuthority authority) {
        var before = authority.work();
        ExactNestedFactorizationTransformationPipeline.Result nested = null;
        try {
            if (parsed == null || authority.remainingPrimitiveWork() < PRIMITIVE_EXPANSION_LENGTH) {
                throw new PolynomialWorkAuthority.LimitReached();
            }
            authority.consume("study.evidence.occurrence-records", 1);
            authority.consume("projection.study-path-navigation", path.size() + 1L);
            var selected = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
            var position = new TreePosition(path, ExpressionFormatter.formatMeasured(selected,
                units -> authority.consume("projection.study-position-format-code-units", units)));
            nested = new ExactNestedFactorizationTransformationPipeline(authority).transform(parsed, position,
                NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
            if (nested.transformed()) {
                var expansion = VerifiedTransition.from(nested.transformation().orElseThrow()).primitiveExpansion();
                if (expansion.size() != PRIMITIVE_EXPANSION_LENGTH) {
                    throw new IllegalStateException("shared primitive expansion changed before study execution");
                }
                var range = nested.projection().orElseThrow().selectedRange().orElseThrow();
                long replacementLength = nested.transformation().orElseThrow()
                    .transformedExpression().orElseThrow().length();
                long outputLength = path.isEmpty() ? replacementLength
                    : parsed.source().length() - (range.endExclusive() - range.startInclusive())
                        + replacementLength + 2L;
                authority.consume(new PolynomialWorkLedger(java.util.Map.of(
                    "nested.rewritten-exact-source-code-units", outputLength,
                    "study.evidence.transition-outcome-records", 1L,
                    "study.evidence.transition-trace-records", 1L,
                    "study.evidence.primitive-step-records", (long) expansion.size())));
                authority.consumePrimitive(expansion.size());
            }
            return new Occurrence(path, nested.status().name(), nested.detailCode(), nested,
                difference(authority.work(), before));
        } catch (PolynomialWorkAuthority.LimitReached exhausted) {
            return new Occurrence(path, "BUDGET_INCONCLUSIVE", exhausted.getMessage(), nested,
                difference(authority.work(), before));
        }
    }

    static PolynomialTheoryUtilityTransitionOutcome transition(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation, Occurrence occurrence, int index) {
        var nested = occurrence.pipeline();
        var primitive = nested.transformation().orElseThrow();
        var range = nested.projection().orElseThrow().selectedRange().orElseThrow();
        String replacement = primitive.transformedExpression().orElseThrow();
        String transformedRoot = occurrence.path().isEmpty() ? replacement
            : formation.sourceExpression().substring(0, range.startInclusive()) + "(" + replacement + ")"
                + formation.sourceExpression().substring(range.endExclusive());
        return PolynomialTheoryUtilityTransitionOutcome.create(index, input.inputId(), occurrence.path(),
            primitive.occurrence().sourceText(), replacement, formation.sourceExpression(), transformedRoot,
            PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID, nested.factorization().orElseThrow().engineId(),
            primitive.occurrence().sourceEvidenceHash(), nested.certificateHash(), CacheDisposition.CACHE_DISABLED,
            "NONE", "NONE", "NONE", occurrence.work());
    }

    static PolynomialTheoryUtilityTransitionTrace trace(PolynomialTheoryUtilityTransitionOutcome transition,
            ExactNestedFactorizationTransformationPipeline.Result nested) {
        var expansion = VerifiedTransition.from(nested.transformation().orElseThrow()).primitiveExpansion();
        var steps = new ArrayList<PrimitiveStep>();
        for (var step : expansion) {
            steps.add(PrimitiveStep.create(transition, steps.size(), 0, step.stageId(), step.evidenceHash()));
        }
        return PolynomialTheoryUtilityTransitionTrace.create(transition, 1, steps, List.of());
    }

    static PolynomialTheoryUtilityFactorizationAttempt attempt(PolynomialTheoryUtilityExecutionInput input,
            ExactNestedFactorizationTransformationPipeline.Result nested,
            PolynomialTheoryUtilityTransitionOutcome transition, int index) {
        var factorization = nested.factorization().orElse(null);
        if (factorization == null || factorization.request().isEmpty()) return null;
        var request = factorization.request().orElseThrow();
        var report = factorization.report().orElseThrow();
        String selected = nested.transformation().map(value -> value.candidateCertificateHash())
            .filter(value -> !value.isEmpty()).orElse("NONE");
        return PolynomialTheoryUtilityFactorizationAttempt.create(index, input.inputId(), factorization.engineId(),
            hash(request.canonicalMaterial()), factorization.certificateHash(), report.candidates().stream()
                .map(value -> value.verificationCertificateHash()).toList(), selected,
            transition == null ? "NONE" : transition.transitionId(),
            selected.equals("NONE") ? report.status().name() : "VERIFIED", report.verificationHash());
    }

    private static PolynomialTheoryUtilityFactorizationAttempt attempt(PolynomialTheoryUtilityExecutionInput input,
            ExactNestedFactorizationTransformationPipeline.Result nested,
            PolynomialTheoryUtilityTransitionOutcome transition, int index, int occurrenceIndex, boolean observed) {
        if (nested == null) return null;
        var retained = attempt(input, nested, transition, index);
        if (!observed || retained == null) return retained;
        return PolynomialTheoryUtilityFactorizationAttempt.createObserved(index, input.inputId(), occurrenceIndex,
            nested, transition == null ? "NONE" : transition.transitionId());
    }

    private static TerminalStatus terminal(List<Occurrence> observations) {
        if (observations.stream().allMatch(value -> value.status().equals("TRANSFORMED"))) {
            return TerminalStatus.VALIDATED_TRANSITION;
        }
        if (observations.stream().anyMatch(value -> List.of("TECHNICAL_FAILURE", "SOURCE_EVIDENCE_MISMATCH",
                "POSITION_NOT_PRESENT", "POSITION_STALE").contains(value.status()))) return TerminalStatus.TECHNICAL_FAILURE;
        if (observations.stream().anyMatch(value -> value.status().equals("BUDGET_INCONCLUSIVE"))) {
            return TerminalStatus.BUDGET_INCONCLUSIVE;
        }
        if (observations.stream().anyMatch(value -> value.status().equals("UNSUPPORTED"))) return TerminalStatus.UNSUPPORTED;
        return TerminalStatus.NO_TRANSITION;
    }

    private static List<List<Integer>> paths(PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        return switch (formation.occurrenceLayout()) {
            case "ROOT" -> List.of(List.of());
            case "NESTED_RIGHT" -> List.of(List.of(1));
            case "TWO_IDENTICAL_SIBLINGS" -> List.of(List.of(0), List.of(1));
            case "FOUR_IDENTICAL_LEAVES" -> List.of(List.of(0, 0), List.of(0, 1), List.of(1, 0), List.of(1, 1));
            default -> throw new IllegalArgumentException("unknown frozen occurrence layout");
        };
    }

    private static void requireFormation(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        if (!PROFILE_ID.equals(input.profileId()) || !ADAPTER_ID.equals(input.adapterId())
                || !input.caseId().equals(formation.caseId())
                || !PolynomialTheoryUtilityExecutionInputs.freeze().inputs().contains(input)
                || !PolynomialTheoryUtilityCaseCorpus.load().cases().contains(formation)) {
            throw new IllegalArgumentException("substituted native input or formation");
        }
    }

    private static PolynomialTheoryUtilityWorkBreakdown difference(PolynomialTheoryUtilityWorkBreakdown a,
            PolynomialTheoryUtilityWorkBreakdown b) {
        return new PolynomialTheoryUtilityWorkBreakdown(a.primitiveWork() - b.primitiveWork(),
            a.matchingWork() - b.matchingWork(), a.sourceValidationWork() - b.sourceValidationWork(),
            a.factorizationWork() - b.factorizationWork(), a.verificationWork() - b.verificationWork(),
            a.renderingWork() - b.renderingWork(), a.reparseWork() - b.reparseWork(),
            a.reconstructionWork() - b.reconstructionWork(), a.occurrenceReplacementWork() - b.occurrenceReplacementWork(),
            0, 0, 0, 0, a.evidenceConstructionWork() - b.evidenceConstructionWork());
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static String executionHash(PolynomialTheoryUtilityCanonicalWorkProjection.Projection projection,
            List<Occurrence> observations) {
        StringBuilder material = new StringBuilder();
        append(material, projection.projectionId());
        observations.forEach(value -> append(material, value.identityMaterial()));
        return hash(material.toString());
    }

    private static String hash(String material) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    public record Occurrence(List<Integer> path, String status, String detailCode,
            ExactNestedFactorizationTransformationPipeline.Result pipeline, PolynomialTheoryUtilityWorkBreakdown work) {
        public Occurrence {
            path = List.copyOf(path);
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detailCode, "detailCode");
            Objects.requireNonNull(work, "work");
        }
        String identityMaterial() {
            StringBuilder material = new StringBuilder();
            append(material, path.toString());
            append(material, status);
            append(material, detailCode);
            append(material, pipeline == null ? "NOT_INVOKED" : pipeline.canonicalMaterial());
            work.appendIdentityMaterial(material);
            return material.toString();
        }
    }

    /** Retains the consumed prefix when the frozen result cannot express it. */
    public static final class UnrepresentableExecution extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final List<Occurrence> occurrences;
        private final PolynomialWorkLedger rawWork;
        private final PolynomialTheoryUtilityCanonicalWorkProjection.Projection projection;

        private UnrepresentableExecution(List<Occurrence> occurrences, PolynomialWorkLedger rawWork,
                PolynomialTheoryUtilityCanonicalWorkProjection.Projection projection) {
            super("FROZEN_RESULT_CONTRACT_CANNOT_RETAIN_PARTIAL_OCCURRENCE_SUCCESS");
            this.occurrences = List.copyOf(occurrences);
            this.rawWork = rawWork;
            this.projection = projection;
        }

        public List<Occurrence> occurrences() { return occurrences; }
        public PolynomialWorkLedger rawWork() { return rawWork; }
        public PolynomialTheoryUtilityCanonicalWorkProjection.Projection projection() { return projection; }
    }

    public record Execution(PolynomialTheoryUtilityMeasuredCandidate measured, PolynomialWorkLedger rawWork,
            PolynomialTheoryUtilityCanonicalWorkProjection.Projection projection,
            List<Occurrence> occurrences, String evidenceHash) {
        public Execution {
            Objects.requireNonNull(measured, "measured");
            Objects.requireNonNull(rawWork, "rawWork");
            Objects.requireNonNull(projection, "projection");
            occurrences = List.copyOf(occurrences);
            var result = measured.result();
            var expected = PolynomialTheoryUtilityCanonicalWorkProjection.project(result.input(),
                PolynomialTheoryUtilityCanonicalWorkProjection.partition(result.work().primitiveWork(), rawWork));
            if (!projection.equals(expected) || !result.work().equals(projection.work())
                    || !executionHash(projection, occurrences).equals(evidenceHash)
                    || !result.detailCode().equals("NATIVE_SHARED_CANONICAL_AUTHORITY:" + evidenceHash)) {
                throw new IllegalArgumentException("native execution evidence differs from its measured result");
            }
            var required = new java.util.LinkedHashMap<String, Long>();
            for (var occurrence : occurrences) {
                if (occurrence.pipeline() != null) {
                    occurrence.pipeline().totalWork().stages().forEach((stage, units) ->
                        required.merge(stage, units, Math::addExact));
                }
            }
            if (required.entrySet().stream().anyMatch(entry -> rawWork.units(entry.getKey()) < entry.getValue())) {
                throw new IllegalArgumentException("native execution omitted consumed pipeline work");
            }
            if (result.observations() != null) {
                var retained = result.observations();
                if (!retained.rawWork().totalMechanicalWork().equals(rawWork)
                        || retained.occurrences().size() != occurrences.size()) {
                    throw new IllegalArgumentException("native observed result differs from its raw execution");
                }
                var cumulative = retained.preparationWork();
                long primitive = 0;
                for (int index = 0; index < occurrences.size(); index++) {
                    var actual = occurrences.get(index);
                    var recorded = retained.occurrences().get(index);
                    var before = PolynomialTheoryUtilityCanonicalWorkProjection.project(result.input(),
                        PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitive, cumulative)).work();
                    cumulative = PolynomialTheoryUtilityExecutionObservations.plus(cumulative, recorded.rawWork());
                    primitive = Math.addExact(primitive, recorded.primitiveWork());
                    var after = PolynomialTheoryUtilityCanonicalWorkProjection.project(result.input(),
                        PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitive, cumulative)).work();
                    if (!PolynomialTheoryUtilityExecutionObservations.difference(after, before).equals(actual.work())) {
                        throw new IllegalArgumentException("native occurrence work differs from its consumed prefix");
                    }
                    var pipeline = actual.pipeline();
                    if (!actual.path().equals(recorded.path())
                            || terminal(List.of(actual)) != recorded.terminalStatus()
                            || !actual.detailCode().equals(recorded.detailCode())
                            || !Objects.equals(pipeline == null ? "NONE" : pipeline.certificateHash(),
                                recorded.pipelineEvidenceHash())
                            || (pipeline != null && pipeline.totalWork().stages().entrySet().stream()
                                .anyMatch(entry -> recorded.rawWork().units(entry.getKey()) < entry.getValue()))) {
                        throw new IllegalArgumentException("native occurrence omitted its actual outcome or pipeline work");
                    }
                }
            }
        }
    }
}
