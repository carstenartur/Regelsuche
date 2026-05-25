package de.regelsuche.search.memory;

/**
 * Why the search decided to drop or keep a re-visited state.
 *
 * <p>Each enum value carries an explanation suitable for the workbench UI's
 * "Suchgedächtnis" tab.</p>
 */
public enum PruningReason {
    /** A previously seen path reached this state with a strictly better score. */
    ALREADY_KNOWN_BETTER("Zustand übersprungen, weil bereits ein besserer Pfad bekannt ist."),
    /** This state was already reached at the same depth/score without new rules. */
    ALREADY_KNOWN_EQUAL("Zustand übersprungen, weil bereits bekannt (gleichwertig)."),
    /** A worse known path has been replaced by the new, better one. */
    REPLACED_WORSE_PATH("Zustand behalten – besserer Pfad ersetzt den bisherigen."),
    /** Re-visit kept because the new path uses rule combinations not seen before. */
    KEPT_NEW_RULE_COMBO("Zustand behalten – neue Regelkombination liefert frische Beweise."),
    /** Re-visit kept because the new path is shallower than the recorded one. */
    KEPT_LOWER_DEPTH("Zustand behalten – kürzerer Pfad gefunden."),
    /** Pruned because the search budget (visited / depth) was exhausted. */
    BUDGET_EXCEEDED("Zustand verworfen – Suchbudget überschritten.");

    private final String explanation;

    PruningReason(String explanation) {
        this.explanation = explanation;
    }

    public String explanation() {
        return explanation;
    }
}
