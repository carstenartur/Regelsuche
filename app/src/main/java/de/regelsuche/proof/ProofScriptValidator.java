package de.regelsuche.proof;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates a proof script for structural completeness before it is submitted
 * to an external prover.
 *
 * <p>The following conditions are detected and reported as violations:</p>
 * <ul>
 *   <li><strong>Admitted statements</strong> — {@code sorry} (Lean) or
 *       {@code admit} (Coq/Lean) placeholders that bypass the proof
 *       obligation. These must never satisfy a required proof policy.</li>
 *   <li><strong>Unsupported placeholders</strong> — unresolved metavariables
 *       ({@code _}) or tactic holes ({@code ?goal}) left in the script.</li>
 *   <li><strong>Missing or empty script</strong> — a blank script cannot
 *       be sent to any prover.</li>
 * </ul>
 *
 * <p>Use {@link #validate(String, String)} to obtain a {@link ValidationResult}
 * before scheduling a proof job.</p>
 */
public final class ProofScriptValidator {

    private ProofScriptValidator() {
    }

    /**
     * Validates {@code script} for the given {@code tool}.
     *
     * @param script the proof artifact text.
     * @param tool   the target tool name (e.g. {@code "lean4"}, {@code "smtlib2"}).
     * @return a {@link ValidationResult} listing any detected violations.
     */
    public static ValidationResult validate(String script, String tool) {
        List<String> violations = new ArrayList<>();
        if (script == null || script.isBlank()) {
            violations.add("empty-script");
            return new ValidationResult(violations);
        }
        String effectiveTool = tool == null ? "" : tool;
        String[] lines = script.split("\n");

        for (String line : lines) {
            String stripped = line.stripLeading();
            // Skip comment lines
            if (stripped.startsWith("--") || stripped.startsWith("//") || stripped.startsWith(";")) {
                continue;
            }
            // Admitted statements
            if (containsWord(stripped, "sorry") || containsWord(stripped, "admit")) {
                violations.add("admitted-statement");
                break;
            }
        }

        // Unsupported placeholders: bare underscore or ?goal tactic holes
        for (String line : lines) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("--") || stripped.startsWith("//") || stripped.startsWith(";")) {
                continue;
            }
            if (containsWord(stripped, "?goal")) {
                violations.add("unsupported-placeholder");
                break;
            }
            if (containsWord(stripped, "_") && !"smtlib2".equals(effectiveTool)) {
                violations.add("unsupported-placeholder");
                break;
            }
        }

        return new ValidationResult(violations);
    }

    private static boolean containsWord(String text, String word) {
        int idx = 0;
        while ((idx = text.indexOf(word, idx)) >= 0) {
            boolean prefixOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean suffixOk = idx + word.length() >= text.length()
                || !Character.isLetterOrDigit(text.charAt(idx + word.length()))
                   && text.charAt(idx + word.length()) != '_';
            if (prefixOk && suffixOk) {
                return true;
            }
            idx++;
        }
        return false;
    }

    /**
     * Result of a script validation.
     *
     * @param violations list of violation codes; empty means the script is valid.
     */
    public record ValidationResult(List<String> violations) {
        public ValidationResult {
            violations = violations == null ? List.of() : List.copyOf(violations);
        }

        /** @return {@code true} if no violations were found. */
        public boolean isValid() {
            return violations.isEmpty();
        }

        /**
         * @return {@code true} if the script contains an admitted statement
         *         ({@code sorry} or {@code admit}).
         */
        public boolean hasAdmittedStatement() {
            return violations.contains("admitted-statement");
        }

        /**
         * @return {@code true} if the script contains unsupported placeholders.
         */
        public boolean hasUnsupportedPlaceholder() {
            return violations.contains("unsupported-placeholder");
        }
    }
}
