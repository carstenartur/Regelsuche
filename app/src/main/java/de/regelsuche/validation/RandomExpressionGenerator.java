package de.regelsuche.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RandomExpressionGenerator {
    private final Random random;
    private final List<String> variables;
    private final int maxConstantMagnitude;

    public RandomExpressionGenerator(long seed) {
        this(seed, List.of("x", "y", "z"), 5);
    }

    public RandomExpressionGenerator(long seed, List<String> variables, int maxConstantMagnitude) {
        if (variables == null || variables.isEmpty() || maxConstantMagnitude < 1) {
            throw new IllegalArgumentException("variables and maxConstantMagnitude are required");
        }
        this.random = new Random(seed);
        this.variables = List.copyOf(variables);
        this.maxConstantMagnitude = maxConstantMagnitude;
    }

    public List<String> generate(int count, int maxDepth) {
        if (count < 0 || maxDepth < 0) {
            throw new IllegalArgumentException("count and maxDepth must not be negative");
        }
        List<String> expressions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            expressions.add(generate(maxDepth));
        }
        return expressions;
    }

    public String generate(int maxDepth) {
        if (maxDepth <= 0) {
            return leaf();
        }
        return switch (random.nextInt(7)) {
            case 0 -> "(" + generate(maxDepth - 1) + " + " + generate(maxDepth - 1) + ")";
            case 1 -> "(" + generate(maxDepth - 1) + " - " + generate(maxDepth - 1) + ")";
            case 2 -> "(" + generate(maxDepth - 1) + " * " + generate(maxDepth - 1) + ")";
            case 3 -> "(" + generate(maxDepth - 1) + " / " + nonZeroLeaf() + ")";
            case 4 -> "(" + generate(maxDepth - 1) + "^" + (2 + random.nextInt(3)) + ")";
            default -> leaf();
        };
    }

    public List<String> polynomialExamples(int min, int max, int degree, List<String> variableNames) {
        if (degree < 1 || variableNames == null || variableNames.isEmpty()) {
            throw new IllegalArgumentException("degree and variableNames are required");
        }
        List<String> examples = new ArrayList<>();
        for (String variable : variableNames) {
            for (int coefficient = min; coefficient <= max; coefficient++) {
                if (coefficient == 0) {
                    continue;
                }
                for (int constant = min; constant <= max; constant++) {
                    examples.add(polynomial(variable, coefficient, constant, degree));
                }
            }
        }
        return examples;
    }

    private String polynomial(String variable, int coefficient, int constant, int degree) {
        StringBuilder builder = new StringBuilder();
        for (int exponent = degree; exponent >= 2; exponent--) {
            if (!builder.isEmpty()) {
                builder.append(" + ");
            }
            builder.append(variable).append("^").append(exponent);
        }
        if (!builder.isEmpty()) {
            builder.append(" + ");
        }
        builder.append(coefficient).append("*").append(variable).append(" + ").append(constant);
        return builder.toString();
    }

    private String leaf() {
        if (random.nextBoolean()) {
            return variables.get(random.nextInt(variables.size()));
        }
        return Integer.toString(randomNonZeroInt());
    }

    private String nonZeroLeaf() {
        return random.nextBoolean() ? variables.get(random.nextInt(variables.size())) + " + " + (1 + random.nextInt(maxConstantMagnitude)) : Integer.toString(randomNonZeroInt());
    }

    private int randomNonZeroInt() {
        int value = random.nextInt(maxConstantMagnitude * 2 + 1) - maxConstantMagnitude;
        return value == 0 ? 1 : value;
    }
}
