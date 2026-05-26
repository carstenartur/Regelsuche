package de.regelsuche.mining;

import java.util.List;

/** Stable port for symbolic-regression engines; all results remain hypothesis evidence only. */
public interface SymbolicRegressionBackend {
    List<SymbolicRegressionSample> extractSamples(List<SuccessfulTransformationPath> paths, int minimumSupport);

    List<SymbolicRegressionFittedResult> fit(List<SymbolicRegressionSample> samples, int minimumSupport);
}
