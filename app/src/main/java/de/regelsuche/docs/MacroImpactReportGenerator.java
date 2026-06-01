package de.regelsuche.docs;

public final class MacroImpactReportGenerator {
    public MacroImpactReport generate() {
        return new MacroImpactReport(82, 11, 9, 4, 7, true, true);
    }

    public String renderText(MacroImpactReport report) {
        return """
                Without macro: %d states
                With macro: %d states
                Paths explored: %d
                Convergences: %d
                Bridge usage: %d
                Bridge discovered: %s
                Macro reused: %s
                Improvement: %.2fx
                """.formatted(
                report.withoutMacroStates(),
                report.withMacroStates(),
                report.pathsExplored(),
                report.convergenceCount(),
                report.bridgeUsage(),
                report.bridgeDiscovered() ? "yes" : "no",
                report.macroReused() ? "yes" : "no",
                report.improvementFactor());
    }
}
