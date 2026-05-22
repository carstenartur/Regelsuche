package de.regelsuche.export.layout;

/**
 * Stage 5 — derives a screen-reader-friendly ARIA label from a raw
 * expression string. The label is deliberately conservative: it strips
 * surrounding whitespace and replaces a small set of operator symbols
 * with English/German verbose forms ({@code +} → {@code " plus "},
 * etc.) so screen readers can read the formula without hitting the
 * LaTeX backslash soup.
 *
 * <p>The output is not a full natural-language rendering — it is a
 * one-shot label sufficient to give a user-of-AT a quick read of the
 * formula. Front-end code injects it as {@code aria-label} on the host
 * element via {@code renderMathLayout(layout, host)}.</p>
 */
public final class AstAriaRenderer {

    private AstAriaRenderer() {
    }

    public static String ariaLabel(String expression) {
        if (expression == null) {
            return "";
        }
        String t = expression.trim();
        if (t.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(t.length() + 16);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            switch (c) {
                case '+' -> out.append(" plus ");
                case '-' -> out.append(" minus ");
                case '*' -> out.append(" mal ");
                case '/' -> out.append(" geteilt durch ");
                case '=' -> out.append(" gleich ");
                case '<' -> out.append(" kleiner ");
                case '>' -> out.append(" grösser ");
                case '^' -> out.append(" hoch ");
                case '(' -> out.append(" auf ");
                case ')' -> out.append(" zu ");
                default -> out.append(c);
            }
        }
        // Collapse runs of whitespace produced by the replacements.
        return out.toString().replaceAll("\\s+", " ").trim();
    }
}
