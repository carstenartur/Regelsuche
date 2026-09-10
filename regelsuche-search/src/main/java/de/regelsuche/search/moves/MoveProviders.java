package de.regelsuche.search.moves;

import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;

/** Unwraps the old base-plus-hypothesis boundary for the common picker. Legacy transform() remains reproducible. */
public final class MoveProviders {
    private MoveProviders() {}
    public static List<MoveProvider> from(TransformationEngine engine) {
        if (engine instanceof MoveProviderInventory inventory) return List.copyOf(inventory.moveProviders());
        if (engine instanceof HypothesisTransformationEngine hypotheses) {
            var providers = new ArrayList<>(from(hypotheses.baseEngine()));
            int index = 0;
            for (var operator : hypotheses.operators()) {
                if (operator instanceof MoveProvider provider) providers.add(provider);
                else providers.add(new EngineMoveProvider(new MoveProvider.Descriptor(
                    "hypothesis-" + index, operator.getClass().getName(), SearchMove.SourceKind.HYPOTHESIS,
                    SearchMove.ProofStrength.UNVALIDATED, List.of(), SearchMove.ValueEvidence.UNKNOWN,
                    operator.getClass().getName()), operator::generateCandidates, false));
                index++;
            }
            return List.copyOf(providers);
        }
        return List.of(new EngineMoveProvider(new MoveProvider.Descriptor("primitive-inventory", "*",
            SearchMove.SourceKind.PRIMITIVE, SearchMove.ProofStrength.REPLAYABLE,
            List.of(), SearchMove.ValueEvidence.UNKNOWN, engine.getClass().getName()), engine, false));
    }
}
