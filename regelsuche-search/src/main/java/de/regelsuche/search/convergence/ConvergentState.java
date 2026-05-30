package de.regelsuche.search.convergence;

import java.util.List;
import java.util.Optional;

public record ConvergentState(
    String expression,
    String canonicalHash,
    List<ConvergentPath> incomingPaths,
    String shortestPathId,
    String mostDidacticPathId,
    Optional<String> macroPathId
) {
    public ConvergentState {
        incomingPaths = List.copyOf(incomingPaths);
        macroPathId = macroPathId == null ? Optional.empty() : macroPathId;
    }
}
