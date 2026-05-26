package de.regelsuche.mining;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/** Internal template backend used as the default symbolic-regression baseline. */
public final class DefaultTemplateSymbolicRegressionBackend implements SymbolicRegressionBackend {
    private static final double EPSILON = 1e-9;

    @Override
    public List<SymbolicRegressionSample> extractSamples(List<SuccessfulTransformationPath> paths, int minimumSupport) {
        if (paths == null || paths.size() < minimumSupport) {
            return List.of();
        }
        List<SymbolicRegressionSample> samples = paths.stream()
            .map(DefaultTemplateSymbolicRegressionBackend::sample)
            .flatMap(java.util.Optional::stream)
            .sorted(Comparator.comparing(SymbolicRegressionSample::pathId))
            .toList();
        return samples.size() < minimumSupport ? List.of() : samples;
    }

    @Override
    public List<SymbolicRegressionFittedResult> fit(List<SymbolicRegressionSample> samples, int minimumSupport) {
        if (samples == null || samples.size() < minimumSupport) {
            return List.of();
        }
        return templateLibrary().stream()
            .map(template -> template.fit(samples, minimumSupport))
            .flatMap(java.util.Optional::stream)
            .toList();
    }

    static String format(double value) {
        if (Math.abs(value - Math.rint(value)) <= EPSILON) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private static List<Template> templateLibrary() {
        return List.of(
            Template.constant(),
            Template.shift(),
            Template.scale(),
            Template.affine(),
            Template.square(),
            Template.polynomialDegree(2),
            Template.polynomialDegree(3),
            Template.reciprocalShift(),
            Template.geometricSequence()
        );
    }

    private static java.util.Optional<SymbolicRegressionSample> sample(SuccessfulTransformationPath path) {
        if (path == null) {
            return java.util.Optional.empty();
        }
        OptionalDouble x = parseNumber(path.originalExpression());
        OptionalDouble y = parseNumber(path.targetExpression());
        if (x.isEmpty() || y.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SymbolicRegressionSample(path.id(), x.getAsDouble(), y.getAsDouble()));
    }

    private static OptionalDouble parseNumber(String expression) {
        if (expression == null || expression.isBlank()) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(Double.parseDouble(expression.trim()));
        } catch (NumberFormatException ignored) {
            return OptionalDouble.empty();
        }
    }

    private interface Template {
        java.util.Optional<SymbolicRegressionFittedResult> fit(List<SymbolicRegressionSample> samples, int minimumSupport);

        static Template constant() {
            return (samples, minimumSupport) -> {
                double c = samples.getFirst().y();
                return supported(samples, sample -> close(sample.y(), c), minimumSupport)
                    .map(support -> new SymbolicRegressionFittedResult("constant", format(c), support));
            };
        }

        static Template shift() {
            return (samples, minimumSupport) -> {
                double c = samples.getFirst().y() - samples.getFirst().x();
                return supported(samples, sample -> close(sample.y(), sample.x() + c), minimumSupport)
                    .map(support -> new SymbolicRegressionFittedResult("shift", c >= 0
                        ? "x + " + format(c)
                        : "x - " + format(-c), support));
            };
        }

        static Template scale() {
            return (samples, minimumSupport) -> {
                SymbolicRegressionSample anchor = samples.stream().filter(sample -> Math.abs(sample.x()) > EPSILON).findFirst().orElse(null);
                if (anchor == null) {
                    return java.util.Optional.empty();
                }
                double a = anchor.y() / anchor.x();
                return supported(samples, sample -> close(sample.y(), a * sample.x()), minimumSupport)
                    .map(support -> new SymbolicRegressionFittedResult("scale", format(a) + " * x", support));
            };
        }

        static Template affine() {
            return (samples, minimumSupport) -> {
                List<SymbolicRegressionSample> distinct = new ArrayList<>();
                for (SymbolicRegressionSample sample : samples) {
                    if (distinct.stream().noneMatch(existing -> close(existing.x(), sample.x()))) {
                        distinct.add(sample);
                    }
                    if (distinct.size() == 2) {
                        break;
                    }
                }
                if (distinct.size() < 2) {
                    return java.util.Optional.empty();
                }
                SymbolicRegressionSample first = distinct.get(0);
                SymbolicRegressionSample second = distinct.get(1);
                double a = (second.y() - first.y()) / (second.x() - first.x());
                double b = first.y() - a * first.x();
                return supported(samples, sample -> close(sample.y(), a * sample.x() + b), minimumSupport)
                    .map(support -> new SymbolicRegressionFittedResult("affine", format(a) + " * x"
                        + (b >= 0 ? " + " + format(b) : " - " + format(-b)), support));
            };
        }

