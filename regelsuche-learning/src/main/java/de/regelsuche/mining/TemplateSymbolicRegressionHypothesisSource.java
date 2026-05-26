package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Small internal template-based symbolic-regression source; outputs evidence-only hypotheses. */
public final class TemplateSymbolicRegressionHypothesisSource implements SymbolicRegressionHypothesisSource {
    private final boolean enabled;
    private final int minimumSupport;
    private final SymbolicRegressionBackend backend;

    public TemplateSymbolicRegressionHypothesisSource(boolean enabled, int minimumSupport) {
        this(enabled, minimumSupport, new DefaultTemplateSymbolicRegressionBackend());
    }

    public TemplateSymbolicRegressionHypothesisSource(
        boolean enabled,
        int minimumSupport,
        SymbolicRegressionBackend backend
    ) {
        this.enabled = enabled;
        this.minimumSupport = Math.max(2, minimumSupport);
        this.backend = backend == null ? new DefaultTemplateSymbolicRegressionBackend() : backend;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<HypothesisCandidate> propose(List<SuccessfulTransformationPath> paths) {
        if (!enabled || paths == null || paths.size() < minimumSupport) {
            return List.of();
        }
        List<SymbolicRegressionSample> samples = backend.extractSamples(paths, minimumSupport);
        if (samples.size() < minimumSupport) {
            return List.of();
        }
        return backend.fit(samples, minimumSupport).stream()
            .map(this::hypothesis)
            .toList();
    }

    private HypothesisCandidate hypothesis(SymbolicRegressionFittedResult fitted) {
        List<String> support = fitted.supportingSamples().stream().map(SymbolicRegressionSample::pathId).toList();
        List<HypothesisCandidate.ExpressionPair> witnesses = fitted.supportingSamples().stream()
            .map(sample -> new HypothesisCandidate.ExpressionPair(
                DefaultTemplateSymbolicRegressionBackend.format(sample.x()),
                DefaultTemplateSymbolicRegressionBackend.format(sample.y())))
            .toList();
        return new HypothesisCandidate(
            "template-symreg-" + Integer.toHexString((fitted.templateName() + fitted.expression()).hashCode()),
            "x",
            fitted.expression(),
            support,
            witnesses,
            List.of("symbolic-regression-evidence-only", "template:" + fitted.templateName()),
            0.0,
            CandidateProofStatus.OBSERVED,
            null,
            List.of(
                "fitted-by-template-library",
                "sample-count=" + fitted.supportingSamples().size(),
                "max-residual=" + DefaultTemplateSymbolicRegressionBackend.format(fitted.maxResidual()),
                "confidence=" + DefaultTemplateSymbolicRegressionBackend.format(fitted.confidence())
            ),
            Map.of("symbolicRegression", List.of(
                "internal-template",
                "evidence-only",
                "template=" + fitted.templateName(),
                "confidence=" + DefaultTemplateSymbolicRegressionBackend.format(fitted.confidence())
            )),
            Instant.EPOCH
        );
    }
}
