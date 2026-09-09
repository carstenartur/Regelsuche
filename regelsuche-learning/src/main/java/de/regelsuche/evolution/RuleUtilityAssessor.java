package de.regelsuche.evolution;

import de.regelsuche.inventory.RuleUtilityEvidence;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.Transformation;
import java.util.List;

/** Evaluates a retained trace against the same frozen primitive inventory, excluding all new macros. */
public final class RuleUtilityAssessor {
    public record Assessment(RuleUtilityEvidence utility, PrimitiveTraceMinimalityVerifier.Assessment reference) {}

    public Assessment assess(EvolutionGenome primitiveInventory, String source, List<Transformation> observedPath,
            PrimitiveTraceMinimalityVerifier.Limits limits, long measuredDirectApplicationWork) {
        var reference = new PrimitiveTraceMinimalityVerifier(primitiveInventory).assess(source, observedPath, limits);
        return new Assessment(fromReference(reference, measuredDirectApplicationWork), reference);
    }

    /** Takes the verifier's privately issued assessment, not a parsed minimum claim. */
    public RuleUtilityEvidence fromReference(PrimitiveTraceMinimalityVerifier.Assessment reference, long measuredDirectApplicationWork) {
        boolean replayed = reference.observedReplayVerified();
        int known = reference.minimumProved() ? reference.minimumPrimitiveSteps()
            : replayed ? reference.observedPrimitiveSteps() : -1;
        var limits = new JsonWriter().beginObject();
        PrimitiveTraceMinimalityVerifier.writeLimits(limits, reference.limits());
        var scope = new RuleUtilityEvidence.ReferenceScope(reference.inventoryHash(), reference.source(), reference.target(),
            PrimitiveTraceMinimalityVerifier.SCOPE, limits.endObject().toString(), reference.contentHash(), replayed, reference.measuredWork());
        return new RuleUtilityEvidence(RuleUtilityEvidence.REVISION, reference.observedPrimitiveSteps(), known,
            reference.minimumProved(), 1, measuredDirectApplicationWork,
            replayed ? reference.observedPrimitiveSteps() : -1, 0, 0, 0, 0, 0, List.of(), 0,
            reference.minimumProved() ? 1.0 : replayed ? 0.5 : 0.0, replayed ? 1 : 0, scope);
    }
}
