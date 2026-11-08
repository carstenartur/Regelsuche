package de.regelsuche.export;

import de.regelsuche.literature.RuleLiteratureCatalog.Citation;
import de.regelsuche.literature.RuleLiteratureCatalog.Numbering;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

final class LiteratureExport {
    private LiteratureExport() { }

    static String markdownCitations(List<Citation> citations) {
        if (citations.isEmpty()) return "";
        return " · " + labels(citations, LiteratureExport::markdownText);
    }

    static String latexCitations(List<Citation> citations) {
        if (citations.isEmpty()) return "";
        return " · " + labels(citations, LiteratureExport::latexText);
    }

    private static String labels(List<Citation> citations, UnaryOperator<String> escape) {
        return citations.stream().map(citation -> citation.role().label() + " [" + citation.number() + "] ("
            + citation.relation().label() + ")"
            + (citation.assumptions().isEmpty() ? "" : " · Voraussetzungen " + escape.apply(String.join(", ", citation.assumptions())))
            + " · " + escape.apply(citation.rationale()))
            .collect(Collectors.joining(", "));
    }

    static String markdownBibliography(Numbering numbering) {
        var sources = numbering.bibliography();
        if (sources.isEmpty()) return "";
        StringBuilder result = new StringBuilder("\n\n## Quellen\n\n");
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            result.append(index + 1).append(". ").append(markdownText(source.description()))
                .append(". [Fundstelle](<").append(source.url()).append(">)\n");
        }
        return result.toString();
    }

    static String latexBibliography(Numbering numbering) {
        var sources = numbering.bibliography();
        if (sources.isEmpty()) return "";
        StringBuilder result = new StringBuilder("\n\n\\subsection*{Quellen}\n\\begin{enumerate}\n");
        for (var source : sources) {
            result.append("\\item ").append(latexText(source.description())).append(". \\texttt{")
                .append(latexText(source.url())).append("}\n");
        }
        return result.append("\\end{enumerate}\n").toString();
    }

    private static String markdownText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
            .replace("*", "\\*").replace("_", "\\_").replace("~", "\\~").replace("`", "\\`");
    }

    private static String latexText(String text) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\textbackslash{}");
                case '^' -> result.append("\\textasciicircum{}");
                case '~' -> result.append("\\textasciitilde{}");
                case '≥' -> result.append("\\ensuremath{\\geq}");
                case '≤' -> result.append("\\ensuremath{\\leq}");
                case '≠' -> result.append("\\ensuremath{\\neq}");
                case '→' -> result.append("\\ensuremath{\\to}");
                case '#', '$', '%', '&', '_', '{', '}' -> result.append('\\').append(character);
                default -> result.append(character);
            }
        }
        return result.toString();
    }
}
