package de.regelsuche.explanation;

import java.util.List;

/**
 * Top-level structured explanation container.
 *
 * <p>An explanation carries a title and an ordered list of sections. It is a
 * pure data object with no dependency on any rendering format; concrete
 * representations (Markdown, plain text, HTML, …) are produced by
 * {@link ExplanationRenderer} implementations.
 */
public record Explanation(String title, List<ExplanationSection> sections) {
    public Explanation {
        title = title == null ? "" : title;
        sections = sections == null ? List.of() : List.copyOf(sections);
    }
}
