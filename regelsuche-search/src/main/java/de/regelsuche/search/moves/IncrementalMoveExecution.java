package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationCursor;
import java.util.List;

/** Additive evidence; legacy move-search exports contain no fields from this revision. */
public record IncrementalMoveExecution(String workRevision, String orderRevision, List<Provider> providers,
        List<Expansion> expansions) {
    public static final String WORK_REVISION = "regelsuche.incremental-native-move-search-work/v1";
    public static final String ORDER_REVISION = "regelsuche.provider-inventory-native-occurrence-order/v1";
    public IncrementalMoveExecution { providers = List.copyOf(providers); expansions = List.copyOf(expansions); }
    public record Provider(MoveProvider.Descriptor descriptor, TransformationCursor.Definition definition) {}
    public record Lane(int providerIndex, boolean assumptionChecked, boolean assumptionRejected,
            TransformationCursor.Snapshot cursor) {}
    public record Expansion(MoveState source, boolean closed, List<Lane> lanes) {
        public Expansion { lanes = List.copyOf(lanes); }
    }
}