        static Template square() {
            return (samples, minimumSupport) -> supported(samples, sample -> close(sample.y(), sample.x() * sample.x()), minimumSupport)
                .map(support -> new SymbolicRegressionFittedResult("square", "x^2", support));
        }

        static Template polynomialDegree(int degree) {
            return (samples, minimumSupport) -> {
                List<SymbolicRegressionSample> distinct = distinctByX(samples, degree + 1);
                if (distinct.size() < degree + 1) {
                    return java.util.Optional.empty();
                }
                double[] coefficients = solveVandermonde(distinct, degree);
                if (coefficients.length == 0) {
                    return java.util.Optional.empty();
                }
                return supportedWithResidual(
                    samples,
                    sample -> evaluatePolynomial(coefficients, sample.x()),
                    minimumSupport
                ).map(support -> new SymbolicRegressionFittedResult(
                    degree == 2 ? "polynomial-degree-2" : "polynomial-degree-3",
                    polynomialExpression(coefficients),
                    support.samples(),
                    support.maxResidual(),
                    support.confidence()
                ));
            };
        }

        static Template reciprocalShift() {
            return (samples, minimumSupport) -> {
                List<SymbolicRegressionSample> distinct = samples.stream()
                    .filter(sample -> Math.abs(sample.y()) > EPSILON)
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                if (distinct.size() < 2) {
                    return java.util.Optional.empty();
                }
                SymbolicRegressionSample first = distinct.get(0);
                SymbolicRegressionSample second = distinct.stream()
                    .filter(sample -> !close(sample.y(), first.y()))
                    .findFirst()
                    .orElse(null);
                if (second == null) {
                    return java.util.Optional.empty();
                }
                double b = (second.y() * second.x() - first.y() * first.x()) / (first.y() - second.y());
                double a = first.y() * (first.x() + b);
                if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(a) <= EPSILON) {
                    return java.util.Optional.empty();
                }
                return supportedWithResidual(
                    samples,
                    sample -> Math.abs(sample.x() + b) <= EPSILON ? Double.NaN : a / (sample.x() + b),
                    minimumSupport
                ).map(support -> new SymbolicRegressionFittedResult(
                    "rational-reciprocal-shift",
                    format(a) + " / (x " + (b >= 0 ? "+ " + format(b) : "- " + format(-b)) + ")",
                    support.samples(),
                    support.maxResidual(),
                    support.confidence()
                ));
            };
        }

        static Template geometricSequence() {
            return (samples, minimumSupport) -> {
                List<SymbolicRegressionSample> distinct = distinctByX(samples, 2);
                if (distinct.size() < 2 || distinct.stream().anyMatch(sample -> sample.y() <= 0)) {
                    return java.util.Optional.empty();
                }
                SymbolicRegressionSample first = distinct.get(0);
                SymbolicRegressionSample second = distinct.get(1);
                double deltaX = second.x() - first.x();
                if (Math.abs(deltaX) <= EPSILON) {
                    return java.util.Optional.empty();
                }
                double r = Math.pow(second.y() / first.y(), 1.0 / deltaX);
                double a = first.y() / Math.pow(r, first.x());
                if (!Double.isFinite(a) || !Double.isFinite(r) || a <= 0 || r <= 0) {
                    return java.util.Optional.empty();
                }
                return supportedWithResidual(
                    samples,
                    sample -> a * Math.pow(r, sample.x()),
                    minimumSupport
                ).map(support -> new SymbolicRegressionFittedResult(
                    "geometric-sequence",
                    format(a) + " * " + format(r) + "^x",
                    support.samples(),
                    support.maxResidual(),
                    support.confidence()
                ));
            };
        }

        private static java.util.Optional<List<SymbolicRegressionSample>> supported(
            List<SymbolicRegressionSample> samples,
            java.util.function.Predicate<SymbolicRegressionSample> predicate,
            int minimumSupport
        ) {
            List<SymbolicRegressionSample> support = samples.stream().filter(predicate).toList();
            return support.size() >= minimumSupport ? java.util.Optional.of(support) : java.util.Optional.empty();
        }

