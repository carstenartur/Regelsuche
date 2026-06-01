package de.regelsuche.search;

public record MacroImpactReport(
        SearchSpaceAnalytics withoutMacro,
        SearchSpaceAnalytics withMacro) {

    public int stateReduction() {
        return withoutMacro.statesExplored() - withMacro.statesExplored();
    }

    public String renderMarkdown() {
        return "| Mode | States explored | Unique canonical states | Macro usage |\n"
                + "| --- | ---: | ---: | ---: |\n"
                + "| Without macro | " + withoutMacro.statesExplored() + " | " + withoutMacro.uniqueCanonicalStates() + " | " + withoutMacro.learnedMacroUsage() + " |\n"
                + "| With macro | " + withMacro.statesExplored() + " | " + withMacro.uniqueCanonicalStates() + " | " + withMacro.learnedMacroUsage() + " |\n"
                + "| Reduction | " + stateReduction() + " | | |\n";
    }
}
