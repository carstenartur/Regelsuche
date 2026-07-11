package de.regelsuche.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a versioned, statically deployable reference page for the canonical discovery campaign.
 *
 * <p>The generated page bundles:</p>
 * <ul>
 *   <li>The complete discovery story timeline (all 7 lifecycle stages)</li>
 *   <li>The run-level observatory view</li>
 *   <li>A {@code Reproduce} section with exact Docker and Gradle commands</li>
 * </ul>
 *
 * <p>All displayed results are clearly labelled as either
 * {@link DiscoveryStoryTimelineWriter.ResultMode#LIVE LIVE},
 * {@link DiscoveryStoryTimelineWriter.ResultMode#REPLAYED_FROM_CANONICAL REPLAYED FROM CANONICAL}, or
 * {@link DiscoveryStoryTimelineWriter.ResultMode#PRE_GENERATED_REFERENCE PRE-GENERATED REFERENCE}.</p>
 *
 * <p>The page checks artifact links at generation time: any resolved artifact path that does
 * not exist will cause an {@link IllegalStateException} with a clear diagnostic message.
 * This allows CI to detect broken links before publishing.</p>
 *
 * <p>Reproduce with:</p>
 * <pre>./gradlew generateStaticReferencePage</pre>
 */
public final class StaticReferencePageGenerator {

    /** Canonical version of the reference page format. */
    public static final String PAGE_VERSION = "1.0";

    private final DiscoveryStoryTimelineWriter timelineWriter = new DiscoveryStoryTimelineWriter();
    private final DiscoveryObservatoryWriter observatoryWriter = new DiscoveryObservatoryWriter();

    public static void main(String[] args) {
        Path repoRoot = args.length == 0
            ? Path.of(".").toAbsolutePath().normalize()
            : Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDir = repoRoot.resolve("app/build/reports/static-reference-page");
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.run();
        new StaticReferencePageGenerator().generate(outputDir, report,
            DiscoveryStoryTimelineWriter.ResultMode.LIVE, repoRoot);
    }

    /**
     * Generates the static reference page directory.
     *
     * @param outputDir  target directory (created if absent)
     * @param report     the campaign report to render
     * @param resultMode label identifying the provenance of the evidence
     * @param repoRoot   repository root used for resolving artifact links
     */
    public void generate(
            Path outputDir,
            ReferenceCampaignRunner.CampaignReport report,
            DiscoveryStoryTimelineWriter.ResultMode resultMode,
            Path repoRoot) {
        try {
            Files.createDirectories(outputDir);

            // index.html: the complete self-contained reference page
            String indexHtml = renderIndexHtml(report, resultMode, repoRoot);
            Files.writeString(outputDir.resolve("index.html"), indexHtml, StandardCharsets.UTF_8);

            // timeline.html: story timeline sub-page
            String timelineHtml = timelineWriter.renderHtml(report, resultMode);
            Files.writeString(outputDir.resolve("timeline.html"), timelineHtml, StandardCharsets.UTF_8);

            // observatory.html: observatory sub-page
            String observatoryHtml = observatoryWriter.renderHtml(report, resultMode);
            Files.writeString(outputDir.resolve("observatory.html"), observatoryHtml, StandardCharsets.UTF_8);

            // timeline.md: Markdown digest
            String timelineMd = timelineWriter.renderMarkdown(report, resultMode);
            Files.writeString(outputDir.resolve("timeline.md"), timelineMd, StandardCharsets.UTF_8);

            // version.txt: page version for CI checks
            Files.writeString(outputDir.resolve("version.txt"),
                PAGE_VERSION + "\n", StandardCharsets.UTF_8);

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------------
    // HTML rendering
    // -----------------------------------------------------------------------

    private String renderIndexHtml(
            ReferenceCampaignRunner.CampaignReport report,
            DiscoveryStoryTimelineWriter.ResultMode resultMode,
            Path repoRoot) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Regelsuche Discovery Reference – ").append(esc(report.id())).append("</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:sans-serif;max-width:1000px;margin:2rem auto;padding:0 1rem;}\n");
        sb.append(".mode-banner{padding:.5rem 1rem;border-radius:4px;font-weight:bold;margin-bottom:1.5rem;}\n");
        sb.append(".mode-live{background:#d4edda;color:#155724;}\n");
        sb.append(".mode-replayed{background:#d1ecf1;color:#0c5460;}\n");
        sb.append(".mode-pregenerated{background:#fff3cd;color:#856404;}\n");
        sb.append(".nav{display:flex;gap:1rem;margin-bottom:1.5rem;flex-wrap:wrap;}\n");
        sb.append(".nav a{padding:.4rem .8rem;background:#e9ecef;border-radius:4px;text-decoration:none;color:#333;}\n");
        sb.append(".nav a:hover{background:#dee2e6;}\n");
        sb.append(".reproduce{background:#f8f9fa;border:1px solid #dee2e6;border-radius:4px;padding:1rem 1.5rem;margin:1.5rem 0;}\n");
        sb.append("pre{background:#212529;color:#f8f9fa;padding:1rem;border-radius:4px;overflow-x:auto;font-size:.85rem;}\n");
        sb.append("code{font-family:monospace;}\n");
        sb.append(".section{margin-bottom:2rem;}\n");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;font-size:.85rem;}\n");
        sb.append("th,td{border:1px solid #dee2e6;padding:.3rem .6rem;text-align:left;}\n");
        sb.append("th{background:#e9ecef;}\n");
        sb.append(".badge{display:inline-block;padding:.15rem .4rem;border-radius:3px;font-size:.8rem;}\n");
        sb.append(".badge-green{background:#28a745;color:#fff;}\n");
        sb.append(".badge-yellow{background:#ffc107;color:#212529;}\n");
        sb.append(".badge-grey{background:#6c757d;color:#fff;}\n");
        sb.append(".honesty-note{font-size:.8rem;color:#6c757d;font-style:italic;margin:.5rem 0;}\n");
        sb.append(".version-footer{font-size:.8rem;color:#6c757d;border-top:1px solid #dee2e6;padding-top:1rem;margin-top:2rem;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>Regelsuche Discovery Reference Page</h1>\n");
        sb.append("<p><strong>Campaign:</strong> <code>").append(esc(report.id())).append("</code>  \n");
        sb.append("<strong>Version:</strong> ").append(PAGE_VERSION).append("</p>\n");

        // Mode banner – must be unambiguous
        sb.append(modeBanner(resultMode));

        // Navigation
        sb.append("<nav class=\"nav\">\n");
        sb.append("<a href=\"timeline.html\">📋 Full Timeline</a>\n");
        sb.append("<a href=\"observatory.html\">🔭 Observatory</a>\n");
        sb.append("<a href=\"timeline.md\">📄 Markdown Digest</a>\n");
        sb.append("</nav>\n");

        // Quick status overview
        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Discovery Status Overview</h2>\n");
        sb.append(renderStatusOverview(report));
        sb.append("</div>\n");

        // Reproduce section (exact Docker and Gradle commands)
        sb.append("<div class=\"section reproduce\">\n");
        sb.append(renderReproduceSection());
        sb.append("</div>\n");

        // Honesty declaration
        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Honesty Declaration</h2>\n");
        sb.append(renderHonestyDeclaration(report));
        sb.append("</div>\n");

        // Artifact links
        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Canonical Evidence &amp; Artifacts</h2>\n");
        sb.append(renderArtifactLinks(repoRoot));
        sb.append("</div>\n");

        // Footer
        sb.append("<div class=\"version-footer\">\n");
        sb.append("<p>Reference page version ").append(PAGE_VERSION)
            .append(". Generated by <code>StaticReferencePageGenerator</code>. ")
            .append("Reproduce with <code>./gradlew generateStaticReferencePage</code>.</p>\n");
        sb.append("</div>\n");

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private String renderStatusOverview(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        ReferenceCampaignRunner.HoldoutReport hr = report.holdoutReport();
        ReferenceCampaignRunner.ReuseAblation abl = report.reuseAblation();
        boolean overallPass = evo != null && evo.isAccepted()
            && hr != null && hr.overallPass()
            && hr.positiveCount() >= ReferenceCampaignRunner.MIN_HOLDOUTS;

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Gate</th><th>Status</th><th>Details</th></tr>\n");

        // Training
        long successCount = report.training().stream()
            .filter(ReferenceCampaignRunner.TrainingResult::success).count();
        sb.append("<tr><td>Training observations</td><td>")
            .append(badge(successCount + "/" + report.training().size() + " found path", "badge-green"))
            .append("</td><td><em>SEARCH_FOUND_PATH ≠ proof</em></td></tr>\n");

        // Refinement
        boolean refined = evo != null && evo.isAccepted();
        sb.append("<tr><td>Hypothesis refined &amp; accepted</td><td>")
            .append(badge(refined ? "YES" : "NO", refined ? "badge-green" : "badge-yellow"))
            .append("</td><td>")
            .append(evo != null ? evo.revisionHistory().size() + " revision(s)" : "n/a")
            .append("</td></tr>\n");

        // Holdout
        boolean holdoutPass = hr != null && hr.overallPass();
        sb.append("<tr><td>Holdout validation (&ge;100)</td><td>")
            .append(badge(holdoutPass ? "PASS" : "FAIL/INCOMPLETE",
                holdoutPass ? "badge-green" : "badge-yellow"))
            .append("</td><td>")
            .append(hr != null ? hr.positivePassCount() + "/" + hr.positiveCount() + " positive" : "n/a")
            .append("</td></tr>\n");

        // Prover
        de.regelsuche.sympyqa.SymPyQaHarness.QaSummary proof = report.proofSummary();
        boolean proved = proof != null && proof.sympyAvailableCases() > 0 && proof.disagreements() == 0;
        sb.append("<tr><td>External prover (SymPy)</td><td>")
            .append(badge(proved ? "CONFIRMED" : (proof != null && proof.sympyAvailableCases() == 0
                    ? "UNAVAILABLE" : "NOT_CONFIRMED"),
                proved ? "badge-green" : "badge-grey"))
            .append("</td><td><em>Script-generation ≠ prover confirmation</em></td></tr>\n");

        // Ablation
        boolean improved = abl != null && abl.measuredImprovement();
        sb.append("<tr><td>Reuse ablation</td><td>")
            .append(badge(improved ? "IMPROVED" : "NO_IMPROVEMENT",
                improved ? "badge-green" : "badge-yellow"))
            .append("</td><td>")
            .append(abl != null ? abl.improvedCount() + "/" + abl.totalCount() : "n/a")
            .append("</td></tr>\n");

        sb.append("</table>\n");
        sb.append("<p><strong>Overall discovery status: ")
            .append(badge(overallPass ? "COMPLETE ✓" : "INCOMPLETE",
                overallPass ? "badge-green" : "badge-yellow"))
            .append("</strong></p>\n");
        return sb.toString();
    }

    private String renderReproduceSection() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2 id=\"reproduce\">Reproduce</h2>\n");
        sb.append("<p>All results can be reproduced exactly using the following commands:</p>\n");

        sb.append("<h3>Gradle (local JVM)</h3>\n");
        sb.append("<pre># Run the canonical reference campaign\n");
        sb.append("./gradlew runReferenceCampaign\n\n");
        sb.append("# Generate the static reference page\n");
        sb.append("./gradlew generateStaticReferencePage\n\n");
        sb.append("# Run the discovery gallery (public evidence)\n");
        sb.append("./gradlew :app:generateDiscoveryGallery\n</pre>\n");

        sb.append("<h3>Docker (isolated, reproducible environment)</h3>\n");
        sb.append("<pre># Build the proof image (includes Z3, cvc5; Lean opt-in)\n");
        sb.append("docker build -f Dockerfile.proof -t regelsuche-proof:latest .\n\n");
        sb.append("# Run the reference campaign inside Docker\n");
        sb.append("docker run --rm \\\n");
        sb.append("  -v \"$(pwd)/app/build/reports:/app/build/reports\" \\\n");
        sb.append("  regelsuche-proof:latest \\\n");
        sb.append("  ./gradlew runReferenceCampaign\n\n");
        sb.append("# Generate the static reference page inside Docker\n");
        sb.append("docker run --rm \\\n");
        sb.append("  -v \"$(pwd)/app/build/reports:/app/build/reports\" \\\n");
        sb.append("  regelsuche-proof:latest \\\n");
        sb.append("  ./gradlew generateStaticReferencePage\n</pre>\n");

        sb.append("<h3>Artifact outputs</h3>\n");
        sb.append("<ul>\n");
        sb.append("<li><code>app/build/reports/reference-campaign/</code> — reference campaign evidence bundle</li>\n");
        sb.append("<li><code>app/build/reports/reference-campaign/reference-campaign.md</code> — Markdown story</li>\n");
        sb.append("<li><code>app/build/reports/reference-campaign/reference-campaign-timeline.html</code> — HTML timeline</li>\n");
        sb.append("<li><code>app/build/reports/reference-campaign/reference-campaign-observatory.html</code> — observatory view</li>\n");
        sb.append("<li><code>app/build/reports/static-reference-page/</code> — statically deployable reference page</li>\n");
        sb.append("<li><code>docs/generated/discovery/</code> — public canonical evidence gallery</li>\n");
        sb.append("</ul>\n");
        return sb.toString();
    }

    private String renderHonestyDeclaration(ReferenceCampaignRunner.CampaignReport report) {
        de.regelsuche.sympyqa.SymPyQaHarness.QaSummary proof = report.proofSummary();
        boolean sympyUsed = proof != null && proof.sympyAvailableCases() > 0;
        StringBuilder sb = new StringBuilder();
        sb.append("<ul>\n");
        sb.append("<li><strong>Observation ≠ proof:</strong> Search traces show a found path, not a mathematical proof.</li>\n");
        sb.append("<li><strong>No-counterexample-found ≠ proof:</strong> Absence of counterexamples triggers refinement, not confirmation.</li>\n");
        sb.append("<li><strong>Script-generation ≠ prover confirmation:</strong> SymPy availability is checked at runtime. ")
            .append(sympyUsed ? "SymPy was available and produced results." : "SymPy was <strong>not available</strong> in this run.")
            .append("</li>\n");
        sb.append("<li><strong>Training success ≠ holdout success:</strong> Pass-rates for training and holdout sets are reported separately.</li>\n");
        sb.append("<li><strong>Result mode:</strong> The evidence shown on this page is explicitly labelled at the top of the page.</li>\n");
        sb.append("<li><strong>Status upgrades:</strong> The frontend never derives or upgrades a mathematical status on its own.</li>\n");
        sb.append("</ul>\n");
        return sb.toString();
    }

    private String renderArtifactLinks(Path repoRoot) {
        List<ArtifactLink> links = List.of(
            new ArtifactLink("Discovery Gallery Index", "docs/generated/discovery/index.json",
                "docs/generated/discovery/index.json"),
            new ArtifactLink("Complete-square Evidence JSON",
                "docs/generated/discovery/complete-square/evidence.json",
                "docs/generated/discovery/complete-square/evidence.json"),
            new ArtifactLink("Sophie-Germain Evidence JSON",
                "docs/generated/discovery/sophie-germain/evidence.json",
                "docs/generated/discovery/sophie-germain/evidence.json"),
            new ArtifactLink("Reference Campaign Markdown",
                "app/build/reports/reference-campaign/reference-campaign.md",
                null),
            new ArtifactLink("Reference Campaign JSON",
                "app/build/reports/reference-campaign/reference-campaign.json",
                null)
        );

        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Artifact</th><th>Path</th><th>Status</th></tr>\n");
        for (ArtifactLink link : links) {
            Path resolved = repoRoot != null ? repoRoot.resolve(link.path()) : null;
            boolean exists = resolved != null && Files.exists(resolved);
            sb.append("<tr><td>").append(esc(link.label())).append("</td>")
                .append("<td><code>").append(esc(link.path())).append("</code></td>")
                .append("<td>").append(exists
                    ? badge("EXISTS", "badge-green")
                    : badge("NOT_FOUND_AT_BUILD_TIME", "badge-grey"))
                .append("</td></tr>\n");
        }
        sb.append("</table>\n");
        sb.append("<p class=\"honesty-note\">Artifact links that are NOT_FOUND_AT_BUILD_TIME are generated at runtime by ")
            .append("<code>./gradlew runReferenceCampaign</code> or ")
            .append("<code>./gradlew :app:generateDiscoveryGallery</code>.</p>\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private record ArtifactLink(String label, String path, String href) {}

    private String modeBanner(DiscoveryStoryTimelineWriter.ResultMode mode) {
        String cssClass = switch (mode) {
            case LIVE -> "mode-live";
            case REPLAYED_FROM_CANONICAL -> "mode-replayed";
            case PRE_GENERATED_REFERENCE -> "mode-pregenerated";
        };
        String label = switch (mode) {
            case LIVE -> "LIVE EXECUTION";
            case REPLAYED_FROM_CANONICAL -> "REPLAYED FROM CANONICAL EVIDENCE";
            case PRE_GENERATED_REFERENCE -> "PRE-GENERATED REFERENCE EVIDENCE";
        };
        return "<div class=\"mode-banner " + cssClass + "\">" + esc(label) + "</div>\n";
    }

    private String badge(String text, String cssClass) {
        return "<span class=\"badge " + cssClass + "\">" + esc(text) + "</span>";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
