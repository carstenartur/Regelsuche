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
            Template.square()
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

        private static java.util.Optional<List<SymbolicRegressionSample>> supported(
            List<SymbolicRegressionSample> samples,
            java.util.function.Predicate<SymbolicRegressionSample> predicate,
            int minimumSupport
        ) {
            List<SymbolicRegressionSample> support = samples.stream().filter(predicate).toList();
            return support.size() >= minimumSupport ? java.util.Optional.of(support) : java.util.Optional.empty();
        }

        private static boolean close(double left, double right) {
            return Math.abs(left - right) <= EPSILON;
        }
    }
}
