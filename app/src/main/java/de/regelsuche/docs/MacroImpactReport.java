package de.regelsuche.docs;

public record MacroImpactReport(
        int withoutMacroStates,
        int withMacroStates,
        int pathsExplored,
        int convergenceCount,
        int bridgeUsage,
        boolean bridgeDiscovered,
        boolean macroReused) {
    public double improvementFactor() {
        return withoutMacroStates / (double) withMacroStates;
    }
}
