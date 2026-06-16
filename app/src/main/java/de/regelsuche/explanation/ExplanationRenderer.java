package de.regelsuche.explanation;

/**
 * Produces a text representation of an {@link Explanation}.
 *
 * <p>Implementations must not depend on fachliche (domain) components; all
 * domain-specific data is encoded in the {@link Explanation} object graph.
 * The desired dependency direction is:
 * <pre>
 *   search / discovery / moves
 *   ↓
 *   explanation model
 *   ↓
 *   renderer
 * </pre>
 */
public interface ExplanationRenderer {
    /**
     * Renders the given explanation into a text representation.
     *
     * @param explanation the explanation to render
     * @return rendered text (format is implementation-defined)
     */
    String render(Explanation explanation);
}
