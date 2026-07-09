package de.regelsuche.docs;

import de.regelsuche.search.telemetry.SearchTimelineDataCollector.TimelinePoint;
import java.util.List;
import java.util.Locale;

/**
 * Generates an SVG timeline from search telemetry data points.
 *
 * <p>The chart shows two lines over event sequence numbers:
 * <ul>
 *   <li><strong>Frontier size</strong> — how the open list grows and shrinks as the search proceeds.</li>
 *   <li><strong>Visited count</strong> — the cumulative number of canonical states visited.</li>
 * </ul>
 * </p>
 */
final class TelemetryTimelineSvgWriter {

    String render(String scenarioId, List<TimelinePoint> points) {
        if (points.isEmpty()) {
            return renderEmpty(scenarioId);
        }

        int svgWidth = 900;
        int svgHeight = 420;
        int chartLeft = 70;
        int chartRight = svgWidth - 40;
        int chartTop = 60;
        int chartBottom = svgHeight - 80;
        int chartWidth = chartRight - chartLeft;
        int chartHeight = chartBottom - chartTop;

        long maxSeq = points.getLast().sequence();
        int maxFrontier = points.stream().mapToInt(TimelinePoint::frontierSize).max().orElse(1);
        int maxVisited = points.stream().mapToInt(TimelinePoint::visitedCount).max().orElse(1);
        int yMax = Math.max(maxFrontier, maxVisited);
        if (yMax < 1) {
            yMax = 1;
        }

        StringBuilder frontierPolyline = new StringBuilder();
        StringBuilder visitedPolyline = new StringBuilder();
        for (TimelinePoint pt : points) {
            double x = chartLeft + (maxSeq == 0 ? 0 : (double) pt.sequence() / maxSeq * chartWidth);
            double yFrontier = chartBottom - (double) pt.frontierSize() / yMax * chartHeight;
            double yVisited = chartBottom - (double) pt.visitedCount() / yMax * chartHeight;
            frontierPolyline.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, yFrontier));
            visitedPolyline.append(String.format(Locale.ROOT, "%.1f,%.1f ", x, yVisited));
        }

        int midY = (chartTop + chartBottom) / 2;
        int legendY = svgHeight - 20;

        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" data-generated-by="TelemetryTimelineSvgWriter" data-scenario-id="${scenarioId}">
              <rect width="100%" height="100%" fill="#f8fafc"/>
              <text x="40" y="36" font-size="15" font-weight="700" fill="#1e293b">Search telemetry timeline: ${scenarioId}</text>
              <text x="40" y="54" font-size="11" fill="#64748b">Frontier size and visited count over STATE_VISITED events · ${pointCount} data points · max frontier ${maxFrontier} · max visited ${maxVisited}</text>
              <line x1="${chartLeft}" y1="${chartTop}" x2="${chartLeft}" y2="${chartBottom}" stroke="#94a3b8" stroke-width="1.5"/>
              <line x1="${chartLeft}" y1="${chartBottom}" x2="${chartRight}" y2="${chartBottom}" stroke="#94a3b8" stroke-width="1.5"/>
              <text x="${chartLeft}" y="${chartBottom+16}" font-size="11" fill="#64748b" text-anchor="middle">Event sequence</text>
              <text x="14" y="${midY}" font-size="11" fill="#64748b" text-anchor="middle" transform="rotate(-90,14,${midY})">Count</text>
              <text x="${chartLeft}" y="${chartTop-8}" font-size="10" fill="#94a3b8">${yMax}</text>
              <text x="${chartLeft}" y="${chartBottom-4}" font-size="10" fill="#94a3b8">0</text>
              <polyline points="${frontierPolyline}" fill="none" stroke="#6366f1" stroke-width="2" stroke-linejoin="round" opacity="0.85"/>
              <polyline points="${visitedPolyline}" fill="none" stroke="#10b981" stroke-width="2" stroke-linejoin="round" opacity="0.85"/>
              <rect x="40" y="${legendY-12}" width="12" height="12" fill="#6366f1" opacity="0.85"/>
              <text x="56" y="${legendY}" font-size="11" fill="#475569">Frontier size</text>
              <rect x="160" y="${legendY-12}" width="12" height="12" fill="#10b981" opacity="0.85"/>
              <text x="176" y="${legendY}" font-size="11" fill="#475569">Visited count</text>
            </svg>
            """
            .replace("${w}", Integer.toString(svgWidth))
            .replace("${h}", Integer.toString(svgHeight))
            .replace("${scenarioId}", escapeXml(scenarioId))
            .replace("${pointCount}", Integer.toString(points.size()))
            .replace("${maxFrontier}", Integer.toString(maxFrontier))
            .replace("${maxVisited}", Integer.toString(maxVisited))
            .replace("${yMax}", Integer.toString(yMax))
            .replace("${chartLeft}", Integer.toString(chartLeft))
            .replace("${chartRight}", Integer.toString(chartRight))
            .replace("${chartTop}", Integer.toString(chartTop))
            .replace("${chartBottom}", Integer.toString(chartBottom))
            .replace("${midY}", Integer.toString(midY))
            .replace("${legendY}", Integer.toString(legendY))
            .replace("${frontierPolyline}", frontierPolyline.toString().trim())
            .replace("${visitedPolyline}", visitedPolyline.toString().trim());
    }

    private String renderEmpty(String scenarioId) {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"900\" height=\"100\">"
            + "<rect width=\"100%\" height=\"100%\" fill=\"#f8fafc\"/>"
            + "<text x=\"40\" y=\"54\" font-size=\"13\" fill=\"#64748b\">No timeline data for "
            + escapeXml(scenarioId) + "</text></svg>\n";
    }

    private String escapeXml(String text) {
        return text == null ? "" : text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
