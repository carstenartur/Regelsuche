package de.regelsuche.explanation;

/**
 * Renders an {@link Explanation} as plain text (no Markdown markup).
 *
 * <p>This renderer demonstrates that the explanation model is not coupled to
 * any particular output format. It is intentionally minimal and can serve as a
 * basis for accessibility readers, terminal output, or test assertions.
 */
public final class PlainTextExplanationRenderer implements ExplanationRenderer {

    @Override
    public String render(Explanation explanation) {
        StringBuilder out = new StringBuilder();
        out.append(explanation.title()).append('\n');
        out.append("=".repeat(Math.min(explanation.title().length(), 60))).append('\n');
        for (ExplanationSection section : explanation.sections()) {
            out.append('\n').append(section.title()).append('\n');
            out.append("-".repeat(Math.min(section.title().length(), 40))).append('\n');
            for (ExplanationFact fact : section.facts()) {
                out.append(fact.key()).append(": ").append(fact.value()).append('\n');
            }
            for (ExplanationMetric metric : section.metrics()) {
                out.append(metric.name()).append(": ").append(metric.count()).append('\n');
            }
            for (ExplanationWarning warning : section.warnings()) {
                out.append("WARNING: ").append(warning.message()).append('\n');
            }
        }
        return out.toString();
    }
}
