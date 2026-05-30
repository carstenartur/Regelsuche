package de.regelsuche.benchmark;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.transform.SquareDifferenceAstPredicate;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.BasicStroke;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Writes deterministic replay/report artefacts for CI discovery runs. */
public final class DiscoveryReplayArtifactWriter {
    private static final String CONTAINER_SECTION_KEY = "doc" + "ker";
    private static final String CONTAINER_FILE_KEY = CONTAINER_SECTION_KEY + "file";
    private static final String CONTAINER_FILE_SHA_KEY = CONTAINER_FILE_KEY + "Sha256";
    private static final String CONTAINER_IMAGE_ENV = "REGELSUCHE_" + "DOCKER" + "_IMAGE";
    private static final String CONTAINER_FILE_NAME = "Doc" + "kerfile";

    public ArtifactBundle write(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        Path outputDirectory
    ) {
        return write(report, outputDirectory, List.of(), null);
    }

    public ArtifactBundle write(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        Path outputDirectory,
        Collection<MathematicalAlgorithmRegistry.AlgorithmDescriptor> algorithmSnapshot
    ) {
        return write(report, outputDirectory, algorithmSnapshot, null);
    }

    public ArtifactBundle write(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        Path outputDirectory,
        Collection<MathematicalAlgorithmRegistry.AlgorithmDescriptor> algorithmSnapshot,
        DiscoverySemanticReportView semanticView
    ) {
        try {
            DiscoverySemanticReportView effectiveSemanticView = semanticView == null
                ? DiscoverySemanticReportView.fromReplayPaths(report)
                : semanticView;
            Files.createDirectories(outputDirectory);
            Path json = outputDirectory.resolve("discovery-report.json");
            Path html = outputDirectory.resolve("discovery-report.html");
            Path markdown = outputDirectory.resolve("discovery-report.md");
            Path replayExport = outputDirectory.resolve("discovery-replay.json");
            Path screenshot = outputDirectory.resolve("discovery-summary.png");
            Path gif = outputDirectory.resolve("discovery-replay.gif");
            Path reproPack = outputDirectory.resolve("reproducibility-pack.json");
            Path hypotheses = outputDirectory.resolve("hypotheses.json");
            Path macroRules = outputDirectory.resolve("macro-rules.json");
            Path counterexamples = outputDirectory.resolve("counterexamples.json");
            Path provenanceGraph = outputDirectory.resolve("provenance.graph.json");
            Path campaign = outputDirectory.resolve("discovery-campaign.json");
            Files.writeString(json, report.renderDeterministicJson());
            Files.writeString(html, renderHtml(report));
            Files.writeString(markdown, renderMarkdown(report, effectiveSemanticView));
            Files.writeString(replayExport, renderReplayExport(report, effectiveSemanticView));
            Files.writeString(hypotheses, renderHypothesesExport(report));
            Files.writeString(macroRules, renderMacroRulesExport(report));
            Files.writeString(counterexamples, renderCounterexamplesExport(report));
            Files.writeString(provenanceGraph, renderProvenanceGraphExport(report));
            Files.writeString(campaign, DiscoveryCampaign.fromReport(
                "campaign-" + sha256Utf8Lines(report.rows().stream().map(row -> row.seed().id()).sorted().toList()).substring(0, 12),
                report,
                report.metrics().processedSeeds(),
                1,
                enabledBackends(algorithmSnapshot),
                "JSON_FILE"
            ).renderJson());
            writeScreenshot(screenshot, report, effectiveSemanticView);
            writeReplayGif(gif, report, effectiveSemanticView);
            Files.writeString(reproPack, renderReproducibilityPack(report,
                List.of(json, html, markdown, replayExport, screenshot, gif, hypotheses, macroRules, counterexamples, provenanceGraph, campaign),
                algorithmSnapshot));
            return new ArtifactBundle(json, html, markdown, replayExport, screenshot, gif, reproPack,
                hypotheses, macroRules, counterexamples, provenanceGraph, campaign);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write discovery replay artefacts to " + outputDirectory, exception);
        }
    }

