package de.regelsuche.mining;

import java.util.List;

/**
 * Optional symbolic-regression input port.
 *
 * <p>Implementations are disabled by default and must emit {@link HypothesisCandidate}
 * values only. Symbolic-regression output is evidence, never a proof.</p>
 */
public interface SymbolicRegressionHypothesisSource {
    default boolean enabled() {
        return false;
    }

    List<HypothesisCandidate> propose(List<SuccessfulTransformationPath> paths);
}
