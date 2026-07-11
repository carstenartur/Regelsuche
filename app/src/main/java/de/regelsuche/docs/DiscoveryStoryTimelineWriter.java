package de.regelsuche.docs;

import java.util.List;

/**
 * Generates HTML and Markdown discovery story timelines from a {@link ReferenceCampaignRunner.CampaignReport}.
 *
 * <p>The timeline follows the canonical 7-stage discovery lifecycle:</p>
 * <ol>
 *   <li><b>Observe</b> – search traces and training observations</li>
 *   <li><b>Generalize</b> – initial conjecture formation</li>
 *   <li><b>Challenge</b> – counterexample search against the conjecture</li>
 *   <li><b>Refine</b> – assumption-guarded refinement</li>
 *   <li><b>Validate</b> – holdout validation on unseen examples</li>
 *   <li><b>Prove</b> – external prover confirmation</li>
 *   <li><b>Learn</b> – promotion and measured reuse improvement</li>
 * </ol>
 *
 * <p>Honesty invariants enforced by this class:</p>
 * <ul>
 *   <li>Observation ≠ proof: search success is labelled {@code SEARCH_FOUND_PATH}, not {@code PROVED}.</li>
 *   <li>No-counterexample-found ≠ proof: absence of counterexamples is surfaced verbatim.</li>
 *   <li>Script generation ≠ prover confirmation: SymPy availability is checked and surfaced.</li>
 *   <li>Training success ≠ holdout success: both pass-rates are shown separately.</li>
 *   <li>Live execution vs. replayed vs. pre-generated are labelled explicitly via {@code resultMode}.</li>
 * </ul>
 */
public final class DiscoveryStoryTimelineWriter {

    /** Identifies how the displayed evidence was produced. */
    public enum ResultMode {
        /** The evidence was produced by a live run of the discovery engine. */
        LIVE,
        /** The evidence was replayed from a canonical evidence bundle. */
        REPLAYED_FROM_CANONICAL,
        /** The evidence was pre-generated for a versioned reference release. */
        PRE_GENERATED_REFERENCE
    }

