package de.regelsuche.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 helper: tiny token-level diff over LaTeX strings that produces
 * {@code [start, length]} change spans suitable for colour-coded highlighting
 * via {@link MathPresentation#alignedDerivationLatexWithDiff(java.util.List)}.
 *
 * <p>The tokenizer is deliberately simple: it slices a LaTeX string into
 * three categories of tokens:
 * <ul>
 *   <li>backslash-introduced macros such as {@code \frac}, {@code \cdot},
 *       {@code \begin}, including the alphabetic name after the backslash;</li>
 *   <li>maximal runs of alphanumerics (variable / number identifiers);</li>
 *   <li>any other single character (punctuation, braces, operators).</li>
 * </ul>
 * The output is a Longest-Common-Subsequence based diff: tokens that are
 * present in {@code from} but not matched in {@code to} (and vice versa)
 * become change spans. This is intentionally rough — its job is to
 * <em>highlight what moved between consecutive replay steps</em>, not to
 * compute an authoritative semantic diff.</p>
 *
 * <p>All offsets are in {@code char} units over the original input strings
 * (which is also what {@link String#substring(int, int)} consumes), so
 * downstream wrappers can splice into the LaTeX text without any
 * code-point gymnastics.</p>
 */
public final class MathDiff {

    private MathDiff() {
    }

    /** Diff result: change spans over the {@code from} and {@code to} strings. */
    public record Result(List<int[]> fromSpans, List<int[]> toSpans) {
        public Result {
            fromSpans = fromSpans == null ? List.of() : List.copyOf(fromSpans);
            toSpans = toSpans == null ? List.of() : List.copyOf(toSpans);
        }
    }

    /**
     * Returns the change spans of {@code from} vs {@code to}. If either
     * input is {@code null} or equal to the other, the corresponding
     * spans list is empty (no diff to highlight).
     */
    public static Result diffSpans(String from, String to) {
        if (from == null) {
            from = "";
        }
        if (to == null) {
            to = "";
        }
        if (from.equals(to)) {
            return new Result(List.of(), List.of());
        }
        List<Token> fromTokens = tokenize(from);
        List<Token> toTokens = tokenize(to);
        boolean[] keepFrom = new boolean[fromTokens.size()];
        boolean[] keepTo = new boolean[toTokens.size()];
        markLcs(fromTokens, toTokens, keepFrom, keepTo);
        // Anything not kept is "changed". Coalesce adjacent unchanged
        // tokens into a single contiguous span over the original string.
        List<int[]> fromChanges = collectChangedSpans(fromTokens, keepFrom);
        List<int[]> toChanges = collectChangedSpans(toTokens, keepTo);
        return new Result(fromChanges, toChanges);
    }

    private static void markLcs(List<Token> a, List<Token> b, boolean[] keepA, boolean[] keepB) {
        int n = a.size();
        int m = b.size();
        if (n == 0 || m == 0) {
            return;
        }
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).text.equals(b.get(j).text)) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).text.equals(b.get(j).text)) {
                keepA[i] = true;
                keepB[j] = true;
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++;
            } else {
                j++;
            }
        }
    }

    private static List<int[]> collectChangedSpans(List<Token> tokens, boolean[] keep) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (keep[i]) {
                i++;
                continue;
            }
            int start = tokens.get(i).start;
            int end = tokens.get(i).end;
            int j = i + 1;
            while (j < tokens.size() && !keep[j]) {
                end = tokens.get(j).end;
                j++;
            }
            out.add(new int[] { start, end - start });
            i = j;
        }
        return out;
    }

    static List<Token> tokenize(String s) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                int j = i + 1;
                if (j < n && Character.isLetter(s.charAt(j))) {
                    while (j < n && Character.isLetter(s.charAt(j))) {
                        j++;
                    }
                } else if (j < n) {
                    // Escaped single non-letter character (e.g. `\\`, `\{`).
                    j++;
                }
                out.add(new Token(s.substring(i, j), i, j));
                i = j;
            } else if (Character.isLetterOrDigit(c)) {
                int j = i + 1;
                while (j < n && Character.isLetterOrDigit(s.charAt(j))) {
                    j++;
                }
                out.add(new Token(s.substring(i, j), i, j));
                i = j;
            } else if (Character.isWhitespace(c)) {
                // Skip whitespace — it never produces a meaningful diff
                // span but its position is still preserved by surrounding
                // tokens' start/end offsets.
                i++;
            } else {
                out.add(new Token(String.valueOf(c), i, i + 1));
                i++;
            }
        }
        return out;
    }

    static record Token(String text, int start, int end) {
    }
}
