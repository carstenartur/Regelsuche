package de.regelsuche.mining;

import java.util.List;

/**
 * Optional symbolic-regression input port.
 *
 * <p>Implementations are disabled by default and must emit {@link HypothesisCandidate}
 * values only. Symbolic-regression output is evidence, never a proof. Backends can
 * expose {@link SymbolicRegressionSample} and {@link SymbolicRegressionFittedResult}
 * values internally, but the promotion pipeline only receives observed hypotheses
 * that still pass through counterexample search.</p>
 */
public interface SymbolicRegressionHypothesisSource {
    default boolean enabled() {
        return false;
    }

    List<HypothesisCandidate> propose(List<SuccessfulTransformationPath> paths);
}
