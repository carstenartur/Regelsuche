package de.regelsuche.didactic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Token-level diff between two expressions for the didactic replay view
 * (spec item 5: "Symbol-Diff — visualisiere, was sich geändert hat,
 * z. B. farbig markiert").
 *
 * <p>The diff is intentionally lightweight: we tokenise both expressions
 * with the same rules used by the project's parser (digits, identifiers,
 * one-character operators / parentheses / comparators) and then compute
 * the longest common subsequence (LCS) of tokens. Tokens that are not
 * on the LCS are marked as {@code REMOVED} (only in the left side) or
 * {@code ADDED} (only in the right side); the remaining tokens are
 * {@code UNCHANGED}. The result is suitable for direct rendering in
 * HTML with three CSS classes.</p>
 */
public final class SymbolDiff {

    /** How a single token should be rendered in the replay view. */
    public enum Change { UNCHANGED, ADDED, REMOVED }

    /** One token plus its diff status. */
    public record Token(String text, Change change) {
        public Token {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(change, "change");
        }
    }

    private SymbolDiff() {
    }

    /**
     * @return the diff as a flat token sequence in display order. A
     *         {@link Change#REMOVED} token represents the {@code before}
     *         side; a {@link Change#ADDED} token represents the
     *         {@code after} side; {@link Change#UNCHANGED} tokens are
     *         identical between the two sides.
     */
    public static List<Token> diff(String before, String after) {
        List<String> beforeTokens = tokenize(Objects.requireNonNullElse(before, ""));
        List<String> afterTokens  = tokenize(Objects.requireNonNullElse(after, ""));
        int[][] lcs = lcsTable(beforeTokens, afterTokens);
        List<Token> tokens = new ArrayList<>(beforeTokens.size() + afterTokens.size());
        emit(beforeTokens, afterTokens, lcs, beforeTokens.size(), afterTokens.size(), tokens);
        return tokens;
    }

    /**
     * @return the subset of {@link #diff(String, String)} that represents
     *         a change (i.e. ADDED or REMOVED). Useful for highlight-only
     *         renderers.
     */
    public static List<Token> changes(String before, String after) {
        List<Token> all = diff(before, after);
        List<Token> filtered = new ArrayList<>();
        for (Token token : all) {
            if (token.change() != Change.UNCHANGED) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    // -------- tokenisation --------

    /** Match the project's parser: digits, identifiers, single-char ops, comparators. */
    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || c == '.') {
                int start = i;
                while (i < n && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(input.substring(start, i));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(input.substring(start, i));
                continue;
            }
            // Two-character comparators
            if (i + 1 < n) {
                String two = input.substring(i, i + 2);
                if (two.equals("<=") || two.equals(">=") || two.equals("==") || two.equals("!=")) {
                    tokens.add(two);
                    i += 2;
                    continue;
                }
            }
            tokens.add(String.valueOf(c));
            i++;
        }
        return tokens;
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        int[][] table = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    table[i][j] = table[i - 1][j - 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i - 1][j], table[i][j - 1]);
                }
            }
        }
        return table;
    }

    private static void emit(List<String> a, List<String> b, int[][] table,
                             int i, int j, List<Token> sink) {
        if (i == 0 && j == 0) {
            return;
        }
        if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
            emit(a, b, table, i - 1, j - 1, sink);
            sink.add(new Token(a.get(i - 1), Change.UNCHANGED));
        } else if (j > 0 && (i == 0 || table[i][j - 1] >= table[i - 1][j])) {
            emit(a, b, table, i, j - 1, sink);
            sink.add(new Token(b.get(j - 1), Change.ADDED));
        } else {
            emit(a, b, table, i - 1, j, sink);
            sink.add(new Token(a.get(i - 1), Change.REMOVED));
        }
    }
}
