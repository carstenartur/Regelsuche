package de.regelsuche.explain;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.layout.AstAriaRenderer;
import java.util.List;
import java.util.Locale;

/**
 * Generates ARIA-ready narration text for replay steps and
 * transformation paths.
 *
 * <p>The narration is derived from the semantic transformation
 * information (rule id, expression, equivalence status) — never from
 * rendered HTML or LaTeX strings — to satisfy the requirement that
 * "the ARIA layer must use semantic transformation information, not
 * regex/string parsing."</p>
 *
 * <p>German ({@link Locale#GERMAN}) and English ({@link Locale#ENGLISH})
 * are fully supported. All other locales fall back to English.</p>
 */
public class AriaDescriptionService {

    private final ExplanationService explanationService;

    public AriaDescriptionService() {
        this(new ExplanationService());
    }

    public AriaDescriptionService(ExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    // ── Step narration ───────────────────────────────────────────────────────

    /**
     * Builds a screen-reader-friendly narration for a single
     * transformation step.
     *
     * <p>Example output (English):
     * <pre>
     *   Step 1: Distributive law (addition, left). Before: a times open bracket b plus c close bracket.
     *   After: a times b plus a times c. Equivalence-preserving.
     * </pre></p>
     *
     * @param step      the transformation step
     * @param stepIndex 0-based step index used in the narration
     * @param locale    target locale
     * @return non-null, non-empty narration string
     */
    public String stepNarration(TransformationStep step, int stepIndex, Locale locale) {
        boolean german = isGerman(locale);
        String fromAria = AstAriaRenderer.ariaLabel(step.beforeExpression(), locale);
        String toAria   = AstAriaRenderer.ariaLabel(step.afterExpression(), locale);
        String ruleLabel = explanationService.ruleTitle(step.ruleId(), locale);

        String equivalenceNote;
        if (step.equivalencePreserving()) {
            equivalenceNote = german ? "Äquivalenz erhaltend." : "Equivalence-preserving.";
        } else {
            equivalenceNote = german
                ? "Achtung: Umformung ist nicht garantiert äquivalent."
                : "Warning: transformation is not guaranteed to be equivalent.";
        }

        if (german) {
            return "Schritt " + (stepIndex + 1) + ": " + ruleLabel + " "
                + "Vorher: " + fromAria + ". "
                + "Nachher: " + toAria + ". "
                + equivalenceNote;
        } else {
            return "Step " + (stepIndex + 1) + ": " + ruleLabel + " "
                + "Before: " + fromAria + ". "
                + "After: " + toAria + ". "
                + equivalenceNote;
        }
    }

    // ── Comparator-flip warning ──────────────────────────────────────────────

    /**
     * Returns a warning narration when a transformation flips the
     * comparator direction of an inequality.
     *
     * @param locale target locale
     * @return warning string suitable for an {@code aria-live} region
     */
    public String comparatorFlipWarning(Locale locale) {
        if (isGerman(locale)) {
            return "Warnung: Das Vergleichszeichen wurde umgekehrt, "
                + "weil durch eine negative Zahl dividiert (oder mit ihr multipliziert) wurde.";
        } else {
            return "Warning: The comparator was flipped because division (or multiplication) "
                + "by a negative number was applied.";
        }
    }

    // ── Path summary narration ───────────────────────────────────────────────

    /**
     * Builds a full narration for a complete transformation path,
     * announcing each step in sequence.
     *
     * @param path   the transformation path
     * @param locale target locale
     * @return multi-line narration string
     */
    public String pathNarration(DiscoveredTransformation path, Locale locale) {
        boolean german = isGerman(locale);
        List<TransformationStep> steps = path.steps();
        StringBuilder sb = new StringBuilder();

        if (german) {
            sb.append("Umformungspfad mit ").append(steps.size())
                .append(steps.size() == 1 ? " Schritt." : " Schritten.").append('\n');
            sb.append("Ausgangsausdruck: ")
                .append(AstAriaRenderer.ariaLabel(path.originalExpression(), locale)).append('\n');
        } else {
            sb.append("Transformation path with ").append(steps.size())
                .append(steps.size() == 1 ? " step." : " steps.").append('\n');
            sb.append("Original expression: ")
                .append(AstAriaRenderer.ariaLabel(path.originalExpression(), locale)).append('\n');
        }

        for (int i = 0; i < steps.size(); i++) {
            sb.append(stepNarration(steps.get(i), i, locale)).append('\n');
        }

        if (german) {
            sb.append("Ergebnis: ")
                .append(AstAriaRenderer.ariaLabel(path.improvedExpression(), locale));
        } else {
            sb.append("Result: ")
                .append(AstAriaRenderer.ariaLabel(path.improvedExpression(), locale));
        }
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isGerman(Locale locale) {
        if (locale == null) {
            return false;
        }
        return "de".equalsIgnoreCase(locale.getLanguage());
    }

}
