package de.regelsuche.algebra;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuadraticAnalyzer {
    private static final Pattern SQUARE = Pattern.compile("^([a-zA-Z][a-zA-Z0-9_]*)\\^2$");
    private static final Pattern COEFFICIENT_SQUARE = Pattern.compile("^([+-]?\\d+)\\*([a-zA-Z][a-zA-Z0-9_]*)\\^2$");
    private static final Pattern LINEAR = Pattern.compile("^([+-]?\\d+)\\*([a-zA-Z][a-zA-Z0-9_]*)$");
    private static final Pattern VARIABLE = Pattern.compile("^([a-zA-Z][a-zA-Z0-9_]*)$");
    private static final Pattern PERFECT_SQUARE = Pattern.compile("^\\(([a-zA-Z][a-zA-Z0-9_]*)([+-]\\d+)\\)\\^2$");
    private static final Pattern COMPLETION = Pattern.compile("^\\(([a-zA-Z][a-zA-Z0-9_]*)([+-]\\d+)\\)\\^2([+-]\\d+)$");
    private static final Pattern DIFFERENCE_PRODUCT = Pattern.compile(
        "^\\(([a-zA-Z][a-zA-Z0-9_]*)([+-]\\d+)\\)\\*\\(\\1([+-]\\d+)\\)$"
    );

    private QuadraticAnalyzer() {
    }

    public static Optional<QuadraticCoefficients> analyze(String expression) {
        String compact = canonicalInput(expression);
        Optional<QuadraticCoefficients> square = analyzePerfectSquare(compact);
        if (square.isPresent()) {
            return square;
        }
        Optional<QuadraticCoefficients> completion = analyzeCompletion(compact);
        if (completion.isPresent()) {
            return completion;
        }
        Optional<QuadraticCoefficients> product = analyzeDifferenceProduct(compact);
        if (product.isPresent()) {
            return product;
        }
        return analyzePolynomial(compact);
    }

    public static Optional<QuadraticCoefficients> analyzePolynomial(String expression) {
        String compact = canonicalInput(expression);
        if (compact.isBlank()) {
            return Optional.empty();
        }

        int quadratic = 0;
        int linear = 0;
        int constant = 0;
        String variable = null;
        try {
            for (String term : splitTerms(compact)) {
                if (term.isBlank()) {
                    continue;
                }
                Matcher coefficientSquare = COEFFICIENT_SQUARE.matcher(term);
                Matcher square = SQUARE.matcher(term);
                Matcher linearTerm = LINEAR.matcher(term);
                Matcher variableTerm = VARIABLE.matcher(term);
                if (coefficientSquare.matches()) {
                    quadratic += Integer.parseInt(coefficientSquare.group(1));
                    variable = compatible(variable, coefficientSquare.group(2));
                } else if (square.matches()) {
                    quadratic += 1;
                    variable = compatible(variable, square.group(1));
                } else if (linearTerm.matches()) {
                    linear += Integer.parseInt(linearTerm.group(1));
                    variable = compatible(variable, linearTerm.group(2));
                } else if (variableTerm.matches()) {
                    linear += 1;
                    variable = compatible(variable, variableTerm.group(1));
                } else {
                    constant += Integer.parseInt(term);
                }
            }
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (variable == null || quadratic == 0) {
            return Optional.empty();
        }
        return Optional.of(new QuadraticCoefficients(quadratic, linear, constant, variable));
    }

    public static Optional<QuadraticCoefficients> analyzePerfectSquare(String expression) {
        Matcher matcher = PERFECT_SQUARE.matcher(canonicalInput(expression));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(matcher.group(2));
        return Optional.of(new QuadraticCoefficients(1, 2 * value, value * value, matcher.group(1)));
    }

    public static Optional<QuadraticCoefficients> analyzeDifferenceProduct(String expression) {
        Matcher matcher = DIFFERENCE_PRODUCT.matcher(canonicalInput(expression));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int left = Integer.parseInt(matcher.group(2));
        int right = Integer.parseInt(matcher.group(3));
        if (left + right != 0) {
            return Optional.empty();
        }
        return Optional.of(new QuadraticCoefficients(1, 0, left * right, matcher.group(1)));
    }

    public static Optional<QuadraticCoefficients> analyzeCompletion(String expression) {
        Matcher matcher = COMPLETION.matcher(canonicalInput(expression));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int value = Integer.parseInt(matcher.group(2));
        int adjustment = Integer.parseInt(matcher.group(3));
        return Optional.of(new QuadraticCoefficients(1, 2 * value, value * value + adjustment, matcher.group(1)));
    }

    public static String formatPolynomial(QuadraticCoefficients coefficients) {
        String x = coefficients.variable();
        StringBuilder builder = new StringBuilder();
        appendTerm(builder, coefficients.quadratic(), x + "^2");
        appendTerm(builder, coefficients.linear(), x);
        appendTerm(builder, coefficients.constant(), "");
        return builder.toString().trim();
    }

    public static String formatPerfectSquare(String variable, int value) {
        if (value >= 0) {
            return "(" + variable + " + " + value + ")^2";
        }
        return "(" + variable + " - " + Math.abs(value) + ")^2";
    }

    public static String formatCompletion(String variable, int value) {
        return formatPerfectSquare(variable, value) + " - " + (value * value);
    }

    public static String canonicalInput(String expression) {
        return expression.replaceAll("\\s+", "")
            .replace("**", "^")
            .replace(")(", ")*(")
            .replaceAll("(?<=[0-9])(?=[a-zA-Z])", "*")
            .replaceAll("(?<=[a-zA-Z0-9_])(?=\\()", "*");
    }

    private static String compatible(String current, String next) {
        if (current == null) {
            return next;
        }
        if (!current.equals(next)) {
            throw new IllegalArgumentException("Only one variable is supported per quadratic expression");
        }
        return current;
    }

    private static String[] splitTerms(String compact) {
        String normalized = compact.charAt(0) == '-' ? compact : "+" + compact;
        return normalized.replace("-", "+-").split("\\+");
    }

    private static void appendTerm(StringBuilder builder, int coefficient, String base) {
        if (coefficient == 0) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(coefficient > 0 ? " + " : " - ");
        } else if (coefficient < 0) {
            builder.append("-");
        }

        int abs = Math.abs(coefficient);
        if (base.isBlank()) {
            builder.append(abs);
        } else if (abs == 1) {
            builder.append(base);
        } else {
            builder.append(abs).append("*").append(base);
        }
    }
}
