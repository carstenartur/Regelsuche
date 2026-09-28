package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Optional;

/** Legacy facade over the shared eager batch picker. */
public final class EagerMovePicker implements MovePicker {
    private final EagerBatchPicker<SearchMove> picker;
    public EagerMovePicker(List<MoveProvider> providers,MovePriorityPolicy policy,MoveState state,MoveContext context) {
        picker=new EagerBatchPicker<>(SearchBatches.legacyProviders(providers,state,context),SearchBatches.legacyRanking(policy,state,context));
    }
    @Override public Optional<SearchMove> next(){return picker.next();}
    @Override public TransformationWorkMetrics workMetrics(){return picker.workMetrics();}
    @Override public List<SearchMove> generatedMoves(){return picker.generatedMoves();}
    @Override public boolean complete(){return picker.complete();}
    @Override public int nextStage(){return picker.nextStage();}
}
