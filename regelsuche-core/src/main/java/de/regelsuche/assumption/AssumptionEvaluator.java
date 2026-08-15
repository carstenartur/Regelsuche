package de.regelsuche.assumption;

import java.util.List;

/** Backend-neutral evaluator for one required assumption. */
public interface AssumptionEvaluator {
    String id();

    String revision();

    AssumptionEvaluationEvidence evaluate(
        Assumption requiredAssumption,
        List<Assumption> knownAssumptions
    );
}
