package de.regelsuche.math.algorithms.cas;

import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.time.Duration;
import java.util.List;

/** Safe default Singular worker used until an external process adapter is configured. */
public final class DisabledSingularWorker implements SingularWorker {
    @Override
    public MathematicalAlgorithmRegistry.AlgorithmExecutionResult reduceModuloIdeal(
        String polynomialExpression,
        List<String> generatorExpressions,
        Duration timeout
    ) {
        return MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unavailable(
            "singularBackend is enabled, but no Singular worker is configured"
        );
    }
}
