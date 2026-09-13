package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.transform.MeasuredPolynomialDecompositionPipeline;
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Measured execution of the frozen specialized hypothesis control. */
public final class PolynomialTheoryUtilitySpecializedAdapter implements PolynomialTheoryUtilityProfileAdapter {
    public static final String PROFILE_ID = "SPECIALIZED_BINARY_QUARTIC_CONTROL";
    public static final String ADAPTER_ID = "regelsuche.polynomial-theory-utility.specialized-binary-quartic-control/v1";
    private static final int PRIMITIVE_STEPS = 6;

    @Override public String profileId() { return PROFILE_ID; }
    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public String resultSchema() { return PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA; }

    @Override public Run openRun(RunDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        var expected = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input -> input.runId().equals(descriptor.runId())).toList();
        if (expected.size() != PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()
                || descriptor.expectedCaseCount() != expected.size()
                || !PROFILE_ID.equals(descriptor.profileId()) || !ADAPTER_ID.equals(descriptor.adapterId())
                || !expected.getFirst().profileId().equals(PROFILE_ID)
                || !expected.getFirst().checkpointId().equals(descriptor.checkpointId())) {
            throw new IllegalArgumentException("specialized run differs from the frozen matrix");
        }
        return new SpecializedRun(expected);
    }

    private static final class SpecializedRun implements MeasuredRun {
        private final List<PolynomialTheoryUtilityExecutionInput> expected;
        private int next;
        private boolean closed;
        private SpecializedRun(List<PolynomialTheoryUtilityExecutionInput> expected) { this.expected = List.copyOf(expected); }
        @Override public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                PolynomialTheoryUtilityExecutionInput input, PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
            if (closed || next >= expected.size() || !expected.get(next).equals(input)
                    || !PolynomialTheoryUtilityCaseCorpus.load().cases().get(next).equals(formation)) {
                throw new IllegalArgumentException("specialized input is not the next frozen row and formation");
            }
            try {
                var measured = executeCase(input, formation);
                next++;
                return measured;
            } catch (RuntimeException | Error failure) {
                closed = true;
                throw failure;
            }
        }
        @Override public void close() {
            if (closed || next != expected.size()) throw new IllegalStateException("specialized run did not consume every row");
            closed = true;
        }
    }

    private static PolynomialTheoryUtilityMeasuredCandidate executeCase(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        ExactParsedTerm parsed = null;
        try {
            authority.consume("projection.study-source-parse-code-units", formation.sourceExpression().length());
            parsed = new ExpressionParser().parseExactTerm(formation.sourceExpression());
        } catch (PolynomialWorkAuthority.LimitReached exhausted) { /* retain each unexecuted occurrence */ }
        var preparation = authority.ledger();
        var transitions = new ArrayList<PolynomialTheoryUtilityTransitionOutcome>();
        var traces = new ArrayList<PolynomialTheoryUtilityTransitionTrace>();
        var attempts = new ArrayList<PolynomialTheoryUtilityFactorizationAttempt>();
        var occurrences = new ArrayList<PolynomialTheoryUtilityExecutionObservations.Occurrence>();
        for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
            var before = authority.ledger();
            var beforeWork = authority.work();
            var occurrence = executeOccurrence(parsed, path, authority);
            var pipeline = occurrence.pipeline();
            var work = PolynomialTheoryUtilityExecutionObservations.difference(authority.work(), beforeWork);
            PolynomialTheoryUtilityTransitionOutcome transition = null;
            if (occurrence.status() == TerminalStatus.VALIDATED_TRANSITION) {
                transition = PolynomialTheoryUtilityTransitionOutcome.create(transitions.size(), input.inputId(), path,
                    pipeline.sourceOccurrenceExpression().orElseThrow(), pipeline.transformedExpression().orElseThrow(),
                    formation.sourceExpression(), pipeline.rewrittenRootSource().orElseThrow(), pipeline.ruleId(), pipeline.engineId(),
                    pipeline.sourceEvidenceHash().orElseThrow(), pipeline.certificateHash(), CacheDisposition.CACHE_DISABLED,
                    "NONE", "NONE", "NONE", work);
                transitions.add(transition);
                var steps = new ArrayList<PrimitiveStep>();
                for (var step : pipeline.primitiveExpansion()) {
                    steps.add(PrimitiveStep.create(transition, steps.size(), 0, step.stageId(), step.evidenceHash()));
                }
                traces.add(PolynomialTheoryUtilityTransitionTrace.create(transition, 1, steps, List.of()));
            }
            var ownAttempts = new ArrayList<String>();
            if (pipeline != null && pipeline.request().isPresent()) {
                var attempt = PolynomialTheoryUtilityFactorizationAttempt.createObserved(attempts.size(), input.inputId(),
                    occurrences.size(), pipeline, transition == null ? "NONE" : transition.transitionId());
                attempts.add(attempt);
                ownAttempts.add(attempt.attemptId());
            }
            occurrences.add(new PolynomialTheoryUtilityExecutionObservations.Occurrence(occurrences.size(), path,
                occurrence.status(), occurrence.detailCode(), pipeline == null ? "NONE" : pipeline.certificateHash(),
                transition == null ? "NONE" : transition.transitionId(), work.primitiveWork(),
                PolynomialTheoryUtilityExecutionObservations.difference(authority.ledger(), before), ownAttempts, List.of()));
        }
        var observed = PolynomialTheoryUtilityExecutionObservations.create(preparation, occurrences);
        String verifier = switch (observed.terminalStatus()) {
            case VALIDATED_TRANSITION -> "VERIFIED";
            case MIXED_OUTCOMES -> "RETAINED_OCCURRENCE_OUTCOMES";
            default -> attempts.isEmpty() ? "NOT_REQUESTED" : "RETAINED_ATTEMPT_OUTCOMES";
        };
        var result = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
            "SPECIALIZED_SHARED_CANONICAL_AUTHORITY:" + authority.projection().projectionId(), transitions, verifier, observed);
        return PolynomialTheoryUtilityMeasuredCandidate.create(result, traces, attempts, List.of());
    }

    private static Occurrence executeOccurrence(ExactParsedTerm parsed, List<Integer> path,
            PolynomialTheoryUtilityWorkAuthority authority) {
        MeasuredPolynomialDecompositionPipeline.Result pipeline = null;
        try {
            if (parsed == null || authority.remainingPrimitiveWork() < PRIMITIVE_STEPS) throw new PolynomialWorkAuthority.LimitReached();
            authority.consume("study.evidence.occurrence-records", 1);
            authority.consume("projection.study-path-navigation", path.size() + 1L);
            var selected = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
            var position = new TreePosition(path, ExpressionFormatter.formatMeasured(selected,
                units -> authority.consume("projection.study-position-format-code-units", units)));
            pipeline = new PolynomialDecompositionSynthesisOperator().factorExpression(parsed, position, authority);
            if (pipeline.generated()) {
                if (pipeline.primitiveExpansion().size() != PRIMITIVE_STEPS) {
                    throw new IllegalStateException("specialized primitive expansion changed");
                }
                authority.consume(new PolynomialWorkLedger(Map.of("study.evidence.transition-outcome-records", 1L,
                    "study.evidence.transition-trace-records", 1L, "study.evidence.primitive-step-records", (long) PRIMITIVE_STEPS)));
                authority.consumePrimitive(PRIMITIVE_STEPS);
            }
            return new Occurrence(terminal(pipeline), pipeline.detailCode(), pipeline);
        } catch (PolynomialWorkAuthority.LimitReached exhausted) {
            return new Occurrence(TerminalStatus.BUDGET_INCONCLUSIVE, exhausted.getMessage(), pipeline);
        }
    }

    private static TerminalStatus terminal(MeasuredPolynomialDecompositionPipeline.Result pipeline) {
        return switch (pipeline.status()) {
            case GENERATED -> TerminalStatus.VALIDATED_TRANSITION;
            case BUDGET_INCONCLUSIVE -> TerminalStatus.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED_SEMANTIC_VIEW, UNSUPPORTED_FACTORIZATION_REQUEST -> TerminalStatus.UNSUPPORTED;
            case POSITION_NOT_PRESENT, POSITION_STALE, TECHNICAL_FAILURE -> TerminalStatus.TECHNICAL_FAILURE;
            default -> TerminalStatus.NO_TRANSITION;
        };
    }
    private record Occurrence(TerminalStatus status, String detailCode, MeasuredPolynomialDecompositionPipeline.Result pipeline) { }
}
