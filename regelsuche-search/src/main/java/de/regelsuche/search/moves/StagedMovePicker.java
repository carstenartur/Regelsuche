package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Optional;

/** Legacy facade over the shared staged batch picker. */
public final class StagedMovePicker implements MovePicker {
    private final StagedBatchPicker<SearchMove> picker;
    public StagedMovePicker(List<MoveProvider> providers,MovePriorityPolicy policy,MoveState state,MoveContext context) {
        picker=new StagedBatchPicker<>(SearchBatches.legacyProviders(providers,state,context),SearchBatches.legacyRanking(policy,state,context));
    }
    @Override public Optional<SearchMove> next(){return picker.next();}
    @Override public TransformationWorkMetrics workMetrics(){return picker.workMetrics();}
    @Override public List<SearchMove> generatedMoves(){return picker.generatedMoves();}
    @Override public boolean complete(){return picker.complete();}
    @Override public int nextStage(){return picker.nextStage();}
}
