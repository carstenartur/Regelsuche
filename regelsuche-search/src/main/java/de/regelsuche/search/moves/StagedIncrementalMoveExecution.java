package de.regelsuche.search.moves;

import java.util.List;

/** Distinct v2 receipt: no fields are added to the frozen native v1 serialization. */
public record StagedIncrementalMoveExecution(String workRevision, String orderRevision,
        List<Provider> providers, List<Expansion> expansions) {
    public static final String WORK_REVISION = "regelsuche.staged-incremental-move-search-work/v2";
    public static final String ORDER_REVISION = "regelsuche.stage-provider-score-native-order-two-learned-burst/v2";
    public StagedIncrementalMoveExecution { providers = List.copyOf(providers); expansions = List.copyOf(expansions); }
    public boolean accountingComplete() {
        return expansions.stream().flatMap(expansion -> expansion.lanes().stream())
            .allMatch(lane -> lane.cursor() == null || lane.cursor().accountingComplete());
    }
    public record Provider(MoveProvider.Descriptor descriptor, IncrementalProviderContract.Definition definition) {}
    public record Lane(int providerIndex, int stage, IncrementalProviderContract.Snapshot cursor) {}
    public record Expansion(MoveState source, boolean closed, List<Lane> lanes) {
        public Expansion { lanes = List.copyOf(lanes); }
    }
}