    /** Writes an HTML research digest. */
    public String renderHtml(ReferenceCampaignRunner.CampaignReport report, ResultMode mode) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Discovery Story Timeline – ").append(esc(report.id())).append("</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:sans-serif;max-width:900px;margin:2rem auto;padding:0 1rem;}\n");
        sb.append(".mode-banner{padding:.5rem 1rem;border-radius:4px;font-weight:bold;margin-bottom:1rem;}\n");
        sb.append(".mode-live{background:#d4edda;color:#155724;}\n");
        sb.append(".mode-replayed{background:#d1ecf1;color:#0c5460;}\n");
        sb.append(".mode-pregenerated{background:#fff3cd;color:#856404;}\n");
        sb.append(".timeline{position:relative;padding-left:2rem;}\n");
        sb.append(".stage{position:relative;margin-bottom:2rem;padding:1rem 1rem 1rem 1.5rem;");
        sb.append("border-left:4px solid #adb5bd;border-radius:0 4px 4px 0;background:#f8f9fa;}\n");
        sb.append(".stage.done{border-left-color:#28a745;}\n");
        sb.append(".stage.inconclusive{border-left-color:#ffc107;}\n");
        sb.append(".stage.blocked{border-left-color:#dc3545;}\n");
        sb.append(".stage-title{font-size:1.1rem;font-weight:bold;margin-bottom:.5rem;}\n");
        sb.append(".stage-badge{display:inline-block;padding:.2rem .5rem;border-radius:3px;");
        sb.append("font-size:.8rem;margin-left:.5rem;}\n");
        sb.append(".badge-done{background:#28a745;color:#fff;}\n");
        sb.append(".badge-inconclusive{background:#ffc107;color:#212529;}\n");
        sb.append(".badge-blocked{background:#dc3545;color:#fff;}\n");
        sb.append(".badge-skipped{background:#6c757d;color:#fff;}\n");
        sb.append(".evidence-link{font-size:.85rem;margin-top:.5rem;}\n");
        sb.append("table{border-collapse:collapse;width:100%;margin:.5rem 0;font-size:.85rem;}\n");
        sb.append("th,td{border:1px solid #dee2e6;padding:.3rem .6rem;text-align:left;}\n");
        sb.append("th{background:#e9ecef;}\n");
        sb.append(".honesty-note{font-size:.8rem;color:#6c757d;font-style:italic;margin-top:.3rem;}\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>Discovery Story Timeline</h1>\n");
        sb.append("<p><strong>Campaign:</strong> <code>").append(esc(report.id())).append("</code></p>\n");
        sb.append(renderModeBanner(mode));

        sb.append("<div class=\"timeline\">\n");
        sb.append(renderObserveStage(report));
        sb.append(renderGeneralizeStage(report));
        sb.append(renderChallengeStage(report));
        sb.append(renderRefineStage(report));
        sb.append(renderValidateStage(report));
        sb.append(renderProveStage(report));
        sb.append(renderLearnStage(report));
        sb.append("</div>\n");

        if (!report.blockers().isEmpty()) {
            sb.append("<h2>Blockers</h2>\n<ul>\n");
            for (String b : report.blockers()) {
                sb.append("<li>").append(esc(b)).append("</li>\n");
            }
            sb.append("</ul>\n");
        }

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    /** Writes a Markdown research digest. */
    public String renderMarkdown(ReferenceCampaignRunner.CampaignReport report, ResultMode mode) {
        StringBuilder out = new StringBuilder();
        out.append("# Discovery Story Timeline\n\n");
        out.append("**Campaign:** `").append(report.id()).append("`  \n");
        out.append("**Evidence mode:** ").append(modeLabel(mode)).append("\n\n");

        out.append("---\n\n");
        out.append("## Stage 1 – Observe\n\n");
        appendObserveMd(out, report);

        out.append("## Stage 2 – Generalize\n\n");
        appendGeneralizeMd(out, report);

        out.append("## Stage 3 – Challenge\n\n");
        appendChallengeMd(out, report);

        out.append("## Stage 4 – Refine\n\n");
        appendRefineMd(out, report);

        out.append("## Stage 5 – Validate\n\n");
        appendValidateMd(out, report);

        out.append("## Stage 6 – Prove\n\n");
        appendProveMd(out, report);

        out.append("## Stage 7 – Learn\n\n");
        appendLearnMd(out, report);

        if (!report.blockers().isEmpty()) {
            out.append("## Blockers\n\n");
            for (String b : report.blockers()) {
                out.append("- ").append(b).append("\n");
            }
            out.append("\n");
        }

        out.append("---\n");
        out.append("_Generated by `DiscoveryStoryTimelineWriter`. Evidence mode: ")
            .append(modeLabel(mode)).append("._\n");
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // HTML stage renderers
    // -----------------------------------------------------------------------

    private String renderModeBanner(ResultMode mode) {
        String cssClass = switch (mode) {
            case LIVE -> "mode-live";
            case REPLAYED_FROM_CANONICAL -> "mode-replayed";
            case PRE_GENERATED_REFERENCE -> "mode-pregenerated";
        };
        return "<div class=\"mode-banner " + cssClass + "\">" + esc(modeLabel(mode)) + "</div>\n";
    }

    private String renderObserveStage(ReferenceCampaignRunner.CampaignReport report) {
        List<ReferenceCampaignRunner.TrainingResult> training = report.training();
        long successCount = training.stream().filter(ReferenceCampaignRunner.TrainingResult::success).count();
        boolean done = !training.isEmpty() && successCount > 0;
        String status = done ? "done" : "inconclusive";
        String badge = done ? "OBSERVED" : "INCONCLUSIVE";

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("1 – Observe", status, badge));
        sb.append("<p>Ran <strong>").append(training.size())
            .append("</strong> training observations; <strong>").append(successCount)
            .append("</strong> found a path.</p>\n");
        sb.append("<p class=\"honesty-note\">⚠️ Search-found-path ≠ mathematical proof. ")
            .append("Counterexamples may still exist.</p>\n");
        if (!training.isEmpty()) {
            sb.append("<table><tr><th>ID</th><th>Input</th><th>Target</th><th>Path found</th></tr>\n");
            for (ReferenceCampaignRunner.TrainingResult t : training) {
                sb.append("<tr><td>").append(esc(t.id())).append("</td>")
                    .append("<td><code>").append(esc(t.inputExpression())).append("</code></td>")
                    .append("<td><code>").append(esc(t.targetExpression())).append("</code></td>")
                    .append("<td>").append(t.success() ? "✓ SEARCH_FOUND_PATH" : "✗ NOT_FOUND").append("</td>")
                    .append("</tr>\n");
            }
            sb.append("</table>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderGeneralizeStage(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        boolean done = evo != null;
        String status = done ? "done" : "blocked";
        String badge = done ? "GENERALIZED" : "BLOCKED";

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("2 – Generalize", status, badge));
        if (evo != null) {
            sb.append("<p>Initial conjecture (intentionally overgeneralised — no positivity assumptions):</p>\n");
            sb.append("<p><code>").append(esc(evo.initialLeftPattern()))
                .append(" = ").append(esc(evo.initialRightPattern())).append("</code></p>\n");
            sb.append("<p class=\"honesty-note\">⚠️ This conjecture is deliberately overgeneralised. ")
                .append("It will be challenged in the next stage.</p>\n");
        } else {
            sb.append("<p>No hypothesis formed.</p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderChallengeStage(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        boolean hasCex = evo != null
            && evo.counterexampleReport() != null
            && !evo.counterexampleReport().counterexamples().isEmpty();
        String status = hasCex ? "done" : (evo != null ? "inconclusive" : "blocked");
        String badge = hasCex ? "CHALLENGED" : (evo != null ? "NO_CEX_FOUND" : "BLOCKED");

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("3 – Challenge", status, badge));
        if (evo != null && evo.counterexampleReport() != null) {
            ReferenceCampaignRunner.CounterexampleReport cex = evo.counterexampleReport();
            sb.append("<p class=\"honesty-note\">⚠️ No-counterexample-found ≠ proof. ")
                .append("Absence of counterexamples only triggers refinement, not confirmation.</p>\n");
            if (hasCex) {
                sb.append("<p>Found <strong>").append(cex.counterexamples().size())
                    .append("</strong> counterexample(s) that triggered refinement:</p>\n");
                sb.append("<ul>\n");
                for (ReferenceCampaignRunner.CounterexampleEntry entry : cex.counterexamples()) {
                    sb.append("<li>Revision <code>").append(esc(entry.revisionId()))
                        .append("</code>: assignments = <code>").append(esc(String.join(", ", entry.assignments())))
                        .append("</code>, strategy = <code>").append(esc(entry.strategyName()))
                        .append("</code></li>\n");
                }
                sb.append("</ul>\n");
            } else {
                sb.append("<p>No counterexamples found (the initial conjecture was not falsified by search).</p>\n");
            }
        } else {
            sb.append("<p>Challenge stage not reached.</p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderRefineStage(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        boolean hasRevisions = evo != null && !evo.revisionHistory().isEmpty();
        String status = (evo != null && evo.isAccepted()) ? "done" : (hasRevisions ? "inconclusive" : "blocked");
        String badge = (evo != null && evo.isAccepted()) ? "REFINED" : (hasRevisions ? "IN_PROGRESS" : "BLOCKED");

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("4 – Refine", status, badge));
        if (evo != null && hasRevisions) {
            sb.append("<p>Ran <strong>").append(evo.revisionHistory().size())
                .append("</strong> revision(s):</p>\n");
            sb.append("<table><tr><th>Revision</th><th>Left</th><th>Right</th><th>Assumptions</th><th>Status</th></tr>\n");
            for (ReferenceCampaignRunner.RevisionSummary rev : evo.revisionHistory()) {
                sb.append("<tr><td>r").append(rev.revisionIndex()).append("</td>")
                    .append("<td><code>").append(esc(rev.leftPattern())).append("</code></td>")
                    .append("<td><code>").append(esc(rev.rightPattern())).append("</code></td>")
                    .append("<td>").append(esc(String.join(", ", rev.assumptions()))).append("</td>")
                    .append("<td>").append(esc(rev.status())).append("</td></tr>\n");
            }
            sb.append("</table>\n");
            if (evo.acceptedRevision() != null) {
                ReferenceCampaignRunner.RevisionSummary accepted = evo.acceptedRevision();
                sb.append("<p><strong>Accepted revision:</strong> <code>")
                    .append(esc(accepted.leftPattern())).append(" = ")
                    .append(esc(accepted.rightPattern())).append("</code>")
                    .append(" with assumptions: <code>")
                    .append(esc(String.join(", ", accepted.assumptions()))).append("</code></p>\n");
            }
            sb.append("<p>Rejected/inconclusive revisions remain inspectable in the table above.</p>\n");
        } else if (evo != null) {
            sb.append("<p>No refinement revisions recorded.</p>\n");
        } else {
            sb.append("<p>Refinement stage not reached.</p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderValidateStage(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HoldoutReport hr = report.holdoutReport();
        boolean done = hr != null && hr.overallPass();
        String status = done ? "done" : (hr != null ? "inconclusive" : "blocked");
        String badge = done ? "VALIDATED" : (hr != null ? "PARTIAL" : "BLOCKED");

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("5 – Validate", status, badge));
        if (hr != null) {
            sb.append("<p class=\"honesty-note\">⚠️ Holdout success ≠ mathematical proof. ")
                .append("Holdouts cover unseen examples but cannot exhaust all cases.</p>\n");
            sb.append("<p>Positive holdouts: <strong>").append(hr.positivePassCount())
                .append(" / ").append(hr.positiveCount()).append("</strong> passed.  ");
            sb.append("Negative holdouts: <strong>").append(hr.negativeBlockedCount())
                .append(" / ").append(hr.negativeCount()).append("</strong> correctly blocked.</p>\n");
            sb.append("<p>Overall: <strong>").append(done ? "PASS ✓" : "FAIL ✗").append("</strong></p>\n");
        } else {
            sb.append("<p>Holdout validation not reached.</p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderProveStage(ReferenceCampaignRunner.CampaignReport report) {
        de.regelsuche.sympyqa.SymPyQaHarness.QaSummary proof = report.proofSummary();
        boolean sympyUsed = proof != null && proof.sympyAvailableCases() > 0;
        boolean done = sympyUsed && proof.disagreements() == 0;
        String status = done ? "done" : (proof != null ? "inconclusive" : "blocked");
        String badge = done ? "EXTERNALLY_CONFIRMED" : (sympyUsed ? "DISAGREEMENTS_PRESENT" : (proof != null ? "SYMPY_UNAVAILABLE" : "BLOCKED"));

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("6 – Prove", status, badge));
        sb.append("<p class=\"honesty-note\">⚠️ Script-generation ≠ prover confirmation. ")
            .append("Prover availability is checked at runtime. Missing SymPy means this stage is skipped.</p>\n");
        if (proof != null) {
            sb.append("<p>SymPy QA harness: total=<strong>").append(proof.totalCases())
                .append("</strong>, sympy-cases=<strong>").append(proof.sympyAvailableCases())
                .append("</strong>, disagreements=<strong>").append(proof.disagreements())
                .append("</strong>, path-found=<strong>").append(proof.regelsuchePathFound())
                .append("</strong>.</p>\n");
            if (!sympyUsed) {
                sb.append("<p><em>SymPy not available in this environment – external confirmation skipped.</em></p>\n");
            }
        } else {
            sb.append("<p>Proof stage not reached.</p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    private String renderLearnStage(ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.ReuseAblation abl = report.reuseAblation();
        boolean done = abl != null && abl.measuredImprovement();
        String status = done ? "done" : (abl != null ? "inconclusive" : "blocked");
        String badge = done ? "LEARNED" : (abl != null ? "NO_IMPROVEMENT" : "BLOCKED");

        StringBuilder sb = new StringBuilder();
        sb.append(stageOpen("7 – Learn", status, badge));
        if (report.promotionRecord() != null) {
            sb.append("<p>Promoted candidate: <code>")
                .append(esc(report.promotionRecord().candidateId())).append("</code>  \n");
            sb.append("Stage: <code>").append(esc(report.promotionRecord().stage().name()))
                .append("</code>  Oracle: <code>").append(esc(report.promotionRecord().oracleStatus()))
                .append("</code></p>\n");
        } else {
            sb.append("<p>No candidate promoted in this run.</p>\n");
        }
        if (abl != null) {
            sb.append("<p>Reuse ablation: improved <strong>").append(abl.improvedCount())
                .append(" / ").append(abl.totalCount()).append("</strong> cases.  ")
                .append("Measured improvement: <strong>")
                .append(abl.measuredImprovement() ? "YES ✓" : "NO ✗").append("</strong></p>\n");
        }
        sb.append(stageClose());
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Markdown stage renderers
    // -----------------------------------------------------------------------

    private void appendObserveMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        List<ReferenceCampaignRunner.TrainingResult> training = report.training();
        long successCount = training.stream().filter(ReferenceCampaignRunner.TrainingResult::success).count();
        out.append("_⚠️ Search-found-path ≠ mathematical proof._\n\n");
        out.append("Ran **").append(training.size()).append("** training observations; **")
            .append(successCount).append("** found a path (SEARCH_FOUND_PATH).\n\n");
        out.append("| ID | Input | Target | Path found |\n");
        out.append("| --- | --- | --- | --- |\n");
        for (ReferenceCampaignRunner.TrainingResult t : training) {
            out.append("| ").append(mdEsc(t.id()))
                .append(" | `").append(mdEsc(t.inputExpression())).append("`")
                .append(" | `").append(mdEsc(t.targetExpression())).append("`")
                .append(" | ").append(t.success() ? "✓" : "✗")
                .append(" |\n");
        }
        out.append("\n");
    }

    private void appendGeneralizeMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        if (evo != null) {
            out.append("Initial conjecture (intentionally overgeneralised — no positivity assumptions):\n\n");
            out.append("> `").append(evo.initialLeftPattern()).append(" = ")
                .append(evo.initialRightPattern()).append("`\n\n");
        } else {
            out.append("No hypothesis formed.\n\n");
        }
    }

    private void appendChallengeMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        out.append("_⚠️ No-counterexample-found ≠ proof._\n\n");
        if (evo != null && evo.counterexampleReport() != null) {
            ReferenceCampaignRunner.CounterexampleReport cex = evo.counterexampleReport();
            if (!cex.counterexamples().isEmpty()) {
                out.append("Found **").append(cex.counterexamples().size())
                    .append("** counterexample(s):\n\n");
                for (ReferenceCampaignRunner.CounterexampleEntry entry : cex.counterexamples()) {
                    out.append("- Revision `").append(entry.revisionId())
                        .append("`: `").append(String.join(", ", entry.assignments()))
                        .append("`, strategy `").append(entry.strategyName()).append("`\n");
                }
                out.append("\n");
            } else {
                out.append("No counterexamples found.\n\n");
            }
        } else {
            out.append("Challenge stage not reached.\n\n");
        }
    }

    private void appendRefineMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        if (evo != null && !evo.revisionHistory().isEmpty()) {
            out.append("Ran **").append(evo.revisionHistory().size()).append("** revision(s):\n\n");
            out.append("| Revision | Left | Right | Assumptions | Status |\n");
            out.append("| --- | --- | --- | --- | --- |\n");
            for (ReferenceCampaignRunner.RevisionSummary rev : evo.revisionHistory()) {
                out.append("| r").append(rev.revisionIndex())
                    .append(" | `").append(mdEsc(rev.leftPattern())).append("`")
                    .append(" | `").append(mdEsc(rev.rightPattern())).append("`")
                    .append(" | ").append(mdEsc(String.join(", ", rev.assumptions())))
                    .append(" | ").append(rev.status())
                    .append(" |\n");
            }
            out.append("\n");
            if (evo.acceptedRevision() != null) {
                ReferenceCampaignRunner.RevisionSummary accepted = evo.acceptedRevision();
                out.append("**Accepted:** `").append(accepted.leftPattern()).append(" = ")
                    .append(accepted.rightPattern()).append("` with `")
                    .append(String.join(", ", accepted.assumptions())).append("`\n\n");
            }
        } else {
            out.append("No refinement revisions.\n\n");
        }
    }

    private void appendValidateMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.HoldoutReport hr = report.holdoutReport();
        out.append("_⚠️ Holdout success ≠ mathematical proof._\n\n");
        if (hr != null) {
            out.append("- Positive holdouts: **").append(hr.positivePassCount())
                .append(" / ").append(hr.positiveCount()).append("** passed\n");
            out.append("- Negative holdouts: **").append(hr.negativeBlockedCount())
                .append(" / ").append(hr.negativeCount()).append("** blocked\n");
            out.append("- Overall: **").append(hr.overallPass() ? "PASS ✓" : "FAIL ✗").append("**\n\n");
        } else {
            out.append("Holdout validation not reached.\n\n");
        }
    }

    private void appendProveMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        de.regelsuche.sympyqa.SymPyQaHarness.QaSummary proof = report.proofSummary();
        out.append("_⚠️ Script-generation ≠ prover confirmation. SymPy availability checked at runtime._\n\n");
        if (proof != null) {
            out.append("SymPy QA harness: total=**").append(proof.totalCases())
                .append("**, sympy-cases=**").append(proof.sympyAvailableCases())
                .append("**, disagreements=**").append(proof.disagreements())
                .append("**, path-found=**").append(proof.regelsuchePathFound()).append("**\n\n");
            if (proof.sympyAvailableCases() == 0) {
                out.append("_SymPy not available – external confirmation skipped._\n\n");
            }
        } else {
            out.append("Proof stage not reached.\n\n");
        }
    }

    private void appendLearnMd(StringBuilder out, ReferenceCampaignRunner.CampaignReport report) {
        ReferenceCampaignRunner.ReuseAblation abl = report.reuseAblation();
        if (report.promotionRecord() != null) {
            out.append("Promoted: `").append(report.promotionRecord().candidateId())
                .append("` (stage: `").append(report.promotionRecord().stage().name())
                .append("`, oracle: `").append(report.promotionRecord().oracleStatus()).append("`)\n\n");
        } else {
            out.append("No candidate promoted.\n\n");
        }
        if (abl != null) {
            out.append("Reuse ablation: improved **").append(abl.improvedCount())
                .append(" / ").append(abl.totalCount()).append("** cases. ")
                .append("Measured improvement: **").append(abl.measuredImprovement() ? "YES ✓" : "NO ✗")
                .append("**\n\n");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String stageOpen(String title, String statusCss, String badge) {
        String badgeCss = switch (statusCss) {
            case "done" -> "badge-done";
            case "inconclusive" -> "badge-inconclusive";
            case "blocked" -> "badge-blocked";
            default -> "badge-skipped";
        };
        return "<div class=\"stage " + statusCss + "\">\n"
            + "<div class=\"stage-title\">Stage " + esc(title)
            + "<span class=\"stage-badge " + badgeCss + "\">" + esc(badge) + "</span></div>\n";
    }

    private String stageClose() {
        return "</div>\n";
    }

    private String modeLabel(ResultMode mode) {
        return switch (mode) {
            case LIVE -> "LIVE EXECUTION";
            case REPLAYED_FROM_CANONICAL -> "REPLAYED FROM CANONICAL EVIDENCE";
            case PRE_GENERATED_REFERENCE -> "PRE-GENERATED REFERENCE EVIDENCE";
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

    private static String mdEsc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ");
    }
}
