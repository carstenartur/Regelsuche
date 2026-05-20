package de.regelsuche.export;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphStatsDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.MacroRuleCandidate;
import java.util.List;
import java.util.Locale;

/**
 * Produces a comprehensive analysis report covering the input expression,
 * search profile, domain selection, graph metrics, the best path, alternative
 * paths, macro rule candidates, identities, assumptions, proof status and
 * rule-inventory changes.
 *
 * <p>Supports three output formats — Markdown for humans, LaTeX for papers
 * and JSON for downstream tools. Used by
 * {@code GET /api/exports/search-analysis-report.{md,tex,json}}.</p>
 */
public final class SearchAnalysisReportService {

    private final AstLatexRenderer latex = new AstLatexRenderer();

    public String renderMarkdown(SearchAnalysisReportContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Suchanalyse-Bericht\n\n");
        appendHeader(sb, ctx, " * ");
        SearchGraphStatsDto stats = ctx.graph().stats();
        sb.append("## Graphmetriken\n\n");
        sb.append(" * Knoten: ").append(stats.nodeCount()).append("\n");
        sb.append(" * Kanten: ").append(stats.edgeCount()).append("\n");
        sb.append(" * Sackgassen: ").append(stats.deadEndCount()).append("\n");
        sb.append(" * Bester Score: ").append(stats.bestScore()).append("\n");
        sb.append(" * Durchschn. Branching: ")
            .append(String.format(Locale.ROOT, "%.2f", stats.averageBranchingFactor())).append("\n");
        sb.append(" * Maximaltiefe: ").append(stats.maxDepthReached()).append("\n");
        sb.append(" * Kandidaten: ").append(stats.candidateCount()).append("\n");
        sb.append(" * Macro-Regeln: ").append(stats.macroRuleCount()).append("\n\n");

        DiscoveredTransformation best = ctx.bestPath();
        if (best != null) {
            sb.append("## Bester Pfad\n\n");
            sb.append(" * Id: `").append(best.id()).append("`\n");
            sb.append(" * Schritte: ").append(best.steps().size()).append("\n");
            sb.append(" * Score-Verbesserung: ").append(best.totalImprovement()).append("\n");
            sb.append(" * Proof-Status: ").append(best.validationStatus().name()).append("\n");
            sb.append(" * Rechenweg: `").append(latex.renderExpression(best.originalExpression()))
                .append("` → `").append(latex.renderExpression(best.improvedExpression())).append("`\n\n");
        }

        List<DiscoveredTransformation> alternatives = ctx.alternativePaths();
        if (!alternatives.isEmpty()) {
            sb.append("## Alternative Pfade\n\n");
            for (DiscoveredTransformation t : alternatives) {
                sb.append(" * `").append(t.id()).append("` — ").append(t.steps().size())
                    .append(" Schritte, Verbesserung ").append(t.totalImprovement())
                    .append(", Status ").append(t.validationStatus().name()).append("\n");
            }
            sb.append('\n');
        }

        if (!ctx.macroRules().isEmpty()) {
            sb.append("## Makroregeln\n\n");
            for (MacroRuleCandidate m : ctx.macroRules()) {
                sb.append(" * `").append(m.id()).append("`: `")
                    .append(latex.renderExpression(m.leftPattern())).append("` → `")
                    .append(latex.renderExpression(m.rightPattern())).append("` (")
                    .append(m.occurrences()).append(" Vorkommen, Proof: ")
                    .append(m.proofStatus().name()).append(")\n");
            }
            sb.append('\n');
        }

        if (!ctx.identities().isEmpty()) {
            sb.append("## Identitäten\n\n");
            for (IdentityReportDto id : ctx.identities()) {
                sb.append(" * `").append(id.id()).append("`: `")
                    .append(latex.renderExpression(id.leftPattern())).append("` = `")
                    .append(latex.renderExpression(id.rightPattern())).append("`, bekannt: ")
                    .append(id.knownRuleStatus().name()).append("\n");
            }
            sb.append('\n');
        }

        if (!ctx.assumptions().isEmpty()) {
            sb.append("## Annahmen\n\n");
            for (String a : ctx.assumptions()) {
                sb.append(" * ").append(a).append("\n");
            }
            sb.append('\n');
        }

        if (!ctx.inventoryAdditions().isEmpty()) {
            sb.append("## Regelvorrat-Änderungen\n\n");
            for (ReusableRule r : ctx.inventoryAdditions()) {
                sb.append(" * `").append(r.id()).append("`: `")
                    .append(r.leftPattern()).append("` → `").append(r.rightPattern()).append("`\n");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public String renderLatex(SearchAnalysisReportContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("\\documentclass{article}\n\\usepackage{amsmath}\n\\begin{document}\n");
        sb.append("\\section*{Suchanalyse-Bericht}\n");
        sb.append("\\textbf{Eingabe:} $").append(latex.renderExpression(ctx.input())).append("$\\\\\n");
        sb.append("\\textbf{Suchprofil:} ").append(escapeLatex(ctx.searchProfile())).append("\\\\\n");
        sb.append("\\textbf{Domänen:} ").append(escapeLatex(String.join(", ", ctx.domains()))).append("\\\\\n");
        DiscoveredTransformation best = ctx.bestPath();
        if (best != null) {
            sb.append("\\section*{Bester Pfad}\n");
            sb.append("$").append(latex.renderExpression(best.originalExpression())).append("$ \\\\\n");
            sb.append("$\\rightarrow$ $").append(latex.renderExpression(best.improvedExpression())).append("$\\\\\n");
            sb.append("Schritte: ").append(best.steps().size()).append(", Score-Verbesserung: ")
                .append(best.totalImprovement()).append("\\\\\n");
        }
        if (!ctx.identities().isEmpty()) {
            sb.append("\\section*{Identitäten}\n\\begin{itemize}\n");
            for (IdentityReportDto id : ctx.identities()) {
                sb.append("  \\item $").append(latex.renderExpression(id.leftPattern()))
                    .append(" = ").append(latex.renderExpression(id.rightPattern())).append("$\n");
            }
            sb.append("\\end{itemize}\n");
        }
        sb.append("\\end{document}\n");
        return sb.toString();
    }

    public String renderJson(SearchAnalysisReportContext ctx) {
        JsonWriter w = new JsonWriter();
        w.beginObject();
        w.property("input", ctx.input());
        w.property("searchProfile", ctx.searchProfile());
        w.stringArray("domains", ctx.domains());
        SearchGraphStatsDto s = ctx.graph().stats();
        w.object("graphMetrics", inner -> {
            inner.property("nodeCount", s.nodeCount());
            inner.property("edgeCount", s.edgeCount());
            inner.property("deadEndCount", s.deadEndCount());
            inner.property("bestScore", s.bestScore());
            inner.property("averageBranchingFactor", s.averageBranchingFactor());
            inner.property("maxDepthReached", s.maxDepthReached());
            inner.property("candidateCount", s.candidateCount());
            inner.property("macroRuleCount", s.macroRuleCount());
        });
        DiscoveredTransformation best = ctx.bestPath();
        if (best != null) {
            w.object("bestPath", inner -> {
                inner.property("id", best.id());
                inner.property("stepCount", best.steps().size());
                inner.property("totalImprovement", best.totalImprovement());
                inner.property("proofStatus", best.validationStatus().name());
                inner.property("originalExpression", best.originalExpression());
                inner.property("improvedExpression", best.improvedExpression());
            });
        }
        w.array("alternativePaths", arr -> {
            for (DiscoveredTransformation t : ctx.alternativePaths()) {
                arr.objectValue(o -> {
                    o.property("id", t.id());
                    o.property("stepCount", t.steps().size());
                    o.property("totalImprovement", t.totalImprovement());
                    o.property("proofStatus", t.validationStatus().name());
                });
            }
        });
        w.array("macroRules", arr -> {
            for (MacroRuleCandidate m : ctx.macroRules()) {
                arr.objectValue(o -> {
                    o.property("id", m.id());
                    o.property("leftPattern", m.leftPattern());
                    o.property("rightPattern", m.rightPattern());
                    o.property("occurrences", m.occurrences());
                    o.property("compressionRatio", m.compressionRatio());
                    o.property("proofStatus", m.proofStatus().name());
                });
            }
        });
        w.array("identities", arr -> {
            for (IdentityReportDto id : ctx.identities()) {
                arr.objectValue(o -> {
                    o.property("id", id.id());
                    o.property("leftPattern", id.leftPattern());
                    o.property("rightPattern", id.rightPattern());
                    o.property("knownRuleStatus", id.knownRuleStatus().name());
                    o.property("proofStatus", id.proofStatus().name());
                });
            }
        });
        w.stringArray("assumptions", ctx.assumptions());
        w.array("inventoryAdditions", arr -> {
            for (ReusableRule r : ctx.inventoryAdditions()) {
                arr.objectValue(o -> {
                    o.property("id", r.id());
                    o.property("leftPattern", r.leftPattern());
                    o.property("rightPattern", r.rightPattern());
                });
            }
        });
        w.endObject();
        return w.toString();
    }

    private void appendHeader(StringBuilder sb, SearchAnalysisReportContext ctx, String bullet) {
        sb.append("## Eingabe\n\n");
        sb.append(bullet).append("Ausdruck: `").append(ctx.input()).append("`\n");
        sb.append(bullet).append("LaTeX: $").append(latex.renderExpression(ctx.input())).append("$\n");
        sb.append(bullet).append("Suchprofil: ").append(ctx.searchProfile()).append("\n");
        sb.append(bullet).append("Domänen: ").append(String.join(", ", ctx.domains())).append("\n\n");
    }

    private static String escapeLatex(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\textbackslash{}")
            .replace("_", "\\_")
            .replace("&", "\\&")
            .replace("%", "\\%")
            .replace("#", "\\#");
    }

    /** Bundles every piece needed to render a report. */
    public record SearchAnalysisReportContext(
        String input,
        String searchProfile,
        List<String> domains,
        SearchGraphDto graph,
        DiscoveredTransformation bestPath,
        List<DiscoveredTransformation> alternativePaths,
        List<MacroRuleCandidate> macroRules,
        List<IdentityReportDto> identities,
        List<String> assumptions,
        List<ReusableRule> inventoryAdditions
    ) {
        public SearchAnalysisReportContext {
            input = input == null ? "" : input;
            searchProfile = searchProfile == null ? "" : searchProfile;
            domains = domains == null ? List.of() : List.copyOf(domains);
            alternativePaths = alternativePaths == null ? List.of() : List.copyOf(alternativePaths);
            macroRules = macroRules == null ? List.of() : List.copyOf(macroRules);
            identities = identities == null ? List.of() : List.copyOf(identities);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            inventoryAdditions = inventoryAdditions == null ? List.of() : List.copyOf(inventoryAdditions);
        }
    }
}
