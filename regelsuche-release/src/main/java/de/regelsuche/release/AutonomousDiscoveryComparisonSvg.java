package de.regelsuche.release;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Presentation only: renders paired measurements without inventing a search trace. */
final class AutonomousDiscoveryComparisonSvg {
    private static final int WIDTH = 1000;
    private static final int FORMULA_COLUMNS = 72;

    private AutonomousDiscoveryComparisonSvg() {
    }

    static String render(Comparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        StringBuilder body = new StringBuilder();
        text(body, "heading", 32, 44, "Was bringt die gelernte Regel?");
        text(body, "label", 32, 76,
            "Dieselbe zurückgehaltene Aufgabe, mit und ohne zusätzliche Regel.");
        int y = formula(body, 100, "Gelernte Regel",
            comparison.leftPattern() + "  →  " + comparison.rightPattern());
        y = formula(body, y, "Ausgangsausdruck der zurückgehaltenen Aufgabe",
            comparison.inputExpression());
        y = formula(body, y, "Gesuchte Zieldarstellung (exakter Syntaxvergleich)",
            comparison.targetExpression());
        outcome(body, 32, y, "Ohne gelernte Regel", comparison.baseline());
        outcome(body, 512, y, "Mit gelernter Regel", comparison.candidate());
        y += 248;
        text(body, "note", 32, y,
            "Verglichen werden Suchzustände, nicht gemessene Laufzeiten.");
        text(body, "note", 32, y + 28,
            "Ein ausgewählter Holdout-Fall; die vollständige Fallserie steht in der Evidence.");
        text(body, "claim", 32, y + 58, "NO EXTERNAL NOVELTY CLAIM");
        y += 90;
        for (String line : wrap("Fall: " + comparison.id(), 100)) {
            text(body, "source", 32, y, line);
            y += 20;
        }
        int height = y + 16;
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + WIDTH
            + "\" height=\"" + height + "\" viewBox=\"0 0 " + WIDTH + " " + height
            + "\" role=\"img\" aria-labelledby=\"title desc\" xml:lang=\"de\""
            + " data-generated-by=\"AutonomousDiscoveryComparisonSvg\""
            + " data-case-id=\"" + xml(comparison.id()) + "\">\n"
            + "<title id=\"title\">Gelernte Regel im Held-out-Vergleich</title>\n"
            + "<desc id=\"desc\">" + xml(description(comparison)) + "</desc>\n"
            + """
                <style>
                text{font-family:Arial,Helvetica,sans-serif;fill:#172033}
                .heading{font-size:28px;font-weight:700}
                .label{font-size:18px}.formula{font-family:monospace;font-size:20px}
                .cardTitle{font-size:20px;font-weight:700}.count{font-size:52px;font-weight:700}
                .countLong{font-size:28px;font-weight:700}
                .result{font-size:22px;font-weight:700}.note{font-size:17px}
                .claim{font-size:16px;font-weight:700;fill:#73402b}
                .source{font-family:monospace;font-size:14px}
                </style>
                <rect width="100%" height="100%" fill="#ffffff"/>
                """
            + body + "</svg>\n";
    }

    private static int formula(StringBuilder svg, int y, String label, String expression) {
        List<String> lines = wrap(expression, FORMULA_COLUMNS);
        int height = 50 + lines.size() * 26;
        rect(svg, 32, y, 936, height, "#f7f8fa");
        text(svg, "label", 56, y + 26, label);
        for (int index = 0; index < lines.size(); index++) {
            text(svg, "formula", 56, y + 58 + index * 26, lines.get(index));
        }
        return y + height + 16;
    }

    private static void outcome(StringBuilder svg, int x, int y, String label, Outcome result) {
        rect(svg, x, y, 456, 214, result.reached() ? "#eef8ee" : "#fff6ed");
        text(svg, "cardTitle", x + 24, y + 34, label);
        String count = Long.toString(result.exploredStates());
        text(svg, count.length() > 12 ? "countLong" : "count", x + 24, y + 94, count);
        text(svg, "label", x + 24, y + 123, "untersuchte Zustände");
        text(svg, "result", x + 24, y + 160,
            result.reached() ? "Ziel erreicht" : "Ziel im Lauf nicht erreicht");
        text(svg, "label", x + 24, y + 191, pathLabel(result));
    }

    private static String pathLabel(Outcome result) {
        if (!result.reached()) {
            return "Kein Zielpfad gefunden";
        }
        return result.pathLength() + (result.pathLength() == 1
            ? " Umformung im Zielpfad" : " Umformungen im Zielpfad");
    }

    private static String description(Comparison comparison) {
        return "Fall " + comparison.id() + ". Regel: " + comparison.leftPattern()
            + " → " + comparison.rightPattern() + ". Ausgangsausdruck: "
            + comparison.inputExpression() + ". Gesuchte Zieldarstellung: "
            + comparison.targetExpression() + ". Ohne Regel: "
            + comparison.baseline().exploredStates() + " untersuchte Zustände; "
            + pathLabel(comparison.baseline()) + ". Mit Regel: "
            + comparison.candidate().exploredStates() + " untersuchte Zustände; "
            + pathLabel(comparison.candidate())
            + ". Kein Laufzeitvergleich und keine Behauptung externer mathematischer Neuheit.";
    }

    /** Conservative monospace wrapping; never truncates formula tokens or case identifiers. */
    private static List<String> wrap(String value, int columns) {
        List<String> lines = new ArrayList<>();
        String remaining = value;
        while (remaining.codePointCount(0, remaining.length()) > columns) {
            int end = remaining.offsetByCodePoints(0, columns);
            int space = remaining.lastIndexOf(' ', end);
            if (space > 0) {
                end = space;
            }
            lines.add(remaining.substring(0, end));
            remaining = remaining.substring(end).stripLeading();
        }
        lines.add(remaining);
        return lines;
    }

    private static void rect(StringBuilder svg, int x, int y, int width, int height, String fill) {
        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
            .append("\" width=\"").append(width).append("\" height=\"").append(height)
            .append("\" rx=\"12\" fill=\"").append(fill)
            .append("\" stroke=\"#c5cad3\"/>\n");
    }

    private static void text(StringBuilder svg, String css, int x, int y, String value) {
        svg.append("<text class=\"").append(css).append("\" x=\"").append(x)
            .append("\" y=\"").append(y).append("\">").append(xml(value))
            .append("</text>\n");
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    record Outcome(boolean reached, int pathLength, long exploredStates) {
        Outcome {
            if (exploredStates < 0 || (reached ? pathLength < 0 : pathLength != -1)) {
                throw new IllegalArgumentException("inconsistent search outcome");
            }
        }
    }

    record Comparison(
        String id, String inputExpression, String targetExpression,
        String leftPattern, String rightPattern, Outcome baseline, Outcome candidate
    ) {
        Comparison {
            for (String value : new String[]{id, inputExpression, targetExpression,
                    leftPattern, rightPattern}) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("comparison text must not be blank");
                }
            }
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(candidate, "candidate");
        }
    }
}
