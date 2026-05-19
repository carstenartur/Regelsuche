package de.regelsuche.scoring;

import de.regelsuche.algebra.QuadraticAnalyzer;

public class ExpressionScorer {
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
        return new ExpressionScore(compact.length(), nodes, operators, maxNesting, bonus);
    }

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