    public String renderHtml(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">");
        out.append("<title>Regelsuche Discovery Report</title>");
        out.append("<style>")
            .append("body{font-family:system-ui,sans-serif;background:#f6f8fb;color:#1f2937;margin:0;padding:2rem}")
            .append("h1,h2{margin:.2rem 0 1rem}table{width:100%;border-collapse:collapse;background:#fff}")
            .append("td,th{border:1px solid #d8dee8;padding:.5rem;vertical-align:top;text-align:left}")
            .append("th{background:#eef2ff}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:.75rem;margin:1rem 0}")
            .append(".card{background:#fff;border:1px solid #d8dee8;border-radius:.75rem;padding:.9rem}")
            .append(".metric{font-size:1.5rem;font-weight:700}.muted{color:#667085;font-size:.92rem}")
            .append(".replay-step{margin:.2rem 0}.ok{color:#137333}.fail{color:#a50e0e}.unknown{color:#b45309}.neutral{color:#1d4ed8}")
            .append(".pill{display:inline-block;background:#eef2ff;border:1px solid #c7d2fe;border-radius:999px;padding:.08rem .45rem;margin:.08rem;font-size:.82rem}")
            .append("code{background:#f3f4f6;border-radius:.25rem;padding:.08rem .25rem}ul,ol{margin:.2rem 0 .2rem 1.2rem;padding:0}")
            .append("</style>");
        out.append("</head><body><h1>Regelsuche Discovery Report</h1>");
        out.append("<p class=\"muted\">Deterministischer Replay-/Discovery-Bericht für wissenschaftliche Reproduktionsläufe.</p>");
        DiscoveryDashboardMetrics dashboard = dashboardMetrics(report);
        out.append("<div class=\"cards\">")
            .append(metricCard("searchSpaceSize", String.valueOf(dashboard.searchSpaceSize())))
            .append(metricCard("matchStats", dashboard.matchStats().matched() + " / " + dashboard.matchStats().unmatched()))
            .append(metricCard("macroMoveUsage", dashboard.macroMoveUsage().applied() + " applied"))
            .append(metricCard("memoryUsage", dashboard.memoryUsage() + " B"))
            .append(metricCard("counterexampleStats", dashboard.counterexampleStats().found() + " / " + dashboard.counterexampleStats().checked()))
            .append(metricCard("proofSuccessRate", String.format(java.util.Locale.ROOT, "%.2f", dashboard.proofSuccessRate())))
            .append(metricCard("artifactCounts", String.valueOf(dashboard.artifactCounts().values().stream().mapToInt(Integer::intValue).sum())))
            .append(metricCard("Seeds", String.valueOf(report.metrics().processedSeeds())))
            .append(metricCard("Erfolgreich", String.valueOf(report.metrics().successfulSeeds())))
            .append(metricCard("Hypothesen", String.valueOf(report.metrics().hypotheses())))
            .append(metricCard("Gegenbeispiele", String.valueOf(report.metrics().counterexamples())))
            .append(metricCard("Runtime Σ", report.metrics().accumulatedRuntimeMillis() + " ms"))
            .append(metricCard("Speicher Σ", report.metrics().accumulatedMemoryBytes() + " B"))
            .append("</div>");
        out.append("<p class=\"muted\">Seeds: ").append(report.metrics().processedSeeds())
            .append(" · erfolgreich: ").append(report.metrics().successfulSeeds())
            .append(" · Hypothesen: ").append(report.metrics().hypotheses())
            .append(" · Gegenbeispiele: ").append(report.metrics().counterexamples())
            .append("</p><table><thead><tr><th>Seed</th><th>Status</th><th>ResultKind</th><th>Discovery</th><th>Rules</th><th>Replay</th><th>Summary</th></tr></thead><tbody>");
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("<tr><td>").append(escape(row.seed().stableKey())).append("</td><td>")
                .append(row.success() ? "<span class=\"ok\">OK</span>" : "<span class=\"fail\">FAIL</span>")
                .append("<div>").append(renderCounterexampleStatusLabel(row)).append("</div>")
                .append("<div class=\"muted\">Kategorie: ").append(escape(row.seed().category())).append("</div>")
                .append("</td><td>").append(escape(row.resultKind().name()))
                .append("</td><td>")
                .append(renderHtmlDiscoveryDetails(row))
                .append("</td><td>").append(escape(row.rulePath().isEmpty() ? "—" : String.join(" → ", row.rulePath())))
                .append("</td><td><ol>");
            for (String step : row.replayPath()) {
                out.append("<li class=\"replay-step\"><code>").append(escape(step)).append("</code></li>");
            }
            if (row.replayPath().isEmpty()) {
                out.append("<li class=\"replay-step muted\">Kein Replay vorhanden</li>");
            }
            out.append("</ol></td><td>").append(escape(row.summary()))
                .append("<div class=\"muted\">Laufzeit: ").append(row.elapsedMillis())
                .append(" ms · Speicher: ").append(row.memoryBytes()).append(" B</div></td></tr>");
        }
        out.append("</tbody></table></body></html>");
        return out.toString();
    }

    public String renderMarkdown(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        return renderMarkdown(report, DiscoverySemanticReportView.fromReplayPaths(report));
    }

    public String renderMarkdown(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView
    ) {
        StringBuilder out = new StringBuilder();
        out.append("# Regelsuche Discovery Report\n\n");
        out.append("- Seeds: ").append(report.metrics().processedSeeds()).append('\n');
        out.append("- Erfolgreich: ").append(report.metrics().successfulSeeds()).append('\n');
        out.append("- Hypothesen: ").append(report.metrics().hypotheses()).append('\n');
        out.append("- Gegenbeispiele: ").append(report.metrics().counterexamples()).append('\n');
        out.append("- Laufzeit Σ: ").append(report.metrics().accumulatedRuntimeMillis()).append(" ms\n");
        out.append("- Speicher Σ: ").append(report.metrics().accumulatedMemoryBytes()).append(" B\n\n");
        DiscoveryDashboardMetrics dashboard = dashboardMetrics(report);
        out.append("## Dashboard Metrics\n\n");
        out.append("- searchSpaceSize: ").append(dashboard.searchSpaceSize()).append('\n');
        out.append("- matchStats: ").append(dashboard.matchStats().matched()).append(" matched / ")
            .append(dashboard.matchStats().unmatched()).append(" unmatched\n");
        out.append("- macroMoveUsage: ").append(dashboard.macroMoveUsage().applied()).append(" applied\n");
        out.append("- memoryUsage: ").append(dashboard.memoryUsage()).append(" B\n");
        out.append("- counterexampleStats: ").append(dashboard.counterexampleStats().found()).append(" / ")
            .append(dashboard.counterexampleStats().checked()).append('\n');
        out.append("- proofSuccessRate: ").append(String.format(java.util.Locale.ROOT, "%.2f", dashboard.proofSuccessRate())).append('\n');
        out.append("- artifactCounts: ").append(dashboard.artifactCounts()).append("\n\n");
        out.append("## Semantic Discovery View\n\n");
        out.append("- Renderer: ").append(semanticView.renderer()).append('\n');
        out.append("- Raw graph nodes: ").append(semanticView.rawNodeCount()).append('\n');
        out.append("- Raw graph edges: ").append(semanticView.rawEdgeCount()).append('\n');
        out.append("- Main path nodes: ").append(semanticView.semanticNodeCount()).append('\n');
        out.append("- Semantic edges: ").append(semanticView.semanticEdgeCount()).append('\n');
        out.append("- Collapsed variants: ").append(semanticView.collapsedVariantCount()).append('\n');
        out.append("- Collapsed low-signal steps: ").append(semanticView.collapsedLowSignalCount()).append("\n\n");
        out.append("```mermaid\n").append(renderSemanticMermaid(semanticView)).append("```\n\n");
        out.append(renderGeneratedGallery(report, semanticView));
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            out.append("## ").append(row.seed().stableKey()).append("\n\n");
            out.append("- Status: ").append(row.success() ? "OK" : "FAIL").append('\n');
            out.append("- resultKind: ").append(row.resultKind().name()).append('\n');
            out.append("- Kategorie: ").append(row.seed().category()).append('\n');
            out.append("- Annahmen: ").append(joinOrDash(row.seed().assumptions())).append('\n');
            out.append("- Hypothesen: ").append(joinOrDash(row.hypotheses())).append('\n');
            out.append("- counterexampleStatus: ").append(row.counterexampleSearchStatus().name()).append('\n');
            out.append("- attempted sources: ").append(joinOrDash(row.counterexampleAttemptedSources())).append('\n');
            out.append("- inferred assumptions: ").append(joinOrDash(row.inferredAssumptions())).append('\n');
            out.append("- explanation: ").append(row.counterexampleExplanation().isBlank() ? "–" : row.counterexampleExplanation()).append('\n');
            out.append("- Gegenbeispiele: ").append(joinOrDash(row.counterexamples())).append('\n');
            out.append("- Laufzeit: ").append(row.elapsedMillis()).append(" ms\n");
            out.append("- Speicher: ").append(row.memoryBytes()).append(" B\n");
            out.append("- Summary: ").append(row.summary()).append("\n\n");
            out.append("### Discovery summary table\n\n");
            out.append("| expression | operator | resultKind | bridge? | simplified/factored? | learnedMacro? | macroReused? | proofStatus | rulePath | notes |\n");
            out.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
            out.append("| ").append(escapeMarkdown(row.seed().expression()))
                .append(" | ").append(escapeMarkdown(operatorLabel(row.rulePath())))
                .append(" | ").append(row.resultKind().name())
                .append(" | ").append(isBridgeKind(row) ? "yes" : "no")
                .append(" | ").append(isTransformedKind(row) ? "yes" : "no")
                .append(" | ").append(isMacroLearnedKind(row) ? "yes" : "no")
                .append(" | ").append(row.resultKind().hasMacroReuse() ? "yes" : "no")
                .append(" | ").append(row.counterexampleSearchStatus().name())
                .append(" | ").append(escapeMarkdown(row.rulePath().isEmpty() ? "—" : String.join(" -> ", row.rulePath())))
                .append(" | ").append(escapeMarkdown(row.summary()))
                .append(" |\n\n");
            out.append("### Replay\n\n");
            if (row.replayPath().isEmpty()) {
                out.append("- Kein Replay vorhanden\n\n");
            } else {
                for (int i = 0; i < row.replayPath().size(); i++) {
                    out.append(i + 1).append(". `").append(row.replayPath().get(i)).append("`\n");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    public String renderReplayExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        return renderReplayExport(report, DiscoverySemanticReportView.fromReplayPaths(report));
    }

    public String renderReplayExport(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView
    ) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.discovery-replay/v1");
        DiscoveryDashboardMetrics dashboard = dashboardMetrics(report);
        writer.object("dashboardMetrics", metrics -> {
            metrics.property("searchSpaceSize", dashboard.searchSpaceSize());
            metrics.object("matchStats", match -> {
                match.property("matched", dashboard.matchStats().matched());
                match.property("unmatched", dashboard.matchStats().unmatched());
            });
            metrics.object("macroMoveUsage", macro -> {
                macro.property("considered", dashboard.macroMoveUsage().considered());
                macro.property("applied", dashboard.macroMoveUsage().applied());
                macro.property("averageCostReduction", dashboard.macroMoveUsage().averageCostReduction());
            });
            metrics.property("memoryUsage", dashboard.memoryUsage());
            metrics.object("counterexampleStats", counterexamples -> {
                counterexamples.property("checked", dashboard.counterexampleStats().checked());
                counterexamples.property("found", dashboard.counterexampleStats().found());
            });
            metrics.property("proofSuccessRate", dashboard.proofSuccessRate());
            metrics.object("artifactCounts", artifacts -> dashboard.artifactCounts().forEach(artifacts::property));
        });
        writer.array("replays", arr -> report.rows().forEach(row -> arr.objectValue(object -> {
            object.property("seedId", row.seed().id());
            object.property("seedStableKey", row.seed().stableKey());
            object.property("category", row.seed().category());
            object.property("resultKind", row.resultKind().name());
            object.array("assumptions", assumptions -> row.seed().assumptions().forEach(assumptions::value));
            object.array("hypotheses", hypotheses -> row.hypotheses().forEach(hypotheses::value));
            object.property("counterexampleStatus", row.counterexampleSearchStatus().name());
            object.array("counterexamples", counterexamples -> row.counterexamples().forEach(counterexamples::value));
            object.array("attemptedSources", sources -> row.counterexampleAttemptedSources().forEach(sources::value));
            object.array("inferredAssumptions", assumptions -> row.inferredAssumptions().forEach(assumptions::value));
            object.property("explanation", row.counterexampleExplanation());
            object.array("replayPath", replay -> row.replayPath().forEach(replay::value));
            object.array("rulePath", rules -> row.rulePath().forEach(rules::value));
        })));
        writer.object("semanticGraph", semantic -> {
            semantic.property("renderer", semanticView.renderer());
            semantic.property("rawNodeCount", semanticView.rawNodeCount());
            semantic.property("rawEdgeCount", semanticView.rawEdgeCount());
            semantic.property("semanticNodeCount", semanticView.semanticNodeCount());
            semantic.property("semanticEdgeCount", semanticView.semanticEdgeCount());
            semantic.property("collapsedVariantCount", semanticView.collapsedVariantCount());
            semantic.property("collapsedLowSignalCount", semanticView.collapsedLowSignalCount());
            semantic.array("paths", paths -> semanticView.paths().forEach(path -> paths.objectValue(pathObject -> {
                pathObject.property("seedId", path.seedId());
                pathObject.array("nodes", nodes -> path.nodes().forEach(node -> nodes.objectValue(nodeObject -> {
                    nodeObject.property("id", node.id());
                    nodeObject.property("label", node.label());
                })));
                pathObject.array("edges", edges -> path.edges().forEach(edge -> edges.objectValue(edgeObject -> {
                    edgeObject.property("from", edge.from());
                    edgeObject.property("to", edge.to());
                    edgeObject.property("label", edge.label());
                    edgeObject.property("kind", edge.kind());
                    edgeObject.property("collapsedCount", edge.collapsedCount());
                })));
            })));
        });
        writer.endObject();
        return writer.toString();
    }

    public String renderHypothesesExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.hypotheses/v1");
        writer.array("hypotheses", arr -> report.rows().forEach(row -> row.hypotheses().forEach(hypothesis ->
            arr.objectValue(object -> {
                object.property("id", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                object.property("seedId", row.seed().id());
                object.property("expression", hypothesis);
                object.property("counterexampleStatus", row.counterexampleSearchStatus().name());
                object.array("attemptedSources", sources -> row.counterexampleAttemptedSources().forEach(sources::value));
                object.array("inferredAssumptions", assumptions -> row.inferredAssumptions().forEach(assumptions::value));
                object.property("explanation", row.counterexampleExplanation());
                object.property("evidenceCount", row.replayPath().size());
                object.array("assumptions", assumptions -> row.seed().assumptions().forEach(assumptions::value));
                object.array("interestingnessReasons", reasons -> {
                    reasons.value("supported-by-replay-path");
                    if (!row.counterexamples().isEmpty()) {
                        reasons.value("counterexample-search-performed");
                    }
                    if (row.summary().toLowerCase(java.util.Locale.ROOT).contains("proof")) {
                        reasons.value("proof-mentioned-in-summary");
                    }
                });
            }))));
        writer.endObject();
        return writer.toString();
    }

    public String renderMacroRulesExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.macro-rules/v1");
        writer.array("macroRules", arr -> report.rows().forEach(row -> {
            for (String step : row.replayPath()) {
                if (step.toLowerCase(java.util.Locale.ROOT).contains("macro")) {
                    arr.objectValue(object -> {
                        object.property("id", stableArtifactId("macro", row.seed().id(), step));
                        object.property("seedId", row.seed().id());
                        object.property("sourceStep", step);
                        object.property("usefulness", "observed-in-replay");
                    });
                }
            }
        }));
        writer.endObject();
        return writer.toString();
    }

    public String renderCounterexamplesExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.counterexamples/v1");
        writer.array("counterexamples", arr -> report.rows().forEach(row -> {
            if (row.counterexamples().isEmpty()) {
                arr.objectValue(object -> {
                    object.property("id", stableArtifactId("counterexample-attempt", row.seed().id(), row.counterexampleSearchStatus().name()));
                    object.property("seedId", row.seed().id());
                    object.property("counterexampleStatus", row.counterexampleSearchStatus().name());
                    object.property("description", "");
                    object.property("counterexample", "");
                    object.property("refutesHypothesis", "");
                    object.array("attemptedSources", sources -> row.counterexampleAttemptedSources().forEach(sources::value));
                    object.array("inferredAssumptions", assumptions -> row.inferredAssumptions().forEach(assumptions::value));
                    object.property("explanation", row.counterexampleExplanation());
                });
            }
            row.counterexamples().forEach(counterexample -> arr.objectValue(object -> {
                object.property("id", stableArtifactId("counterexample", row.seed().id(), counterexample));
                object.property("seedId", row.seed().id());
                object.property("counterexampleStatus", row.counterexampleSearchStatus().name());
                object.property("description", counterexample);
                object.property("counterexample", counterexample);
                object.property("refutesHypothesis", row.hypotheses().isEmpty() ? "" : row.hypotheses().getFirst());
                object.array("attemptedSources", sources -> row.counterexampleAttemptedSources().forEach(sources::value));
                object.array("inferredAssumptions", assumptions -> row.inferredAssumptions().forEach(assumptions::value));
                object.property("explanation", row.counterexampleExplanation());
            }));
        }));
        writer.endObject();
        return writer.toString();
    }

    public String renderProvenanceGraphExport(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.provenance-graph/v1");
        writer.array("nodes", nodes -> report.rows().forEach(row -> {
            nodes.objectValue(node -> {
                node.property("id", "seed:" + row.seed().id());
                node.property("type", "Seed");
                node.property("label", row.seed().stableKey());
            });
            nodes.objectValue(node -> {
                node.property("id", "search-run:" + row.seed().id());
                node.property("type", "SearchRun");
                node.property("label", row.summary());
            });
            row.hypotheses().forEach(hypothesis -> nodes.objectValue(node -> {
                node.property("id", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                node.property("type", "Hypothesis");
                node.property("label", hypothesis);
                node.property("counterexampleStatus", row.counterexampleSearchStatus().name());
            }));
            row.hypotheses().forEach(hypothesis -> nodes.objectValue(node -> {
                node.property("id", stableArtifactId("counterexample-attempt", row.seed().id(), hypothesis));
                node.property("type", "CounterexampleSearchAttempt");
                node.property("label", "Counterexample search for " + hypothesis);
                node.property("status", row.counterexampleSearchStatus().name());
                node.array("attemptedSources", sources -> row.counterexampleAttemptedSources().forEach(sources::value));
                node.array("inferredAssumptions", assumptions -> row.inferredAssumptions().forEach(assumptions::value));
                node.property("explanation", row.counterexampleExplanation());
            }));
            row.hypotheses().forEach(hypothesis -> nodes.objectValue(node -> {
                node.property("id", stableArtifactId("symreg", row.seed().id(), hypothesis));
                node.property("type", "SymbolicRegressionProposal");
                node.property("label", hypothesis);
            }));
            row.hypotheses().forEach(hypothesis -> nodes.objectValue(node -> {
                node.property("id", stableArtifactId("cas-attempt", row.seed().id(), hypothesis));
                node.property("type", "CASAttempt");
                node.property("label", "CAS verification attempt for " + hypothesis);
            }));
            row.counterexamples().forEach(counterexample -> nodes.objectValue(node -> {
                node.property("id", stableArtifactId("counterexample", row.seed().id(), counterexample));
                node.property("type", "Counterexample");
                node.property("label", counterexample);
                node.property("status", row.counterexampleSearchStatus().name());
            }));
            for (int i = 0; i < row.replayPath().size(); i++) {
                int index = i;
                nodes.objectValue(node -> {
                    node.property("id", "replay:" + row.seed().id() + ":" + index);
                    node.property("type", "SupportingPath");
                    node.property("label", row.replayPath().get(index));
                });
            }
        }));
        writer.array("edges", edges -> report.rows().forEach(row -> {
            edges.objectValue(edge -> {
                edge.property("from", "seed:" + row.seed().id());
                edge.property("to", "search-run:" + row.seed().id());
                edge.property("type", "SEEDED");
            });
            row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                edge.property("from", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                edge.property("to", "seed:" + row.seed().id());
                edge.property("type", "DERIVED_FROM");
            }));
            row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                edge.property("from", stableArtifactId("symreg", row.seed().id(), hypothesis));
                edge.property("to", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                edge.property("type", "PROPOSES");
            }));
            row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                edge.property("from", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                edge.property("to", stableArtifactId("cas-attempt", row.seed().id(), hypothesis));
                edge.property("type", "CHECKED_BY");
            }));
            row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                edge.property("from", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                edge.property("to", stableArtifactId("counterexample-attempt", row.seed().id(), hypothesis));
                edge.property("type", "HYPOTHESIS_TESTED_BY");
                edge.property("status", row.counterexampleSearchStatus().name());
            }));
            row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                edge.property("from", stableArtifactId("counterexample-attempt", row.seed().id(), hypothesis));
                edge.property("to", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                edge.property("type", switch (row.counterexampleSearchStatus()) {
                    case COUNTEREXAMPLE_FOUND -> "FOUND_COUNTEREXAMPLE";
                    case NO_COUNTEREXAMPLE_FOUND -> "NO_COUNTEREXAMPLE_WITHIN_BUDGET";
                    case INCONCLUSIVE -> "INCONCLUSIVE_DUE_TO";
                });
            }));
            row.counterexamples().forEach(counterexample -> edges.objectValue(edge -> {
                edge.property("from", row.hypotheses().isEmpty() ? "search-run:" + row.seed().id()
                    : stableArtifactId("hypothesis", row.seed().id(), row.hypotheses().getFirst()));
                edge.property("to", stableArtifactId("counterexample", row.seed().id(), counterexample));
                edge.property("type", row.hypotheses().isEmpty() ? "GENERATED" : "HAS_COUNTEREXAMPLE");
            }));
            if (!row.hypotheses().isEmpty()) {
                row.counterexamples().forEach(counterexample -> edges.objectValue(edge -> {
                    edge.property("from", stableArtifactId("hypothesis", row.seed().id(), row.hypotheses().getFirst()));
                    edge.property("to", stableArtifactId("counterexample", row.seed().id(), counterexample));
                    edge.property("type", "REFUTED_BY");
                }));
            }
            for (int i = 0; i < row.replayPath().size(); i++) {
                int index = i;
                edges.objectValue(edge -> {
                    edge.property("from", "search-run:" + row.seed().id());
                    edge.property("to", "replay:" + row.seed().id() + ":" + index);
                    edge.property("type", "FOUND_PATH");
                });
                row.hypotheses().forEach(hypothesis -> edges.objectValue(edge -> {
                    edge.property("from", stableArtifactId("hypothesis", row.seed().id(), hypothesis));
                    edge.property("to", "replay:" + row.seed().id() + ":" + index);
                    edge.property("type", "SUPPORTED_BY");
                }));
            }
        }));
        writer.endObject();
        return writer.toString();
    }

    public String renderReproducibilityPack(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        List<Path> artifacts,
        Collection<MathematicalAlgorithmRegistry.AlgorithmDescriptor> algorithmSnapshot
    ) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.property("schema", "regelsuche.reproducibility-pack/v1");
        writer.property("seedSetHash", sha256Utf8Lines(report.rows().stream()
            .map(row -> row.seed().stableKey())
            .sorted()
            .toList()));
        writer.object("toolchain", toolchain -> {
            toolchain.property("javaVersion", System.getProperty("java.version", ""));
            toolchain.property("javaVendor", System.getProperty("java.vendor", ""));
            toolchain.property("osName", System.getProperty("os.name", ""));
            toolchain.property("gradleVersion", System.getProperty("gradle.version", ""));
            toolchain.property("userLanguage", System.getProperty("user.language", ""));
        });
        writer.property("gitCommit", gitCommit());
        writer.array("dependencies", dependencies -> classpathEntries().forEach(entry -> dependencies.objectValue(object -> {
            object.property("file", entry.getFileName().toString());
            object.property("sha256", Files.isRegularFile(entry) ? checksum(entry) : "");
        })));
        writer.object("command", command -> {
            command.property("main", "DiscoveryReplayArtifactWriter");
            command.property("processedSeeds", report.metrics().processedSeeds());
        });
        writer.object("discoveryState", state -> {
            state.array("seedCategories", categories -> report.rows().stream()
                .map(row -> row.seed().category())
                .distinct()
                .sorted()
                .forEach(categories::value));
            state.array("activeAlgorithmIds", active -> (algorithmSnapshot == null ? List
                .<MathematicalAlgorithmRegistry.AlgorithmDescriptor>of() : algorithmSnapshot.stream()
                .filter(MathematicalAlgorithmRegistry.AlgorithmDescriptor::enabled)
                .sorted(Comparator.comparing(MathematicalAlgorithmRegistry.AlgorithmDescriptor::id))
                .toList())
                .forEach(descriptor -> active.value(descriptor.id())));
            state.array("enabledBackends", active -> (algorithmSnapshot == null ? List
                .<MathematicalAlgorithmRegistry.AlgorithmDescriptor>of() : algorithmSnapshot.stream()
                .filter(MathematicalAlgorithmRegistry.AlgorithmDescriptor::enabled)
                .filter(DiscoveryReplayArtifactWriter::isBackend)
                .sorted(Comparator.comparing(MathematicalAlgorithmRegistry.AlgorithmDescriptor::id))
                .toList())
                .forEach(descriptor -> active.value(descriptor.id())));
        });
        writer.array("algorithmRegistry", algorithms -> (algorithmSnapshot == null ? List
            .<MathematicalAlgorithmRegistry.AlgorithmDescriptor>of() : algorithmSnapshot.stream()
            .sorted(Comparator.comparing(MathematicalAlgorithmRegistry.AlgorithmDescriptor::id)).toList())
            .forEach(descriptor -> algorithms.objectValue(object -> {
                object.property("id", descriptor.id());
                object.property("enabled", descriptor.enabled());
                object.property("proofSemantics", descriptor.proofSemantics().name());
                object.property("maxSteps", descriptor.budget().maxSteps());
                object.property("maxStates", descriptor.budget().maxStates());
                object.property("maxCoefficient", descriptor.budget().maxCoefficient());
                object.property("tolerance", descriptor.budget().tolerance());
                object.property("maxTerms", descriptor.budget().maxTerms());
                object.property("maxDegree", descriptor.budget().maxDegree());
                object.property("maxVariables", descriptor.budget().maxVariables());
            })));
        writer.array("proofHistory", history -> report.rows().forEach(row -> history.objectValue(object -> {
            object.property("seedId", row.seed().id());
            object.property("success", row.success());
            object.array("hypotheses", hypotheses -> row.hypotheses().forEach(hypotheses::value));
            object.array("counterexamples", counterexamples -> row.counterexamples().forEach(counterexamples::value));
            object.property("summary", row.summary());
        })));
        writer.object(CONTAINER_SECTION_KEY, container -> {
            Path containerFile = Path.of(CONTAINER_FILE_NAME);
            container.property(CONTAINER_FILE_KEY, containerFile.toString());
            container.property(CONTAINER_FILE_SHA_KEY, Files.isRegularFile(containerFile) ? checksum(containerFile) : "");
            container.property("image", System.getenv().getOrDefault(CONTAINER_IMAGE_ENV, ""));
        });
        writer.array("artifacts", arr -> (artifacts == null ? List.<Path>of() : artifacts).stream()
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(path -> arr.objectValue(object -> {
                object.property("file", path.getFileName().toString());
                object.property("sha256", checksum(path));
            })));
        writer.endObject();
        return writer.toString();
    }

    private static List<Path> classpathEntries() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return List.of();
        }
        return Arrays.stream(classpath.split(java.io.File.pathSeparator))
            .map(Path::of)
            .filter(path -> Files.isRegularFile(path) && path.getFileName() != null)
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .limit(200)
            .toList();
    }

    private static boolean isBackend(MathematicalAlgorithmRegistry.AlgorithmDescriptor descriptor) {
        return descriptor.id().endsWith("Backend")
            || MathematicalAlgorithmRegistry.PSLQ.equals(descriptor.id())
            || MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH.equals(descriptor.id());
    }

    private static List<String> enabledBackends(Collection<MathematicalAlgorithmRegistry.AlgorithmDescriptor> algorithmSnapshot) {
        return (algorithmSnapshot == null ? List.<MathematicalAlgorithmRegistry.AlgorithmDescriptor>of() : algorithmSnapshot.stream()
            .filter(MathematicalAlgorithmRegistry.AlgorithmDescriptor::enabled)
            .filter(DiscoveryReplayArtifactWriter::isBackend)
            .sorted(Comparator.comparing(MathematicalAlgorithmRegistry.AlgorithmDescriptor::id))
            .toList())
            .stream()
            .map(MathematicalAlgorithmRegistry.AlgorithmDescriptor::id)
            .toList();
    }

    private static String gitCommit() {
        String fromProperty = System.getProperty("regelsuche.git.commit", "");
        if (!fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnvironment = System.getenv().getOrDefault("GITHUB_SHA", "");
        if (!fromEnvironment.isBlank()) {
            return fromEnvironment;
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim();
            if (process.waitFor() == 0 && !output.isBlank()) {
                return output;
            }
        } catch (IOException exception) {
            return "unknown";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
        return "unknown";
    }

    private void writeScreenshot(Path path,
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView) throws IOException {
        if (!ImageIO.write(renderFrame(report, semanticView, 0), "png", path.toFile())) {
            throw new IOException("No ImageIO writer for png");
        }
    }

    private void writeReplayGif(Path path,
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").hasNext()
            ? ImageIO.getImageWritersByFormatName("gif").next()
            : null;
        if (writer == null) {
            throw new IOException("No ImageIO writer for gif");
        }
        try (ImageOutputStream output = ImageIO.createImageOutputStream(path.toFile())) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            int frames = Math.max(2, semanticView.paths().stream().mapToInt(pathView -> Math.max(1, pathView.nodes().size())).max().orElse(2));
            for (int i = 0; i < frames; i++) {
                writer.writeToSequence(new javax.imageio.IIOImage(renderFrame(report, semanticView, i), null, null), params);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private BufferedImage renderFrame(DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView,
        int frame) {
        int width = 480;
        int height = 180;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(35, 78, 112));
            graphics.fillRect(0, 0, width, 42);
            graphics.setColor(Color.WHITE);
            graphics.drawString("Regelsuche Semantic Discovery View", 20, 27);
            graphics.setColor(new Color(31, 41, 55));
            graphics.drawString("Main path: " + semanticView.semanticNodeCount() + " nodes", 20, 64);
            graphics.drawString("Collapsed low-signal: " + semanticView.collapsedLowSignalCount(), 20, 82);
            graphics.drawString("Counterexample status: " + aggregateCounterexampleStatus(report), 20, 100);
            drawSemanticMainPath(graphics, semanticView, frame);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void drawSemanticMainPath(Graphics2D graphics,
        DiscoverySemanticReportView semanticView,
        int frame) {
        List<DiscoverySemanticReportView.SemanticNode> nodes = semanticView.paths().stream()
            .findFirst()
            .map(DiscoverySemanticReportView.SemanticPath::nodes)
            .orElse(List.of());
        if (nodes.isEmpty()) {
            graphics.setColor(new Color(107, 114, 128));
            graphics.drawString("No semantic path available", 220, 106);
            return;
        }
        int visible = Math.min(nodes.size(), Math.max(1, frame + 1));
        int left = 220;
        int right = 450;
        int y = 112;
        int spacing = visible == 1 ? 0 : (right - left) / (visible - 1);
        graphics.setStroke(new BasicStroke(2.5f));
        graphics.setColor(new Color(124, 58, 237));
        for (int i = 0; i < visible - 1; i++) {
            int x1 = left + i * spacing;
            int x2 = left + (i + 1) * spacing;
            graphics.drawLine(x1 + 12, y, x2 - 12, y);
        }
        for (int i = 0; i < visible; i++) {
            int x = left + i * spacing;
            graphics.setColor(i == visible - 1 ? new Color(22, 163, 74) : new Color(59, 130, 246));
            graphics.fillOval(x - 12, y - 12, 24, 24);
            graphics.setColor(Color.WHITE);
            graphics.drawString(String.valueOf(i + 1), x - 4, y + 5);
            graphics.setColor(new Color(31, 41, 55));
            String label = nodes.get(i).label();
            graphics.drawString(label.substring(0, Math.min(16, label.length())), x - 24, y + 32);
        }
    }

    private String renderGeneratedGallery(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        DiscoverySemanticReportView semanticView
    ) {
        StringBuilder out = new StringBuilder("## Generated Discovery Gallery\n\n");
        boolean emitted = false;
        for (DeterministicDiscoveryExperimentRunner.SeedRunReport row : report.rows()) {
            if (row.replayPath().isEmpty()) {
                continue;
            }
            if (row.seed().expression().equals("x^4 + 4")
                && row.rulePath().contains("hypothesis_difference_of_squares_preparation")
                && row.rulePath().contains("ast_square_difference_factor")) {
                emitted = true;
                out.append("### Sophie-Germain discovery replay\n\n")
                    .append("- input: `").append(row.seed().expression()).append("`\n")
                    .append("- discovered bridge: `").append(bridgeFrom(row.replayPath())).append("`\n")
                    .append("- factored output: `").append(row.replayPath().getLast()).append("`\n")
                    .append("- rules used: ").append(escapeMarkdown(String.join(" -> ", row.rulePath()))).append('\n')
                    .append("- proof/equivalence status: ").append(row.counterexampleSearchStatus().name()).append('\n')
                    .append("- replay source: generated search/replay path in this report\n\n")
                    .append("```mermaid\n").append(renderSemanticMermaid(semanticView)).append("```\n\n");
            }
            if (row.resultKind().hasMacroReuse() || row.rulePath().stream().anyMatch(rule -> rule.contains("macro"))) {
                emitted = true;
                out.append("### Learned macro reuse\n\n")
                    .append("- input discovery: `").append(row.seed().expression()).append("`\n")
                    .append("- extracted/reused macro evidence: ").append(escapeMarkdown(String.join(" -> ", row.rulePath()))).append('\n')
                    .append("- validation examples: generated by the macro-learning/replay run\n")
                    .append("- limitation: structural matching plus normalization, not full equivalence-class matching\n\n");
            }
        }
        if (!emitted) {
            out.append("- No gallery entry emitted: no qualifying generated replay or macro-reuse artifact in this run.\n\n");
        }
        return out.toString();
    }

    private String bridgeFrom(List<String> replayPath) {
        return replayPath.stream()
            .filter(SquareDifferenceAstPredicate::containsSquareDifference)
            .findFirst()
            .orElse(replayPath.getLast());
    }

    private boolean isBridgeKind(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        return row.resultKind().hasBridge();
    }

    private boolean isTransformedKind(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        return row.resultKind().hasTransformedResult();
    }

    private boolean isMacroLearnedKind(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        return row.resultKind().hasMacroLearning();
    }

    private String operatorLabel(List<String> rulePath) {
        if (rulePath.stream().anyMatch(rule -> rule.contains("complete_square"))) {
            return "complete-square";
        }
        if (rulePath.stream().anyMatch(rule -> rule.contains("difference_of_squares"))) {
            return "difference-of-squares";
        }
        return "—";
    }

    private String renderSemanticMermaid(DiscoverySemanticReportView semanticView) {
        StringBuilder builder = new StringBuilder("graph TD\n");
        for (DiscoverySemanticReportView.SemanticPath path : semanticView.paths()) {
            if (path.nodes().isEmpty()) {
                String seedId = mermaidId("seed", path.seedId());
                builder.append("  ").append(seedId).append("[\"").append(escapeMermaid(path.seedId())).append("\"]\n");
                continue;
            }
            for (DiscoverySemanticReportView.SemanticNode node : path.nodes()) {
                builder.append("  ").append(mermaidId(path.seedId(), node.id()))
                    .append("[\"").append(escapeMermaid(node.label())).append("\"]\n");
            }
            for (DiscoverySemanticReportView.SemanticEdge edge : path.edges()) {
                builder.append("  ").append(mermaidId(path.seedId(), edge.from()))
                    .append(" -->|").append(escapeMermaid(edge.label())).append("| ")
                    .append(mermaidId(path.seedId(), edge.to())).append('\n');
            }
        }
        return builder.toString();
    }

    private String mermaidId(String prefix, String value) {
        return "semantic_" + sha256Utf8Lines(List.of(prefix == null ? "" : prefix, value == null ? "" : value)).substring(0, 12);
    }

    private String escapeMermaid(String value) {
        return (value == null ? "" : value).replace("\"", "'");
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private String escapeMarkdown(String value) {
        return (value == null ? "" : value).replace("|", "\\|");
    }

    private String metricCard(String label, String value) {
        return "<div class=\"card\"><div class=\"muted\">" + escape(label)
            + "</div><div class=\"metric\">" + escape(value) + "</div></div>";
    }

    private String renderHtmlDiscoveryDetails(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        StringBuilder out = new StringBuilder();
        out.append("<div><strong>Annahmen</strong>: ").append(renderPills(row.seed().assumptions())).append("</div>");
        out.append("<div><strong>Hypothesen</strong>: ").append(renderPills(row.hypotheses())).append("</div>");
        out.append("<div><strong>Counterexample Search</strong>: ").append(renderCounterexampleStatusLabel(row)).append("</div>");
        out.append("<div><strong>Quellen</strong>: ").append(renderPills(row.counterexampleAttemptedSources())).append("</div>");
        out.append("<div><strong>Abgeleitete Annahmen</strong>: ").append(renderPills(row.inferredAssumptions())).append("</div>");
        out.append("<div><strong>Gegenbeispiele</strong>: ").append(renderPills(row.counterexamples())).append("</div>");
        if (!row.counterexampleExplanation().isBlank()) {
            out.append("<div class=\"muted\">").append(escape(row.counterexampleExplanation())).append("</div>");
        }
        return out.toString();
    }

    private String renderCounterexampleStatusLabel(DeterministicDiscoveryExperimentRunner.SeedRunReport row) {
        return switch (row.counterexampleSearchStatus()) {
            case COUNTEREXAMPLE_FOUND -> "<span class=\"fail\">counterexampleStatus: COUNTEREXAMPLE_FOUND</span>";
            case NO_COUNTEREXAMPLE_FOUND -> "<span class=\"neutral\">counterexampleStatus: NO_COUNTEREXAMPLE_FOUND</span>";
            case INCONCLUSIVE -> "<span class=\"unknown\">counterexampleStatus: INCONCLUSIVE</span>";
        };
    }

    private String aggregateCounterexampleStatus(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        if (report.rows().stream().anyMatch(row ->
            row.counterexampleSearchStatus() == de.regelsuche.validation.CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND)) {
            return "COUNTEREXAMPLE_FOUND";
        }
        if (report.rows().stream().anyMatch(row ->
            row.counterexampleSearchStatus() == de.regelsuche.validation.CounterexampleSearchService.Status.INCONCLUSIVE)) {
            return "INCONCLUSIVE";
        }
        return "NO_COUNTEREXAMPLE_FOUND";
    }

    private String renderPills(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "<span class=\"muted\">–</span>";
        }
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            out.append("<span class=\"pill\">").append(escape(value)).append("</span>");
        }
        return out.toString();
    }

    private String joinOrDash(java.util.List<String> values) {
        return values == null || values.isEmpty() ? "–" : String.join(", ", values);
    }

    private DiscoveryDashboardMetrics dashboardMetrics(DeterministicDiscoveryExperimentRunner.DiscoveryReport report) {
        return DiscoveryDashboardMetrics.from(report, Map.ofEntries(
            Map.entry("json", 1),
            Map.entry("html", 1),
            Map.entry("markdown", 1),
            Map.entry("replayJson", 1),
            Map.entry("screenshotPng", 1),
            Map.entry("replayGif", 1),
            Map.entry("reproducibilityPack", 1),
            Map.entry("hypotheses", 1),
            Map.entry("macroRules", 1),
            Map.entry("counterexamples", 1),
            Map.entry("provenanceGraph", 1),
            Map.entry("campaign", 1)
        ));
    }

    public record ArtifactBundle(
        Path jsonReport,
        Path htmlReport,
        Path markdownReport,
        Path replayJson,
        Path screenshotPng,
        Path replayGif,
        Path reproducibilityPack,
        Path hypothesesJson,
        Path macroRulesJson,
        Path counterexamplesJson,
        Path provenanceGraphJson,
        Path campaignJson
    ) {
    }

    private static String stableArtifactId(String type, String seedId, String value) {
        return type + ":" + sha256Utf8Lines(List.of(seedId == null ? "" : seedId, value == null ? "" : value)).substring(0, 16);
    }

    private static String checksum(Path path) {
        try {
            MessageDigest digest = newSha256Digest();
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not checksum " + path, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(newSha256Digest().digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String sha256Utf8Lines(List<String> lines) {
        try {
            MessageDigest digest = newSha256Digest();
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    digest.update((byte) '\n');
                }
                digest.update(lines.get(i).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static MessageDigest newSha256Digest() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }
}
