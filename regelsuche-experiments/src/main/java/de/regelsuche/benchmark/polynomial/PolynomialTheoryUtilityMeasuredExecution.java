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
     * Run extension for adapters that produce non-empty mathematical evidence.
     */
    public interface MeasuredRun extends Run {
        PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        );

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
        Objects.requireNonNull(inputs, "inputs");
        List<PolynomialTheoryUtilityProfileAdapter> supplied = List.copyOf(
            Objects.requireNonNull(adapters, "adapters")
        );
        Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured =
            new LinkedHashMap<>();
        List<PolynomialTheoryUtilityProfileAdapter> decorated =
            new ArrayList<>(supplied.size());
        supplied.forEach(adapter -> decorated.add(
            new CapturingAdapter(adapter, captured)
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
        private final Map<
            String,
            PolynomialTheoryUtilityCandidateMeasurements
        > captured;

        private CapturingAdapter(
            PolynomialTheoryUtilityProfileAdapter delegate,
            Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.captured = Objects.requireNonNull(captured, "captured");
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
        public Run openRun(RunDescriptor descriptor) {
            return new CapturingRun(
                Objects.requireNonNull(
                    delegate.openRun(descriptor),
                    "delegate run"
                ),
                captured
            );
        }
    }

    private static final class CapturingRun implements Run {
        private final Run delegate;
        private final Map<
            String,
            PolynomialTheoryUtilityCandidateMeasurements
        > captured;

        private CapturingRun(
            Run delegate,
            Map<String, PolynomialTheoryUtilityCandidateMeasurements> captured
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.captured = Objects.requireNonNull(captured, "captured");
        }

        @Override
        public PolynomialTheoryUtilityCandidateResult execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
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
