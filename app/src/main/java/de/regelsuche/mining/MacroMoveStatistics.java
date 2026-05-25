package de.regelsuche.mining;

import java.util.List;

/** Runtime usefulness counters for a learned macro move. */
public record MacroMoveStatistics(
    int timesConsidered,
    int timesApplied,
    int timesImprovedScore,
    double averageCostReduction,
    List<String> usefulForGoals
) {
    public MacroMoveStatistics {
        usefulForGoals = usefulForGoals == null ? List.of() : List.copyOf(usefulForGoals);
    }

    public static MacroMoveStatistics empty() {
        return new MacroMoveStatistics(0, 0, 0, 0.0, List.of());
    }
}
