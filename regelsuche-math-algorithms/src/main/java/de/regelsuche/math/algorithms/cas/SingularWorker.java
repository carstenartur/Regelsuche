package de.regelsuche.math.algorithms.cas;

import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.time.Duration;
import java.util.List;

/** External Singular worker contract with deterministic timeout/error result mapping. */
public interface SingularWorker {
    MathematicalAlgorithmRegistry.AlgorithmExecutionResult reduceModuloIdeal(
        String polynomialExpression,
        List<String> generatorExpressions,
        Duration timeout
    );
}
