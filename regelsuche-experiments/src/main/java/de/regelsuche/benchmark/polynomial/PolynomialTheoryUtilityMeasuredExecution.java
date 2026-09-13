package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.AdapterRegistry;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateBatch;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.Run;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.TargetBlindRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the existing target-blind runner while retaining every measurement.
 *
 * <p>This class is not a second matrix runner. It decorates the already frozen
 * adapter inventory, delegates ordering, input checks and run lifecycle to
 * {@link TargetBlindRunner}, and binds the resulting batch to the measurements
 * captured during those exact executions.</p>
 */
public final class PolynomialTheoryUtilityMeasuredExecution {
    /**
     * Run extension for adapters that retain execution-time measurements.
     */
    public interface MeasuredRun extends Run {
        PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        );

        /** Executes one row and requires result v3 and measurements v2. */
        default PolynomialTheoryUtilityMeasuredCandidate executeObserved(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            return requireObserved(executeMeasured(input, formationCase));
        }

        @Override
        default PolynomialTheoryUtilityCandidateResult execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            return Objects.requireNonNull(
                executeMeasured(input, formationCase),
                "measured adapter result"
            ).result();
        }
    }

    public PolynomialTheoryUtilityCandidateMeasurementBatch execute(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        List<PolynomialTheoryUtilityProfileAdapter> adapters
    ) {
        return execute(inputs, adapters, false);
    }

    /**
     * Executes the frozen inventory only with explicitly observed adapters.
     *
     * <p>Every adapter must declare result v3 before any run is opened. Each
     * run must supply measurements explicitly, and every actual row must use
     * result v3 and measurements v2. This boundary neither supplies missing
     * profile implementations nor establishes a run-wide cache history.</p>
     */
    public PolynomialTheoryUtilityCandidateMeasurementBatch executeObserved(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        List<PolynomialTheoryUtilityProfileAdapter> adapters
    ) {
        return execute(inputs, adapters, true);
    }

    private PolynomialTheoryUtilityCandidateMeasurementBatch execute(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        List<PolynomialTheoryUtilityProfileAdapter> adapters,
        boolean observed
    ) {
        Objects.requireNonNull(inputs, "inputs");
        List<PolynomialTheoryUtilityProfileAdapter> supplied = List.copyOf(
            Objects.requireNonNull(adapters, "adapters")
        );
        if (observed) {
            new AdapterRegistry(supplied);
            for (var adapter : supplied) {
                if (!PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA
                        .equals(adapter.resultSchema())) {
                    throw new IllegalArgumentException(
                        "observed execution requires a declared result v3 adapter: "
                            + adapter.profileId());
                }
            }
        }
        Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured =
            new LinkedHashMap<>();
        List<PolynomialTheoryUtilityProfileAdapter> decorated =
            new ArrayList<>(supplied.size());
        supplied.forEach(adapter -> decorated.add(
            new CapturingAdapter(adapter, captured, observed)
        ));

        CandidateBatch results = new TargetBlindRunner().execute(
            inputs,
            new AdapterRegistry(decorated)
        );
        List<PolynomialTheoryUtilityCandidateMeasurements> measurements =
            results.results().stream()
                .map(result -> requireMeasurement(captured, result))
                .toList();
        if (captured.size() != measurements.size()) {
            throw new IllegalStateException(
                "measured execution retained evidence outside its result batch"
            );
        }
        return PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            results,
            measurements
        );
    }

    private static PolynomialTheoryUtilityMeasuredCandidate requireObserved(
            PolynomialTheoryUtilityMeasuredCandidate candidate) {
        Objects.requireNonNull(candidate, "measured adapter result");
        if (!PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA
                    .equals(candidate.result().schema())
                || !PolynomialTheoryUtilityCandidateMeasurements.OBSERVED_SCHEMA
                    .equals(candidate.measurements().schema())) {
            throw new IllegalArgumentException(
                "observed execution requires result v3 and measurements v2");
        }
        return candidate;
    }

    private static PolynomialTheoryUtilityCandidateMeasurements
            requireMeasurement(
                Map<String, PolynomialTheoryUtilityCandidateMeasurements>
                    captured,
                PolynomialTheoryUtilityCandidateResult result
            ) {
        var value = captured.get(
            Objects.requireNonNull(result, "result").resultId()
        );
        if (value == null) {
            throw new IllegalStateException(
                "target-blind result lacks its execution-time measurement"
            );
        }
        value.validateAgainst(result);
        return value;
    }

    private static final class CapturingAdapter
            implements PolynomialTheoryUtilityProfileAdapter {
        private final PolynomialTheoryUtilityProfileAdapter delegate;
        private final boolean observed;
        private final Map<
            String,
            PolynomialTheoryUtilityCandidateMeasurements
        > captured;

        private CapturingAdapter(
            PolynomialTheoryUtilityProfileAdapter delegate,
            Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured,
            boolean observed
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.captured = Objects.requireNonNull(captured, "captured");
            this.observed = observed;
        }

        @Override
        public String profileId() {
            return delegate.profileId();
        }

        @Override
        public String adapterId() {
            return delegate.adapterId();
        }

        @Override
        public String resultSchema() {
            return delegate.resultSchema();
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            return new CapturingRun(
                Objects.requireNonNull(
                    delegate.openRun(descriptor),
                    "delegate run"
                ),
                captured,
                observed
            );
        }
    }

    private static final class CapturingRun implements Run {
        private final Run delegate;
        private final boolean observed;
        private final Map<
            String,
            PolynomialTheoryUtilityCandidateMeasurements
        > captured;

        private CapturingRun(
            Run delegate,
            Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured,
            boolean observed
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.captured = Objects.requireNonNull(captured, "captured");
            this.observed = observed;
        }

        @Override
        public PolynomialTheoryUtilityCandidateResult execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            if (observed && !(delegate instanceof MeasuredRun)) {
                throw new IllegalArgumentException(
                    "observed execution requires an explicit measured run");
            }
            PolynomialTheoryUtilityMeasuredCandidate measured =
                delegate instanceof MeasuredRun measuredRun
                    ? Objects.requireNonNull(
                        measuredRun.executeMeasured(input, formationCase),
                        "measured adapter result"
                    )
                    : PolynomialTheoryUtilityMeasuredCandidate
                        .withoutObservations(
                            Objects.requireNonNull(
                                delegate.execute(input, formationCase),
                                "adapter result"
                            )
                        );
            if (observed) requireObserved(measured);
            var result = measured.result();
            var previous = captured.putIfAbsent(
                result.resultId(),
                measured.measurements()
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                    "measured execution repeats a result identity"
                );
            }
            return result;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
