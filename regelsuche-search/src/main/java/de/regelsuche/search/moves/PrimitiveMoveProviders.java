package de.regelsuche.search.moves;

import de.regelsuche.transform.PreparedAstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/** One lazy lane per primitive; no hidden growth or candidate cap in this finite AST relation. */
public final class PrimitiveMoveProviders {
    private PrimitiveMoveProviders() {}
    public static List<MoveProvider> complete(List<RewriteRule> rules, String inventoryId) {
        return List.copyOf(rules).stream().map(rule -> (MoveProvider) new EngineMoveProvider(
            new MoveProvider.Descriptor(rule.id(), rule.id(), SearchMove.SourceKind.PRIMITIVE,
                SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, inventoryId),
            new PreparedAstRewriteTransformationEngine(List.of(rule), Integer.MAX_VALUE, Integer.MAX_VALUE), true)).toList();
    }
}
