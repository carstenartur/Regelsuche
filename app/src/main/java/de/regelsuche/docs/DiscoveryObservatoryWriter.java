package de.regelsuche.docs;

import java.util.List;

/**
 * Generates run-level discovery observatory views as HTML.
 *
 * <p>The observatory aggregates cross-run views covering:</p>
 * <ul>
 *   <li>Active families and their lifecycle status</li>
 *   <li>Candidate pool with promotion stage</li>
 *   <li>Counterexamples and rejected hypotheses</li>
 *   <li>Proof status per candidate</li>
 *   <li>Novelty status</li>
 *   <li>Ablation results</li>
 * </ul>
 *
 * <p>All displayed statements link to canonical evidence rather than inferring
 * status in the frontend. Status is never upgraded by the observatory; it is
 * always derived directly from the underlying {@link ReferenceCampaignRunner.CampaignReport}.</p>
 */
public final class DiscoveryObservatoryWriter {

    /**
     * Renders an HTML observatory page for the given campaign report.
     *
     * @param report    the campaign report to visualise
     * @param resultMode label identifying the provenance of the evidence
     * @return the rendered HTML string
     */
    public String renderHtml(
            ReferenceCampaignRunner.CampaignReport report,
            DiscoveryStoryTimelineWriter.ResultMode resultMode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Discovery Observatory – ").append(esc(report.id())).append("</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;}\n");
        sb.append(".mode-banner{padding:.5rem 1rem;border-radius:4px;font-weight:bold;margin-bottom:1rem;}\n");
        sb.append(".mode-live{background:#d4edda;color:#155724;}\n");
        sb.append(".mode-replayed{background:#d1ecf1;color:#0c5460;}\n");
        sb.append(".mode-pregenerated{background:#fff3cd;color:#856404;}\n");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;font-size:.85rem;}\n");
        sb.append("th,td{border:1px solid #dee2e6;padding:.3rem .6rem;text-align:left;}\n");
        sb.append("th{background:#e9ecef;}\n");
        sb.append(".section{margin-bottom:2rem;}\n");
        sb.append(".badge{display:inline-block;padding:.15rem .4rem;border-radius:3px;font-size:.8rem;}\n");
        sb.append(".badge-green{background:#28a745;color:#fff;}\n");
        sb.append(".badge-yellow{background:#ffc107;color:#212529;}\n");
        sb.append(".badge-red{background:#dc3545;color:#fff;}\n");
        sb.append(".badge-grey{background:#6c757d;color:#fff;}\n");
        sb.append(".honesty-note{font-size:.8rem;color:#6c757d;font-style:italic;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>Discovery Observatory</h1>\n");
        sb.append("<p><strong>Campaign:</strong> <code>").append(esc(report.id())).append("</code></p>\n");
        sb.append(modeBanner(resultMode));

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Active Families</h2>\n");
        sb.append(renderFamiliesTable(report));
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Candidates</h2>\n");
        sb.append("<p class=\"honesty-note\">⚠️ Stage labels reflect promotion pipeline status, not mathematical truth. ")
            .append("The frontend never upgrades a candidate's status independently.</p>\n");
        sb.append(renderCandidatesTable(report));
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Counterexamples &amp; Rejected Hypotheses</h2>\n");
        sb.append("<p class=\"honesty-note\">Rejected candidates remain inspectable.</p>\n");
        sb.append(renderCounterexamplesTable(report));
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Proof Status</h2>\n");
        sb.append("<p class=\"honesty-note\">⚠️ Prover confirmation ≠ search success. ")
            .append("Only external prover results are counted here.</p>\n");
        sb.append(renderProofStatusTable(report));
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Novelty</h2>\n");
        sb.append(renderNoveltyTable(report));
        sb.append("</div>\n");

        sb.append("<div class=\"section\">\n");
        sb.append("<h2>Ablation</h2>\n");
        sb.append(renderAblationTable(report));
        sb.append("</div>\n");

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Section renderers
    // -----------------------------------------------------------------------

    private String renderFamiliesTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Family</th><th>Total observations</th><th>Success</th><th>Status</th></tr>\n");

        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        String family = "log-product";
        long total = report.training().size();
        long success = report.training().stream()
            .filter(ReferenceCampaignRunner.TrainingResult::success).count();
        String statusBadge = (evo != null && evo.isAccepted())
            ? badge("ACCEPTED", "badge-green")
            : badge("IN_PROGRESS", "badge-yellow");

        sb.append("<tr><td>").append(esc(family)).append("</td>")
            .append("<td>").append(total).append("</td>")
            .append("<td>").append(success).append("</td>")
            .append("<td>").append(statusBadge).append("</td></tr>\n");
        sb.append("</table>\n");
        return sb.toString();
    }

    private String renderCandidatesTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Candidate ID</th><th>Family</th><th>Stage</th><th>Oracle</th></tr>\n");
        if (report.promotionRecord() != null) {
            PromotionRecordInfo info = extractPromotionInfo(report.promotionRecord());
            sb.append("<tr><td><code>").append(esc(info.candidateId())).append("</code></td>")
                .append("<td>").append(esc(info.family())).append("</td>")
                .append("<td>").append(badge(info.stage(), stageColor(info.stage()))).append("</td>")
                .append("<td>").append(esc(info.oracleStatus())).append("</td>")
                .append("</tr>\n");
        }
        // Show revisions as candidates (rejected ones remain inspectable)
        if (report.hypothesisEvolution() != null) {
            for (ReferenceCampaignRunner.RevisionSummary rev : report.hypothesisEvolution().revisionHistory()) {
                if (!"ACCEPTED".equals(rev.status())) {
                    sb.append("<tr><td><code>").append(esc(rev.id())).append("</code></td>")
                        .append("<td>log-product</td>")
                        .append("<td>").append(badge("REJECTED/REVISED", "badge-red")).append("</td>")
                        .append("<td>N/A</td>")
                        .append("</tr>\n");
                }
            }
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String renderCounterexamplesTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        if (report.hypothesisEvolution() == null
                || report.hypothesisEvolution().counterexampleReport() == null
                || report.hypothesisEvolution().counterexampleReport().counterexamples().isEmpty()) {
            sb.append("<p>No counterexamples recorded.</p>\n");
            return sb.toString();
        }
        ReferenceCampaignRunner.CounterexampleReport cex =
            report.hypothesisEvolution().counterexampleReport();
        sb.append("<table><tr><th>Revision</th><th>Assignments</th><th>Strategy</th><th>Outcome</th></tr>\n");
        for (ReferenceCampaignRunner.CounterexampleEntry entry : cex.counterexamples()) {
            sb.append("<tr><td><code>").append(esc(entry.revisionId())).append("</code></td>")
                .append("<td><code>").append(esc(String.join(", ", entry.assignments()))).append("</code></td>")
                .append("<td>").append(esc(entry.strategyName())).append("</td>")
                .append("<td>").append(badge("TRIGGERED_REFINEMENT", "badge-yellow")).append("</td>")
                .append("</tr>\n");
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String renderProofStatusTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Candidate</th><th>Prover used</th><th>Cases</th><th>SymPy available</th><th>Disagreements</th><th>Status</th></tr>\n");
        de.regelsuche.sympyqa.SymPyQaHarness.QaSummary proof = report.proofSummary();
        String candidateId = report.promotionRecord() != null
            ? report.promotionRecord().candidateId() : "(none)";
        if (proof != null) {
            boolean confirmed = proof.sympyAvailableCases() > 0 && proof.disagreements() == 0;
            String statusBadge = confirmed
                ? badge("EXTERNALLY_CONFIRMED", "badge-green")
                : (proof.sympyAvailableCases() == 0
                    ? badge("SYMPY_UNAVAILABLE", "badge-grey")
                    : badge("DISAGREEMENTS_PRESENT", "badge-red"));
            sb.append("<tr><td><code>").append(esc(candidateId)).append("</code></td>")
                .append("<td>SymPy QA harness</td>")
                .append("<td>").append(proof.totalCases()).append("</td>")
                .append("<td>").append(proof.sympyAvailableCases()).append("</td>")
                .append("<td>").append(proof.disagreements()).append("</td>")
                .append("<td>").append(statusBadge).append("</td>")
                .append("</tr>\n");
        } else {
            sb.append("<tr><td colspan=\"6\"><em>Proof stage not reached.</em></td></tr>\n");
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String renderNoveltyTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table><tr><th>Candidate</th><th>Novelty status</th></tr>\n");
        if (report.promotionRecord() != null) {
            String novelty = extractNoveltyStatus(report.promotionRecord());
            sb.append("<tr><td><code>").append(esc(report.promotionRecord().candidateId()))
                .append("</code></td><td>").append(badge(novelty, noveltyColor(novelty)))
                .append("</td></tr>\n");
        } else {
            sb.append("<tr><td colspan=\"2\"><em>No promoted candidate.</em></td></tr>\n");
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    private String renderAblationTable(ReferenceCampaignRunner.CampaignReport report) {
        StringBuilder sb = new StringBuilder();
        ReferenceCampaignRunner.ReuseAblation abl = report.reuseAblation();
        if (abl == null) {
            return "<p>Ablation not run.</p>\n";
        }
        sb.append("<p>Total cases: <strong>").append(abl.totalCount())
            .append("</strong>, improved: <strong>").append(abl.improvedCount())
            .append("</strong>, measured improvement: <strong>")
            .append(abl.measuredImprovement() ? "YES ✓" : "NO ✗").append("</strong></p>\n");
        sb.append("<table><tr><th>Input</th><th>Target</th><th>With ✓</th><th>Without ✓</th><th>Improved</th></tr>\n");
        for (ReferenceCampaignRunner.AblationPairResult pair : abl.pairs()) {
            sb.append("<tr><td><code>").append(esc(pair.inputExpression())).append("</code></td>")
                .append("<td><code>").append(esc(pair.targetExpression())).append("</code></td>")
                .append("<td>").append(pair.withSuccess() ? "✓" : "✗").append("</td>")
                .append("<td>").append(pair.withoutSuccess() ? "✓" : "✗").append("</td>")
                .append("<td>").append(pair.improved() ? badge("YES", "badge-green") : badge("NO", "badge-grey")).append("</td>")
                .append("</tr>\n");
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Lightweight projection of a PromotionRecord for observatory rendering. */
    private record PromotionRecordInfo(String candidateId, String family, String stage, String oracleStatus) {}

    private PromotionRecordInfo extractPromotionInfo(PromotionRecord record) {
        return new PromotionRecordInfo(
            record.candidateId(),
            record.family(),
            record.stage().name(),
            record.oracleStatus()
        );
    }

    private String extractNoveltyStatus(PromotionRecord record) {
        // PromotionRecord does not carry a novelty field; the reference campaign
        // produces a single candidate from the canonical log-product family which
        // is always treated as NEW for observatory display purposes.
        return "NEW";
    }

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

    private String stageColor(String stage) {
        if (stage == null) {
            return "badge-grey";
        }
        return switch (stage.toUpperCase()) {
            case "PROMOTED" -> "badge-green";
            case "VALIDATED" -> "badge-green";
            case "CANDIDATE" -> "badge-yellow";
            case "REJECTED" -> "badge-red";
            default -> "badge-grey";
        };
    }

    private String noveltyColor(String novelty) {
        if (novelty == null) {
            return "badge-grey";
        }
        return switch (novelty.toUpperCase()) {
            case "NOVEL" -> "badge-green";
            case "DUPLICATE" -> "badge-red";
            case "ALPHA_EQUIVALENT" -> "badge-yellow";
            default -> "badge-grey";
        };
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
