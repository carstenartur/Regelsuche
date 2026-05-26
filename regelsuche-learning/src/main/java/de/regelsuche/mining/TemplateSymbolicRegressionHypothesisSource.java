package de.regelsuche.mining;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/** Small internal template-based symbolic-regression source; outputs evidence-only hypotheses. */
public final class TemplateSymbolicRegressionHypothesisSource implements SymbolicRegressionHypothesisSource {
    private static final double EPSILON = 1e-9;
    private final boolean enabled;
    private final int minimumSupport;

    public TemplateSymbolicRegressionHypothesisSource(boolean enabled, int minimumSupport) {
        this.enabled = enabled;
        this.minimumSupport = Math.max(2, minimumSupport);
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public List<HypothesisCandidate> propose(List<SuccessfulTransformationPath> paths) {
        if (!enabled || paths == null || paths.size() < minimumSupport) {
            return List.of();
        }
        List<Sample> samples = paths.stream()
            .map(TemplateSymbolicRegressionHypothesisSource::sample)
            .flatMap(java.util.Optional::stream)
            .sorted(Comparator.comparing(Sample::pathId))
            .toList();
        if (samples.size() < minimumSupport) {
            return List.of();
        }
        return templateLibrary().stream()
            .map(template -> template.fit(samples, minimumSupport))
            .flatMap(java.util.Optional::stream)
            .map(this::hypothesis)
            .toList();
    }

    private HypothesisCandidate hypothesis(FittedTemplate fitted) {
        List<String> support = fitted.samples().stream().map(Sample::pathId).toList();
        List<HypothesisCandidate.ExpressionPair> witnesses = fitted.samples().stream()
            .map(sample -> new HypothesisCandidate.ExpressionPair(format(sample.x()), format(sample.y())))
            .toList();
        return new HypothesisCandidate(
            "template-symreg-" + Integer.toHexString((fitted.name() + fitted.expression()).hashCode()),
            "x",
            fitted.expression(),
            support,
            witnesses,
            List.of("symbolic-regression-evidence-only", "template:" + fitted.name()),
            0.0,
            CandidateProofStatus.OBSERVED,
            null,
            List.of("fitted-by-template-library", "sample-count=" + fitted.samples().size()),
            Map.of("symbolicRegression", List.of("internal-template", "evidence-only")),
            Instant.EPOCH
        );
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

    private static java.util.Optional<Sample> sample(SuccessfulTransformationPath path) {
        if (path == null) {
            return java.util.Optional.empty();
        }
        OptionalDouble x = parseNumber(path.originalExpression());
        OptionalDouble y = parseNumber(path.targetExpression());
        if (x.isEmpty() || y.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Sample(path.id(), x.getAsDouble(), y.getAsDouble()));
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

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) <= EPSILON) {
            return Long.toString(Math.round(value));
        }
        return Double.toString(value);
    }

    private record Sample(String pathId, double x, double y) {
    }

    private record FittedTemplate(String name, String expression, List<Sample> samples) {
    }

    private interface Template {
        java.util.Optional<FittedTemplate> fit(List<Sample> samples, int minimumSupport);

        static Template constant() {
            return (samples, minimumSupport) -> {
                double c = samples.getFirst().y();
                return supported(samples, sample -> close(sample.y(), c), minimumSupport)
                    .map(support -> new FittedTemplate("constant", format(c), support));
            };
        }

        static Template shift() {
            return (samples, minimumSupport) -> {
                double c = samples.getFirst().y() - samples.getFirst().x();
                return supported(samples, sample -> close(sample.y(), sample.x() + c), minimumSupport)
                    .map(support -> new FittedTemplate("shift", c >= 0
                        ? "x + " + format(c)
                        : "x - " + format(-c), support));
            };
        }

        static Template scale() {
            return (samples, minimumSupport) -> {
                Sample anchor = samples.stream().filter(sample -> Math.abs(sample.x()) > EPSILON).findFirst().orElse(null);
                if (anchor == null) {
                    return java.util.Optional.empty();
                }
                double a = anchor.y() / anchor.x();
                return supported(samples, sample -> close(sample.y(), a * sample.x()), minimumSupport)
                    .map(support -> new FittedTemplate("scale", format(a) + " * x", support));
            };
        }

        static Template affine() {
            return (samples, minimumSupport) -> {
                List<Sample> distinct = new ArrayList<>();
                for (Sample sample : samples) {
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
                Sample first = distinct.get(0);
                Sample second = distinct.get(1);
                double a = (second.y() - first.y()) / (second.x() - first.x());
                double b = first.y() - a * first.x();
                return supported(samples, sample -> close(sample.y(), a * sample.x() + b), minimumSupport)
                    .map(support -> new FittedTemplate("affine", format(a) + " * x"
                        + (b >= 0 ? " + " + format(b) : " - " + format(-b)), support));
            };
        }

        static Template square() {
            return (samples, minimumSupport) -> supported(samples, sample -> close(sample.y(), sample.x() * sample.x()), minimumSupport)
                .map(support -> new FittedTemplate("square", "x^2", support));
        }

        private static java.util.Optional<List<Sample>> supported(
            List<Sample> samples,
            java.util.function.Predicate<Sample> predicate,
            int minimumSupport
        ) {
            List<Sample> support = samples.stream().filter(predicate).toList();
            return support.size() >= minimumSupport ? java.util.Optional.of(support) : java.util.Optional.empty();
        }

        private static boolean close(double left, double right) {
            return Math.abs(left - right) <= EPSILON;
        }
    }
}
