package de.regelsuche.benchmark;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stable port for running discovery experiments over a seed corpus.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): planned experiment-runner / replay / report
 * features depend on this abstraction so the corpus source, the search
 * driver and the result sink stay decoupled and individually replaceable.
 *
 * <p>The runner is intentionally minimal: it consumes seed expressions and
 * returns a result summary. Concrete implementations choose how to wire
 * search strategies, repositories and trace stores.
 */
public interface DiscoveryExperimentRunner {

    /**
     * Execute the experiment for the given {@code seedExpressions}.
     *
     * @return one {@link ExperimentResult} per seed, in the same order as
     *     the input list.
     */
    List<ExperimentResult> run(List<String> seedExpressions);

    /**
     * Checkout-owned command entry point for retained discovery experiments.
     *
     * <p>The first command is deliberately explicit so additional experiments
     * can share the entry point without inventing one public launcher class per
     * report.</p>
     */
    static void main(String[] args) {
        if (args.length == 0 || !"historical-rediscovery".equals(args[0])) {
            throw new IllegalArgumentException(
                "expected command: historical-rediscovery [output-directory]");
        }
        Path output = args.length >= 2
            ? Path.of(args[1])
            : Path.of("build/reports/historical-rediscovery");
        HistoricalRediscoveryCorpus.Corpus corpus =
            HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(corpus);
        verifyRetainedClaims(report);

        HistoricalWitnessPruningDiagnostic pruning =
            new HistoricalWitnessPruningDiagnostic();
        HistoricalWitnessPruningDiagnostic.Report pruningReport =
            pruning.run(corpus, report);
        verifyWitnessPruning(report, pruningReport);

        HistoricalRediscoveryRunArtifact.begin(output);
        HistoricalRediscoveryAtlas.WrittenArtifacts artifacts =
            atlas.write(output, report);
        HistoricalRediscoveryRunArtifact.VerifiedRun verifiedRun =
            HistoricalRediscoveryRunArtifact.commit(
                output,
                corpus,
                report,
                artifacts);
        Path pruningArtifact =
            pruning.write(witnessOutput(output), pruningReport);

        System.out.println("historicalRediscoveryAssessment="
            + report.assessment().decision());
        System.out.println("historicalRediscoveryCases=" + report.cases().size());
        System.out.println("historicalRediscoveryJson="
            + artifacts.json().toAbsolutePath().normalize());
        System.out.println("historicalRediscoveryMarkdown="
            + artifacts.markdown().toAbsolutePath().normalize());
        System.out.println("historicalRediscoveryRun="
            + verifiedRun.manifestPath());
        System.out.println("historicalRediscoveryRunHash="
            + verifiedRun.manifest().contentHash());
        System.out.println("historicalWitnessPruning=" + pruningArtifact);
        System.out.println("historicalWitnessPruningHash="
            + pruningReport.contentHash());
    }

    /**
     * Prevents aggregate or per-case claims that are stronger than the
     * retained evidence used to derive them.
     */
    private static void verifyRetainedClaims(
        HistoricalRediscoveryAtlas.AtlasReport report
    ) {
        boolean targetBlindDiversitySignal = false;
        for (HistoricalRediscoveryAtlas.CaseResult result : report.cases()) {
            switch (result.status()) {
                case GENERIC_BRIDGE_REQUIRED_AND_FOUND,
                     CURATED_CONTROL_ONLY_MISSING_PRODUCTION_PRIMITIVE -> {
                    if (!result.production().oracle().completeClosureExhausted()) {
                        throw new IllegalStateException(
                            "claim requires complete production closure: "
                                + result.benchmarkCase().id()
                                + " has production oracle status "
                                + result.production().oracle().status());
                    }
                }
                case REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND ->
                    targetBlindDiversitySignal = true;
                default -> {
                    // No additional cross-case claim invariant.
                }
            }
        }
        if (report.assessment().searchPolicyDifferenceIdentified()
                != targetBlindDiversitySignal) {
            throw new IllegalStateException(
                "search-policy claim must be backed by a target-blind "
                    + "structural-diversity result");
        }
    }

    private static void verifyWitnessPruning(
        HistoricalRediscoveryAtlas.AtlasReport atlas,
        HistoricalWitnessPruningDiagnostic.Report diagnostic
    ) {
        Map<String, HistoricalWitnessPruningDiagnostic.CaseDiagnostic> byId =
            diagnostic.cases().stream().collect(Collectors.toMap(
                HistoricalWitnessPruningDiagnostic.CaseDiagnostic::id,
                Function.identity()));
        if (byId.size() != atlas.cases().size()) {
            throw new IllegalStateException(
                "witness-pruning diagnostic must balance every atlas case");
        }
        for (HistoricalRediscoveryAtlas.CaseResult result : atlas.cases()) {
            HistoricalWitnessPruningDiagnostic.CaseDiagnostic retained =
                byId.get(result.benchmarkCase().id());
            if (retained == null) {
                throw new IllegalStateException(
                    "missing witness-pruning case "
                        + result.benchmarkCase().id());
            }
            boolean policySignal = result.status()
                == HistoricalRediscoveryAtlas.PrimaryStatus
                    .REACHABLE_BUT_SCALAR_MISSED_DIVERSITY_FOUND;
            boolean lossRetained = HistoricalWitnessPruningDiagnostic
                .WITNESS_PREFIX_LOST.equals(retained.status());
            if (policySignal && !lossRetained) {
                throw new IllegalStateException(
                    "target-blind diversity signal requires a retained "
                        + "scalar witness-prefix loss: "
                        + result.benchmarkCase().id());
            }
        }
    }

    private static Path witnessOutput(Path atlasOutput) {
        Path normalized = atlasOutput.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        if (name == null) {
            throw new IllegalArgumentException(
                "historical rediscovery output must have a file name");
        }
        return normalized.resolveSibling(name + "-witness-pruning");
    }

    /**
     * Outcome of a single seed run.
     *
     * @param seedExpression the input the runner was started with
     * @param success whether the run reached a useful terminal state
     * @param summary short, human-readable result description
     */
    record ExperimentResult(String seedExpression, boolean success, String summary) {
    }
}
