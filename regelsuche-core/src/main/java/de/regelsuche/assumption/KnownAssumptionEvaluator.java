package de.regelsuche.assumption;

import java.util.List;
import java.util.Objects;

/** Evaluates requirements from explicitly known typed assumptions. */
public final class KnownAssumptionEvaluator implements AssumptionEvaluator {
    public static final KnownAssumptionEvaluator INSTANCE =
        new KnownAssumptionEvaluator();

    private static final String ID = "core.known-assumptions";
    private static final String REVISION = "known-assumptions/v1";

    private KnownAssumptionEvaluator() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String revision() {
        return REVISION;
    }

    @Override
    public AssumptionEvaluationEvidence evaluate(
        Assumption requiredAssumption,
        List<Assumption> knownAssumptions
    ) {
        Objects.requireNonNull(requiredAssumption, "requiredAssumption");
        Objects.requireNonNull(knownAssumptions, "knownAssumptions");
        AssumptionTruthValue result = requiredAssumption.truthValueUnder(
            List.copyOf(knownAssumptions));
        String explanation = switch (result) {
            case TRUE ->
                "The explicit typed assumption context satisfies the requirement.";
            case FALSE ->
                "The explicit typed assumption context contradicts the requirement.";
            case UNKNOWN ->
                "The explicit typed assumption context does not decide the requirement.";
        };
        return AssumptionEvaluationEvidence.evaluated(
            id(), revision(), result, explanation, "");
    }
}
