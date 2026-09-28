package de.regelsuche.search.moves;

import java.util.List;

/** Independent, costed mathematical admission. Ranking and imported proof labels are not authorization. */
@FunctionalInterface
public interface MoveVerifier {
    Verification verify(MoveState source, SearchMove move, MoveContext context);
    record Verification(boolean accepted, long work, List<String> receipts, String reason) implements SearchExecution.Verification, de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(receipts);v.reference(reason);}

        public Verification {
            if (work < 0 || reason == null || (accepted && receipts.isEmpty())) throw new IllegalArgumentException("invalid verification receipt");
            receipts = List.copyOf(receipts);
        }
    }
}
