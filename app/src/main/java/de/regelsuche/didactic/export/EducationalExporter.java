package de.regelsuche.didactic.export;

import de.regelsuche.didactic.SymbolDiff;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import java.util.List;
import java.util.Objects;

/**
 * Renders one {@link DiscoveredTransformation} into the three
 * education-oriented Markdown views requested by the didactic spec:
 * <ul>
 *   <li>{@link #worksheet(DiscoveredTransformation) Arbeitsblatt} —
 *       prompt only, blank lines for the learner to fill in.</li>
 *   <li>{@link #solution(DiscoveredTransformation) Musterlösung} —
 *       full step-by-step derivation with short reasons.</li>
 *   <li>{@link #teacherMode(DiscoveredTransformation) Lehrermodus} —
 *       solution plus expected misconceptions and pedagogical notes,
 *       including a token-level {@link SymbolDiff} per step.</li>
 * </ul>
 *
 * <p>Markdown was chosen so the same output can be rendered to HTML or
 * (with pandoc) to PDF without adding any heavyweight dependency.</p>
 */
public final class EducationalExporter {

    public String worksheet(DiscoveredTransformation derivation) {
        Objects.requireNonNull(derivation, "derivation");
        StringBuilder sb = new StringBuilder();
        sb.append("# Arbeitsblatt\n\n");
        sb.append("**Aufgabe:** Vereinfache bzw. forme um.\n\n");
        sb.append("Ausgangsausdruck:\n\n");
        sb.append("```\n").append(derivation.originalExpression()).append("\n```\n\n");
        sb.append("Trage deine Zwischenschritte ein:\n\n");
        int steps = Math.max(1, derivation.steps().size());
        for (int i = 0; i < steps; i++) {
            sb.append((i + 1)).append(". ").append("________________________________\n");
        }
        sb.append("\nEndergebnis:\n\n");
        sb.append("```\n").append("________________________________").append("\n```\n");
        return sb.toString();
    }

    public String solution(DiscoveredTransformation derivation) {
        Objects.requireNonNull(derivation, "derivation");
        StringBuilder sb = new StringBuilder();
        sb.append("# Musterlösung\n\n");
        sb.append("**Aufgabe:** ").append(derivation.originalExpression()).append("\n\n");
        sb.append("**Ergebnis:** ").append(derivation.improvedExpression()).append("\n\n");
        sb.append("## Schritte\n\n");
        List<TransformationStep> steps = derivation.steps();
        for (int i = 0; i < steps.size(); i++) {
            TransformationStep step = steps.get(i);
            sb.append((i + 1)).append(". `").append(step.beforeExpression())
              .append("` → `").append(step.afterExpression()).append("`");
            String reason = step.explanation();
            if (reason != null && !reason.isBlank()) {
                sb.append("  \n   *").append(reason).append("*");
            } else {
                sb.append("  \n   *Regel: ").append(step.ruleId()).append("*");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public String teacherMode(DiscoveredTransformation derivation) {
        Objects.requireNonNull(derivation, "derivation");
        StringBuilder sb = new StringBuilder();
        sb.append("# Lehrermodus\n\n");
        sb.append("**Aufgabe:** ").append(derivation.originalExpression()).append("\n\n");
        sb.append("**Erwartetes Ergebnis:** ").append(derivation.improvedExpression()).append("\n\n");
        sb.append("**Validierungsstatus:** ").append(derivation.validationStatus().name()).append("\n\n");
        sb.append("## Schritt-für-Schritt (mit Symbol-Diff)\n\n");
        List<TransformationStep> steps = derivation.steps();
        for (int i = 0; i < steps.size(); i++) {
            TransformationStep step = steps.get(i);
            sb.append("### Schritt ").append(i + 1).append("\n\n");
            sb.append("- vorher: `").append(step.beforeExpression()).append("`\n");
            sb.append("- nachher: `").append(step.afterExpression()).append("`\n");
            sb.append("- Regel: `").append(step.ruleId()).append("` (").append(step.ruleKind().name()).append(")\n");
            String reason = step.explanation();
            if (reason != null && !reason.isBlank()) {
                sb.append("- Begründung: ").append(reason).append("\n");
            }
            sb.append("- Diff: ").append(formatDiff(step.beforeExpression(), step.afterExpression())).append("\n\n");
        }
        sb.append("## Pädagogische Hinweise\n\n");
        sb.append("- Achte auf typische Fehlvorstellungen (siehe `/api/didactic/misconceptions`).\n");
        sb.append("- Biete bei Bedarf gestufte Hinweise an (`/api/didactic/hint/{pathId}`).\n");
        return sb.toString();
    }

    private static String formatDiff(String before, String after) {
        List<SymbolDiff.Token> tokens = SymbolDiff.diff(before, after);
        StringBuilder sb = new StringBuilder();
        for (SymbolDiff.Token token : tokens) {
            switch (token.change()) {
                case UNCHANGED -> sb.append(token.text());
                case REMOVED   -> sb.append("~~").append(token.text()).append("~~");
                case ADDED     -> sb.append("**").append(token.text()).append("**");
            }
            sb.append(' ');
        }
        return sb.toString().trim();
    }
}
