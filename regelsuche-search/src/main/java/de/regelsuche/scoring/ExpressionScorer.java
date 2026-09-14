package de.regelsuche.scoring;

import de.regelsuche.algebra.QuadraticAnalyzer;
import de.regelsuche.symbol.SymbolId;
import java.util.regex.Pattern;

public class ExpressionScorer {
    /** Scoped IDs count as single variable tokens; ordinary text retains its historical cost. */
    public static final String SCOPED_SYMBOL_METRIC_REVISION = "regelsuche.scoped-symbol-text-cost/v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final SymbolOverhead NO_OVERHEAD = new SymbolOverhead(0, 0);

    public ExpressionScore score(String expression) {
        String compact = expression.replaceAll("\\s+", "");
        int operators = 0;
        int nodes = 0;
        int nesting = 0;
        int maxNesting = 0;
        for (char c : compact.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                nodes++;
            }
            if (c == '+' || c == '-' || c == '*' || c == '/' || c == '^' || c == '=') {
                operators++;
                nodes++;
            }
            if (c == '(') {
                nesting++;
                maxNesting = Math.max(maxNesting, nesting);
            } else if (c == ')') {
                nesting = Math.max(0, nesting - 1);
            }
        }
        int bonus = recognizedPatternBonus(compact);
        SymbolOverhead overhead = symbolOverhead(compact);
        return new ExpressionScore(compact.length() - overhead.characters(), nodes - overhead.letterDigits(),
            operators, maxNesting, bonus, ScoreRevision.CURRENT);
    }

    /**
     * Textual length for heuristics, not serialized byte size. Each scoped variable
     * contributes one character; function names, whitespace and other text retain
     * their length. The input itself and all symbol identities remain unchanged.
     */
    public static int identityIndependentLength(String expression) {
        return expression.length() - symbolOverhead(expression).characters();
    }

    private static SymbolOverhead symbolOverhead(String expression) {
        if (!expression.contains(SymbolId.IDENTIFIER_PREFIX)) return NO_OVERHEAD;
        int characters = 0;
        int letterDigits = 0;
        var tokens = IDENTIFIER.matcher(expression);
        while (tokens.find()) {
            String token = tokens.group();
            if (!token.startsWith(SymbolId.IDENTIFIER_PREFIX)) continue;
            int next = tokens.end();
            while (next < expression.length() && Character.isWhitespace(expression.charAt(next))) next++;
            // Function names have their existing operator role, not variable identity.
            if (next < expression.length() && expression.charAt(next) == '(') continue;
            SymbolId.fromIdentifier(token); // Never hide malformed reserved IDs as cheap variables.
            characters += token.length() - 1;
            letterDigits += (int) token.chars().filter(Character::isLetterOrDigit).count() - 1;
        }
        return new SymbolOverhead(characters, letterDigits);
    }

    private record SymbolOverhead(int characters, int letterDigits) { }

    private int recognizedPatternBonus(String compact) {
        if (QuadraticAnalyzer.analyzePerfectSquare(compact).isPresent()) {
            return 12;
        }
        if (QuadraticAnalyzer.analyzeDifferenceProduct(compact).isPresent()) {
            return 10;
        }
        if (QuadraticAnalyzer.analyzePolynomial(compact)
            .filter(coefficients -> coefficients.isMonic() && coefficients.linear() == 0 && coefficients.constant() < 0)
            .isPresent()) {
            return 5;
        }
        if (compact.contains(")^2-") || compact.contains(")^2+")) {
            return 6;
        }
        return 0;
    }
}
