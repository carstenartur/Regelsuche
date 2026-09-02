package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.Run;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import de.regelsuche.math.algorithms.polynomial
    .NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial
    .NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.polynomial
    .ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Target-blind native adapter for the frozen on-demand factorization profile.
 *
 * <p>The adapter reuses the exact parser, the preregistered native rational
 * engine, the common verifier and the verifier-bound nested transformation
 * pipeline. It does not consult qualification data, use SymPy or select a
 * hidden best candidate.</p>
 */
public final class
        PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
        implements PolynomialTheoryUtilityProfileAdapter {
    public static final String PROFILE_ID =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    public static final String ADAPTER_ID =
        "regelsuche.polynomial-theory-utility."
            + "on-demand-verified-factorization/v1";

    private static final PolynomialTheoryUtilityOnDemandAdmissionPolicy.Policy
        ADMISSION_POLICY =
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze();

    @Override
    public String profileId() {
        return PROFILE_ID;
    }

    @Override
    public String adapterId() {
        return ADAPTER_ID;
    }

    @Override
    public Run openRun(RunDescriptor descriptor) {
        return new NativeRun(requireDescriptor(descriptor));
    }

    private static RunDescriptor requireDescriptor(RunDescriptor descriptor) {
        var value = Objects.requireNonNull(descriptor, "descriptor");
        var profile = profile();
        if (!PROFILE_ID.equals(value.profileId())
                || !ADAPTER_ID.equals(value.adapterId())
                || value.expectedCaseCount()
                    != PolynomialTheoryUtilityCaseCorpus
                        .ORDERED_CASE_IDS.size()) {
            throw new IllegalArgumentException(
                "on-demand run differs from the frozen profile"
            );
        }
        var checkpoint =
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                .filter(candidate -> candidate.checkpointId().equals(
                    value.checkpointId()
                ))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "on-demand checkpoint is not frozen"
                ));
        String expectedRun = PolynomialTheoryUtilityExecutionIdentity.runId(
            profile,
            checkpoint
        );
        if (!expectedRun.equals(value.runId())) {
            throw new IllegalArgumentException(
                "on-demand run identity differs from the frozen matrix"
            );
        }
        return value;
    }

    private static PolynomialTheoryUtilityExecutionProfile profile() {
        return PolynomialTheoryUtilityExecutionInputs.profile(PROFILE_ID);
    }

    private static final class NativeRun implements MeasuredRun {
        private final RunDescriptor descriptor;
        private final List<PolynomialTheoryUtilityExecutionInput> expected;
        private int nextCase;
        private boolean closed;

        private NativeRun(RunDescriptor descriptor) {
            this.descriptor = descriptor;
            expected = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
                .stream()
                .filter(value -> descriptor.runId().equals(value.runId()))
                .toList();
            if (expected.size() != descriptor.expectedCaseCount()) {
                throw new IllegalStateException(
                    "on-demand run input count differs from its freeze"
                );
            }
        }

        @Override
        public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            requireNext(input, formationCase);
            return executeCase(input, formationCase);
        }

        private void requireNext(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(formationCase, "formationCase");
            if (closed || nextCase >= expected.size()) {
                throw new IllegalStateException(
                    "on-demand run cannot accept another input"
                );
            }
            var frozen = expected.get(nextCase);
            if (!frozen.equals(input)
                    || !input.caseId().equals(formationCase.caseId())
                    || !input.profileId().equals(descriptor.profileId())
                    || !input.adapterId().equals(descriptor.adapterId())) {
                throw new IllegalArgumentException(
                    "on-demand input differs from its frozen position"
                );
            }
            nextCase++;
        }

        @Override
        public void close() {
            if (closed || nextCase != expected.size()) {
                throw new IllegalStateException(
                    "on-demand run closed outside its complete boundary"
                );
            }
            closed = true;
        }
    }

    static PolynomialTheoryUtilityMeasuredCandidate executeCase(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(formationCase, "formationCase");
        if (!PROFILE_ID.equals(input.profileId())
                || !ADAPTER_ID.equals(input.adapterId())
                || !input.caseId().equals(formationCase.caseId())) {
            throw new IllegalArgumentException(
                "on-demand case differs from its frozen execution input"
            );
        }

        var plan = PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
            input,
            formationCase
        );
        if (!ADMISSION_POLICY.admits(plan)) {
            return budgetBeforeExecution(input, formationCase);
        }

        ExactParsedTerm source;
        try {
            source = new ExpressionParser().parseExactTerm(
                formationCase.sourceExpression()
            );
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "frozen formation expression no longer parses exactly",
                exception
            );
        }

        var transitions =
            new ArrayList<PolynomialTheoryUtilityTransitionOutcome>();
        var traces = new ArrayList<PolynomialTheoryUtilityTransitionTrace>();
        var attempts =
            new ArrayList<PolynomialTheoryUtilityFactorizationAttempt>();
        var statuses =
            new ArrayList<ExactNestedFactorizationTransformationPipeline.Status>();
        var aggregateWork = PolynomialTheoryUtilityWorkBreakdown.zero();

        for (var occurrence : plan.occurrences()) {
            var engine = NativeUnivariateFactorizationEngine.rationals(
                NativeUnivariateFactorizationPolicy.boundedDefaults()
                    .withMaxEngineWorkUnits(
                        occurrence.factorizationWork()
                    )
            );
            if (!profile().engineId().equals(engine.engineId())) {
                throw new IllegalStateException(
                    "native engine differs from the frozen profile"
                );
            }

            TreePosition position = livePosition(source, occurrence);
            var nested =
                new ExactNestedFactorizationTransformationPipeline()
                    .transform(source, position, engine, 0);
            statuses.add(nested.status());

            List<PrimitiveDescriptor> primitive = nested.transformed()
                ? primitiveExpansion(input, occurrence, nested)
                : List.of();
            var occurrenceWork =
                PolynomialTheoryUtilityRawWorkPartitioner.project(
                    occurrenceAuthority(input, occurrence),
                    primitive.size(),
                    measuredRawWork(nested, primitive.size())
                ).work();
            aggregateWork = aggregateWork.plus(occurrenceWork);

            PolynomialTheoryUtilityTransitionOutcome transition = null;
            if (nested.transformed()) {
                transition = transition(
                    input,
                    formationCase,
                    occurrence,
                    position,
                    nested,
                    occurrenceWork,
                    transitions.size()
                );
                transitions.add(transition);
                traces.add(trace(transition, primitive));
            }

            var attempt = factorizationAttempt(
                input,
                nested,
                transition,
                attempts.size()
            );
            if (attempt != null) {
                attempts.add(attempt);
            }
        }

        TerminalStatus terminal = terminalStatus(transitions, statuses);
        String detail = detailCode(terminal, transitions, statuses);
        String verifier = verifierOutcome(terminal, attempts);
        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            formationCase,
            terminal,
            detail,
            aggregateWork,
            transitions,
            verifier
        );
        return PolynomialTheoryUtilityMeasuredCandidate.create(
            result,
            traces,
            attempts,
            List.of()
        );
    }

    private static PolynomialTheoryUtilityMeasuredCandidate
            budgetBeforeExecution(
                PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
            ) {
        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            formationCase,
            TerminalStatus.BUDGET_INCONCLUSIVE,
            withAdmissionPolicy(
                "FROZEN_AUTHORITY_BELOW_NATIVE_EXECUTION_ADMISSION"
            ),
            PolynomialTheoryUtilityWorkBreakdown.zero(),
            List.of(),
            "NOT_REQUESTED"
        );
        return PolynomialTheoryUtilityMeasuredCandidate.withoutObservations(
            result
        );
    }

    private static TreePosition livePosition(
        ExactParsedTerm source,
        PolynomialTheoryUtilityOnDemandOccurrencePlan.Occurrence occurrence
    ) {
        var provisional = new TreePosition(occurrence.path(), "pending");
        var selected = provisional.subtreeAt(source.expression()).orElseThrow(
            () -> new IllegalStateException(
                "frozen occurrence path is absent from its formation AST"
            )
        );
        return new TreePosition(
            occurrence.path(),
            ExpressionFormatter.format(selected)
        );
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase,
        PolynomialTheoryUtilityOnDemandOccurrencePlan.Occurrence occurrence,
        TreePosition position,
        ExactNestedFactorizationTransformationPipeline.Result nested,
        PolynomialTheoryUtilityWorkBreakdown work,
        int transitionIndex
    ) {
        var transformation = nested.transformation().orElseThrow();
        String transformedRoot = ExpressionFormatter.format(
            nested.rewrittenRoot().orElseThrow()
        );
        String sourceOccurrence = occurrence.path().isEmpty()
            ? formationCase.sourceExpression()
            : position.text();
        String transformedOccurrence = occurrence.path().isEmpty()
            ? transformedRoot
            : transformation.transformedExpression().orElseThrow();
        return PolynomialTheoryUtilityTransitionOutcome.create(
            transitionIndex,
            input.inputId(),
            occurrence.path(),
            sourceOccurrence,
            transformedOccurrence,
            formationCase.sourceExpression(),
            transformedRoot,
            profile().transformationId(),
            profile().engineId(),
            transformation.occurrence().sourceEvidenceHash(),
            nested.certificateHash(),
            CacheDisposition.CACHE_DISABLED,
            PolynomialTheoryUtilityTransitionOutcome.NO_CACHE_LINEAGE,
            PolynomialTheoryUtilityTransitionOutcome.NO_CACHE_LINEAGE,
            PolynomialTheoryUtilityTransitionOutcome.NO_CACHE_LINEAGE,
            work
        );
    }

    private static PolynomialTheoryUtilityTransitionTrace trace(
        PolynomialTheoryUtilityTransitionOutcome transition,
        List<PrimitiveDescriptor> descriptors
    ) {
        List<PrimitiveStep> steps = new ArrayList<>(descriptors.size());
        for (int index = 0; index < descriptors.size(); index++) {
            var descriptor = descriptors.get(index);
            steps.add(PrimitiveStep.create(
                transition,
                index,
                descriptor.pathEdgeIndex(),
                descriptor.ruleId(),
                descriptor.evidenceHash()
            ));
        }
        return PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            transition.occurrencePath().size() + 1,
            steps,
            List.of()
        );
    }

    private static PolynomialTheoryUtilityFactorizationAttempt
            factorizationAttempt(
                PolynomialTheoryUtilityExecutionInput input,
                ExactNestedFactorizationTransformationPipeline.Result nested,
                PolynomialTheoryUtilityTransitionOutcome transition,
                int attemptIndex
            ) {
        var factorization = nested.factorization().orElse(null);
        if (factorization == null
                || factorization.request().isEmpty()
                || factorization.report().isEmpty()) {
            return null;
        }
        var request = factorization.request().orElseThrow();
        var report = factorization.report().orElseThrow();
        List<String> candidates = report.candidates().stream()
            .map(value -> value.verificationCertificateHash())
            .toList();
        String selected = nested.transformation()
            .map(ExactFactorizationTransformationPipeline.Result
                ::candidateCertificateHash)
            .filter(value -> !value.isEmpty())
            .orElse(
                PolynomialTheoryUtilityFactorizationAttempt.NO_SELECTION
            );
        String transitionId = transition == null
            ? PolynomialTheoryUtilityFactorizationAttempt.NO_TRANSITION
            : transition.transitionId();
        String verifierOutcome =
            PolynomialTheoryUtilityFactorizationAttempt.NO_SELECTION.equals(
                selected
            )
                ? report.status().name()
                : "VERIFIED";
        return PolynomialTheoryUtilityFactorizationAttempt.create(
            attemptIndex,
            input.inputId(),
            profile().engineId(),
            hash(request.canonicalMaterial()),
            factorization.certificateHash(),
            candidates,
            selected,
            transitionId,
            verifierOutcome,
            report.verificationHash()
        );
    }

    private static List<PrimitiveDescriptor> primitiveExpansion(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityOnDemandOccurrencePlan.Occurrence occurrence,
        ExactNestedFactorizationTransformationPipeline.Result nested
    ) {
        var transformation = nested.transformation().orElseThrow();
        int finalEdge = occurrence.path().size();
        List<PrimitiveDescriptor> result = new ArrayList<>();

        result.add(new PrimitiveDescriptor(
            0,
            "EXECUTION_ADMISSION_POLICY",
            ADMISSION_POLICY.policyId()
        ));
        result.add(new PrimitiveDescriptor(
            0,
            "OCCURRENCE_ROOT_SELECTION",
            nested.projection().orElseThrow().certificateHash()
        ));
        StringBuilder prefix = new StringBuilder();
        for (int edge = 0; edge < occurrence.path().size(); edge++) {
            if (!prefix.isEmpty()) {
                prefix.append('.');
            }
            prefix.append(occurrence.path().get(edge));
            result.add(new PrimitiveDescriptor(
                edge + 1,
                "OCCURRENCE_CHILD_SELECTION",
                hash(
                    "regelsuche.polynomial-theory-utility-path-edge/v1",
                    input.inputId(),
                    Integer.toString(occurrence.occurrenceIndex()),
                    prefix.toString()
                )
            ));
        }

        result.add(new PrimitiveDescriptor(
            finalEdge,
            "EXACT_SOURCE_EVIDENCE",
            transformation.occurrence().sourceEvidenceHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "EXACT_FACTORIZATION_PIPELINE",
            transformation.factorization().certificateHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "VERIFIER_SELECTED_CANDIDATE",
            transformation.candidateCertificateHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "EXACT_FACTOR_RENDERING",
            transformation.rendering().orElseThrow().certificateHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "EXACT_REPARSE",
            hash(
                "regelsuche.polynomial-theory-utility-exact-reparse/v1",
                transformation.reparsed().orElseThrow().source()
            )
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "EXACT_POLYNOMIAL_RECONSTRUCTION",
            transformation.reconstruction().orElseThrow().certificateHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "VERIFIER_BOUND_TRANSFORMATION",
            transformation.certificateHash()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "AST_OCCURRENCE_REPLACEMENT",
            nested.rewrittenStructuralHash().orElseThrow()
        ));
        result.add(new PrimitiveDescriptor(
            finalEdge,
            "AST_REPLACEMENT_REPLAY",
            nested.certificateHash()
        ));
        return List.copyOf(result);
    }

    private static TerminalStatus terminalStatus(
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        List<ExactNestedFactorizationTransformationPipeline.Status> statuses
    ) {
        return terminalStatus(transitions.size(), statuses);
    }

    static TerminalStatus terminalStatus(
        int transitionCount,
        List<ExactNestedFactorizationTransformationPipeline.Status> statuses
    ) {
        var retainedStatuses = List.copyOf(
            Objects.requireNonNull(statuses, "statuses")
        );
        if (transitionCount < 0 || transitionCount > retainedStatuses.size()) {
            throw new IllegalArgumentException(
                "transition count differs from occurrence status count"
            );
        }
        if (transitionCount > 0) {
            boolean everyOccurrenceTransformed =
                transitionCount == retainedStatuses.size()
                    && retainedStatuses.stream().allMatch(value ->
                        value
                            == ExactNestedFactorizationTransformationPipeline
                                .Status.TRANSFORMED
                    );
            if (!everyOccurrenceTransformed) {
                throw new IllegalStateException(
                    "partial occurrence success cannot become a terminal "
                        + "validated result"
                );
            }
            return TerminalStatus.VALIDATED_TRANSITION;
        }
        if (retainedStatuses.contains(
                ExactNestedFactorizationTransformationPipeline.Status
                    .TECHNICAL_FAILURE)) {
            return TerminalStatus.TECHNICAL_FAILURE;
        }
        if (retainedStatuses.contains(
                ExactNestedFactorizationTransformationPipeline.Status
                    .BUDGET_INCONCLUSIVE)) {
            return TerminalStatus.BUDGET_INCONCLUSIVE;
        }
        if (retainedStatuses.stream().anyMatch(
                PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                    ::unsupported
        )) {
            return TerminalStatus.UNSUPPORTED;
        }
        return TerminalStatus.NO_TRANSITION;
    }

    private static boolean unsupported(
        ExactNestedFactorizationTransformationPipeline.Status status
    ) {
        return status
                == ExactNestedFactorizationTransformationPipeline.Status
                    .UNSUPPORTED
            || status
                == ExactNestedFactorizationTransformationPipeline.Status
                    .POSITION_NOT_PRESENT
            || status
                == ExactNestedFactorizationTransformationPipeline.Status
                    .POSITION_STALE;
    }

    private static String detailCode(
        TerminalStatus status,
        List<PolynomialTheoryUtilityTransitionOutcome> transitions,
        List<ExactNestedFactorizationTransformationPipeline.Status> statuses
    ) {
        String detail = switch (status) {
            case VALIDATED_TRANSITION -> transitions.size() == 1
                ? "ON_DEMAND_VERIFIED_FACTORIZATION_TRANSITION"
                : "ON_DEMAND_VERIFIED_FACTORIZATION_TRANSITIONS";
            case NO_TRANSITION ->
                "ON_DEMAND_VERIFIED_FACTORIZATION_NO_TRANSITION";
            case UNSUPPORTED ->
                "ON_DEMAND_VERIFIED_FACTORIZATION_UNSUPPORTED";
            case BUDGET_INCONCLUSIVE ->
                "ON_DEMAND_VERIFIED_FACTORIZATION_BUDGET_INCONCLUSIVE";
            case TECHNICAL_FAILURE ->
                "ON_DEMAND_VERIFIED_FACTORIZATION_TECHNICAL_FAILURE";
        };
        return withAdmissionPolicy(detail);
    }

    private static String withAdmissionPolicy(String detailCode) {
        return Objects.requireNonNull(detailCode, "detailCode")
            + ':' + ADMISSION_POLICY.policyId();
    }

    private static String verifierOutcome(
        TerminalStatus status,
        List<PolynomialTheoryUtilityFactorizationAttempt> attempts
    ) {
        if (status == TerminalStatus.VALIDATED_TRANSITION) {
            return "VERIFIED";
        }
        if (attempts.isEmpty()) {
            return "NOT_REQUESTED";
        }
        return attempts.getFirst().verifierOutcome();
    }

    private static PolynomialWorkLedger measuredRawWork(
        ExactNestedFactorizationTransformationPipeline.Result nested,
        int primitiveStepCount
    ) {
        Map<String, Long> stages = new LinkedHashMap<>(
            nested.totalWork().stages()
        );
        boolean attempted = nested.factorization()
            .filter(value -> value.request().isPresent())
            .filter(value -> value.report().isPresent())
            .isPresent();
        if (attempted) {
            add(stages, "study.evidence.factorization-attempt-records", 1L);
        }
        if (nested.transformed()) {
            add(stages, "study.evidence.transition-outcome-records", 1L);
            add(stages, "study.evidence.transition-trace-records", 1L);
            add(
                stages,
                "study.evidence.primitive-step-records",
                primitiveStepCount
            );
        }
        return new PolynomialWorkLedger(stages);
    }

    private static void add(
        Map<String, Long> stages,
        String stage,
        long units
    ) {
        if (units > 0L) {
            stages.merge(stage, units, Math::addExact);
        }
    }

    private static PolynomialTheoryUtilityExecutionInput occurrenceAuthority(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityOnDemandOccurrencePlan.Occurrence occurrence
    ) {
        return new PolynomialTheoryUtilityExecutionInput(
            hash(
                "regelsuche.polynomial-theory-utility-occurrence-authority/v1",
                input.inputId(),
                Integer.toString(occurrence.occurrenceIndex()),
                occurrence.pathKey()
            ),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            occurrence.admittedPrimitiveWork(),
            occurrence.totalMechanicalWork(),
            occurrence.factorizationWork(),
            input.inputStatus()
        );
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String hash(String... values) {
        StringBuilder material = new StringBuilder();
        for (String value : values) {
            String retained = Objects.requireNonNull(value, "hash value");
            material.append(retained.length()).append(':').append(retained);
        }
        return hash(material.toString());
    }

    private record PrimitiveDescriptor(
        int pathEdgeIndex,
        String ruleId,
        String evidenceHash
    ) {
        private PrimitiveDescriptor {
            if (pathEdgeIndex < 0
                    || Objects.requireNonNull(ruleId, "ruleId").isBlank()
                    || !Objects.requireNonNull(
                        evidenceHash,
                        "evidenceHash"
                    ).matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "primitive descriptor is invalid"
                );
            }
        }
    }
}