        private static java.util.Optional<SupportFit> supportedWithResidual(
            List<SymbolicRegressionSample> samples,
            java.util.function.ToDoubleFunction<SymbolicRegressionSample> evaluator,
            int minimumSupport
        ) {
            List<SymbolicRegressionSample> support = new ArrayList<>();
            double maxResidual = 0.0;
            for (SymbolicRegressionSample sample : samples) {
                double predicted = evaluator.applyAsDouble(sample);
                if (!Double.isFinite(predicted)) {
                    continue;
                }
                double residual = Math.abs(sample.y() - predicted);
                if (residual <= EPSILON) {
                    support.add(sample);
                    maxResidual = Math.max(maxResidual, residual);
                }
            }
            if (support.size() < minimumSupport) {
                return java.util.Optional.empty();
            }
            double confidence = (double) support.size() / (double) samples.size();
            return java.util.Optional.of(new SupportFit(support, maxResidual, confidence));
        }

        private static List<SymbolicRegressionSample> distinctByX(List<SymbolicRegressionSample> samples, int limit) {
            List<SymbolicRegressionSample> distinct = new ArrayList<>();
            for (SymbolicRegressionSample sample : samples) {
                if (distinct.stream().noneMatch(existing -> close(existing.x(), sample.x()))) {
                    distinct.add(sample);
                }
                if (distinct.size() == limit) {
                    break;
                }
            }
            return distinct;
        }

        private static double[] solveVandermonde(List<SymbolicRegressionSample> samples, int degree) {
            int n = degree + 1;
            double[][] matrix = new double[n][n + 1];
            for (int row = 0; row < n; row++) {
                double power = 1.0;
                for (int col = 0; col < n; col++) {
                    matrix[row][col] = power;
                    power *= samples.get(row).x();
                }
                matrix[row][n] = samples.get(row).y();
            }
            for (int pivot = 0; pivot < n; pivot++) {
                int best = pivot;
                for (int row = pivot + 1; row < n; row++) {
                    if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[best][pivot])) {
                        best = row;
                    }
                }
                if (Math.abs(matrix[best][pivot]) <= EPSILON) {
                    return new double[0];
                }
                double[] tmp = matrix[pivot];
                matrix[pivot] = matrix[best];
                matrix[best] = tmp;
                double divisor = matrix[pivot][pivot];
                for (int col = pivot; col <= n; col++) {
                    matrix[pivot][col] /= divisor;
                }
                for (int row = 0; row < n; row++) {
                    if (row == pivot) {
                        continue;
                    }
                    double factor = matrix[row][pivot];
                    for (int col = pivot; col <= n; col++) {
                        matrix[row][col] -= factor * matrix[pivot][col];
                    }
                }
            }
            double[] coefficients = new double[n];
            for (int i = 0; i < n; i++) {
                coefficients[i] = matrix[i][n];
            }
            return coefficients;
        }

        private static double evaluatePolynomial(double[] coefficients, double x) {
            double value = 0.0;
            for (int i = coefficients.length - 1; i >= 0; i--) {
                value = value * x + coefficients[i];
            }
            return value;
        }

        private static String polynomialExpression(double[] coefficients) {
            StringBuilder expression = new StringBuilder();
            for (int power = coefficients.length - 1; power >= 0; power--) {
                double coefficient = coefficients[power];
                if (Math.abs(coefficient) <= EPSILON) {
                    continue;
                }
                boolean negative = coefficient < 0;
                double absolute = Math.abs(coefficient);
                if (!expression.isEmpty()) {
                    expression.append(negative ? " - " : " + ");
                } else if (negative) {
                    expression.append("-");
                }
                if (power == 0) {
                    expression.append(format(absolute));
                } else {
                    if (!close(absolute, 1.0)) {
                        expression.append(format(absolute)).append(" * ");
                    }
                    expression.append("x");
                    if (power > 1) {
                        expression.append("^").append(power);
                    }
                }
            }
            return expression.isEmpty() ? "0" : expression.toString();
        }

        private static boolean close(double left, double right) {
            return Math.abs(left - right) <= EPSILON;
        }

        record SupportFit(List<SymbolicRegressionSample> samples, double maxResidual, double confidence) {
        }
    }
}
