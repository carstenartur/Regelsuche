package de.regelsuche.explanation;

import java.util.List;

/**
 * Renders an {@link Explanation} as Markdown text.
 *
 * <p>This is the only class in the explanation pipeline that is allowed to know
 * about Markdown syntax. Fachliche (domain) components must not depend on this
 * class; they interact with the {@link ExplanationRenderer} interface or produce
 * plain {@link Explanation} objects.
 */
public final class MarkdownExplanationRenderer implements ExplanationRenderer {

    @Override
    public String render(Explanation explanation) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(explanation.title()).append("\n\n");
        for (ExplanationSection section : explanation.sections()) {
            out.append("## ").append(section.title()).append("\n\n");
            for (ExplanationFact fact : section.facts()) {
                out.append("- **").append(fact.key()).append(":** ").append(fact.value()).append('\n');
            }
            for (ExplanationMetric metric : section.metrics()) {
                out.append("- **").append(metric.name()).append(":** ").append(metric.count()).append('\n');
            }
            for (ExplanationWarning warning : section.warnings()) {
                out.append("> ⚠ ").append(warning.message()).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Joins a list of reason strings with {@code "; "}, or returns the given
     * {@code fallback} if the list is empty.
     *
     * <p>This helper is used by discovery code that still expresses reasons as
     * plain strings before they have been fully migrated to
     * {@link TransformationExplanation}.
     *
     * @param reasons  list of reason strings (may be empty)
     * @param fallback text to return when {@code reasons} is empty
     * @return joined reasons or fallback
     */
    public String renderReasons(List<String> reasons, String fallback) {
        return reasons == null || reasons.isEmpty() ? fallback : String.join("; ", reasons);
    }
}
