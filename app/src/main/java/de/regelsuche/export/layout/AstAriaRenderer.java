package de.regelsuche.export.layout;

import java.util.Locale;

/**
 * Stage 5 — derives a screen-reader-friendly ARIA label from a raw
 * expression string. The label is deliberately conservative: it strips
 * surrounding whitespace and replaces a small set of operator symbols
 * with verbose forms so screen readers can read the formula without
 * hitting the LaTeX backslash soup.
 *
 * <p>Two locales are fully supported: {@link Locale#GERMAN} (default,
 * uses German operator words) and {@link Locale#ENGLISH} (uses English
 * operator words). All other locales fall back to English.</p>
 *
 * <p>The output is not a full natural-language rendering — it is a
 * one-shot label sufficient to give a user-of-AT a quick read of the
 * formula. Front-end code injects it as {@code aria-label} on the host
 * element via {@code renderMathLayout(layout, host)}.</p>
 */
public final class AstAriaRenderer {

    private AstAriaRenderer() {
    }

    /**
     * Derives an ARIA label for {@code expression} using the default
     * locale (German — kept for backward compatibility).
     */
    public static String ariaLabel(String expression) {
        return ariaLabel(expression, Locale.GERMAN);
    }

    /**
     * Derives an ARIA label for {@code expression} using the given
     * {@code locale}. German and English are fully supported; all other
     * locales fall back to English.
     *
     * @param expression raw mathematical expression string (may be null)
     * @param locale     target locale for operator word substitution
     * @return screen-reader-friendly label, never null
     */
    public static String ariaLabel(String expression, Locale locale) {
        if (expression == null) {
            return "";
        }
        String t = expression.trim();
        if (t.isEmpty()) {
            return "";
        }
        boolean german = isGerman(locale);
        StringBuilder out = new StringBuilder(t.length() + 16);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (german) {
                switch (c) {
                    case '+' -> out.append(" plus ");
                    case '-' -> out.append(" minus ");
                    case '*' -> out.append(" mal ");
                    case '/' -> out.append(" geteilt durch ");
                    case '=' -> out.append(" gleich ");
                    case '<' -> out.append(" kleiner ");
                    case '>' -> out.append(" grösser ");
                    case '^' -> out.append(" hoch ");
                    case '(' -> out.append(" Klammer auf ");
                    case ')' -> out.append(" Klammer zu ");
                    default -> out.append(c);
                }
            } else {
                switch (c) {
                    case '+' -> out.append(" plus ");
                    case '-' -> out.append(" minus ");
                    case '*' -> out.append(" times ");
                    case '/' -> out.append(" divided by ");
                    case '=' -> out.append(" equals ");
                    case '<' -> out.append(" less than ");
                    case '>' -> out.append(" greater than ");
                    case '^' -> out.append(" to the power of ");
                    case '(' -> out.append(" open bracket ");
                    case ')' -> out.append(" close bracket ");
                    default -> out.append(c);
                }
            }
        }
        // Collapse runs of whitespace produced by the replacements.
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean isGerman(Locale locale) {
        if (locale == null) {
            return false;
        }
        return "de".equalsIgnoreCase(locale.getLanguage());
    }
}
