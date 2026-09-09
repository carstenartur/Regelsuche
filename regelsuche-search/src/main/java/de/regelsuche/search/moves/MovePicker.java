package de.regelsuche.search.moves;

import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Optional;

/** Pull boundary shared by eager controls and later staged/lazy scheduling. */
public interface MovePicker {
    Optional<SearchMove> next();
    TransformationWorkMetrics workMetrics();
    List<SearchMove> generatedMoves();
    boolean complete();
}
